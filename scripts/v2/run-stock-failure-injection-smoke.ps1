[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("rdb-atomic", "redis-lua")]
    [string] $Strategy,
    [int] $Users = 500,
    [int] $InitialStock = 100,
    [int] $FailureLimit = 10,
    [int] $ProductId = 1,
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $Root

$RawDir = Join-Path $Root "notes\v2-stock-strategy\raw"
New-Item -ItemType Directory -Force -Path $RawDir | Out-Null

function Invoke-Compose {
    param([string[]] $Arguments)

    & docker compose @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Wait-Api {
    for ($i = 0; $i -lt 60; $i += 1) {
        try {
            $response = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 2
            if ($response.status -eq "UP") {
                return
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }

    throw "API health endpoint did not become ready."
}

function Reset-ExperimentState {
    $resetSql = "TRUNCATE TABLE orders RESTART IDENTITY; UPDATE product_stock SET initial_quantity = $InitialStock, sold_quantity = 0, updated_at = now() WHERE product_id = $ProductId;"
    & docker compose exec -T postgres psql -U limited_goods -d limited_goods_reservation -v ON_ERROR_STOP=1 -c $resetSql | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to reset PostgreSQL state."
    }

    & docker compose exec -T redis redis-cli FLUSHDB | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to flush Redis."
    }

    if ($Strategy -eq "redis-lua") {
        & docker compose exec -T redis redis-cli SET "stock:available:$ProductId" "$InitialStock" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to initialize Redis stock key."
        }
    }
}

function Read-DbMetrics {
    $query = "select ps.initial_quantity, ps.sold_quantity, count(o.id), greatest(count(o.id) - ps.initial_quantity, 0), count(o.id) - ps.sold_quantity from product_stock ps left join orders o on o.product_id = ps.product_id where ps.product_id = $ProductId group by ps.initial_quantity, ps.sold_quantity;"
    $output = & docker compose exec -T postgres psql -U limited_goods -d limited_goods_reservation -q -t -A -F "," -c $query
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read PostgreSQL verification metrics."
    }

    $line = $output | Where-Object { $_ -and $_.Trim() } | Select-Object -Last 1
    $parts = $line.Split(",")
    return [pscustomobject]@{
        InitialQuantity = [int] $parts[0]
        SoldQuantity = [int] $parts[1]
        OrderCount = [int] $parts[2]
        OversellCount = [int] $parts[3]
        RdbOrderStockGap = [int] $parts[4]
    }
}

function Read-RedisAvailable {
    if ($Strategy -ne "redis-lua") {
        return $null
    }

    $value = & docker compose exec -T redis redis-cli GET "stock:available:$ProductId"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read Redis stock key."
    }
    if (-not $value) {
        return $null
    }

    return [int] (($value | Select-Object -First 1).Trim())
}

if (-not $SkipBuild) {
    Invoke-Compose -Arguments @("build", "api")
}

Invoke-Compose -Arguments @("down", "-v")
$env:STOCK_STRATEGY = $Strategy
$env:PURCHASE_FAILURE_MODE = "AFTER_STOCK_DECISION_BEFORE_ORDER_SAVE"
$env:PURCHASE_FAILURE_LIMIT = "$FailureLimit"
$env:K6_SCRIPT = "/scripts/v2/stock-strategy-failure.js"
Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "api", "prometheus")
Wait-Api
Reset-ExperimentState
Wait-Api

$startedAt = Get-Date -Format "yyyyMMdd-HHmmss"
$runId = "$startedAt-$Strategy-failure-smoke"

& docker compose --profile load-test run -T --rm `
    -e STOCK_STRATEGY=$Strategy `
    -e PRODUCT_ID=$ProductId `
    -e VUS=$Users `
    -e ITERATIONS=$Users `
    -e RUN_ID=$runId `
    -e REPEAT=1 `
    -e INITIAL_STOCK=$InitialStock `
    -e PURCHASE_FAILURE_MODE=$env:PURCHASE_FAILURE_MODE `
    -e PURCHASE_FAILURE_LIMIT=$FailureLimit `
    k6

if ($LASTEXITCODE -ne 0) {
    throw "k6 failure smoke run failed for $runId with exit code $LASTEXITCODE"
}

$db = Read-DbMetrics
$redisAvailable = Read-RedisAvailable
if ($Strategy -eq "redis-lua") {
    $stockDecisionCount = $InitialStock - $redisAvailable
} else {
    $stockDecisionCount = $db.SoldQuantity
}

[pscustomobject]@{
    run_id = $runId
    strategy = $Strategy
    users = $Users
    initial_stock = $InitialStock
    failure_limit = $FailureLimit
    db_initial_quantity = $db.InitialQuantity
    db_sold_quantity = $db.SoldQuantity
    db_order_count = $db.OrderCount
    redis_available = $redisAvailable
    stock_decision_count = $stockDecisionCount
    oversell_count = $db.OversellCount
    decision_order_gap = $db.OrderCount - $stockDecisionCount
} | Format-List
