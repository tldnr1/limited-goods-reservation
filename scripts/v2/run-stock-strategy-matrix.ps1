[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("naive-rdb", "rdb-atomic", "rdb-pessimistic", "redis-lua")]
    [string] $Strategy,
    [int[]] $Users = @(100, 500, 1000),
    [int] $Repeats = 5,
    [int] $InitialStock = 100,
    [int] $ProductId = 1,
    [switch] $Smoke,
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"

if ($Smoke) {
    $Users = @(10)
    $Repeats = 1
}

$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $Root

$RawDir = Join-Path $Root "notes\v2-stock-strategy\raw"
$CsvPath = if ($Smoke) {
    Join-Path $RawDir "v2-stock-strategy-smoke-$Strategy.csv"
} else {
    Join-Path $Root "records\experiments\v2-stock-strategy-comparison.csv"
}
New-Item -ItemType Directory -Force -Path $RawDir | Out-Null
if ($Smoke -and (Test-Path -LiteralPath $CsvPath)) {
    Remove-Item -LiteralPath $CsvPath -Force
}

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

function Invoke-K6Run {
    param(
        [int] $UserCount,
        [int] $Repeat,
        [string] $RunId
    )

    & docker compose --profile load-test run -T --rm `
        -e STOCK_STRATEGY=$Strategy `
        -e PRODUCT_ID=$ProductId `
        -e VUS=$UserCount `
        -e ITERATIONS=$UserCount `
        -e RUN_ID=$RunId `
        -e REPEAT=$Repeat `
        -e INITIAL_STOCK=$InitialStock `
        k6

    if ($LASTEXITCODE -ne 0) {
        throw "k6 run failed for $RunId with exit code $LASTEXITCODE"
    }
}

function Read-K6Summary {
    param([string] $RunId)

    $summaryPath = Join-Path $RawDir "$RunId.summary.json"
    if (-not (Test-Path $summaryPath)) {
        throw "Missing k6 summary file: $summaryPath"
    }

    return Get-Content -Raw -Path $summaryPath | ConvertFrom-Json
}

function Get-MetricValue {
    param($MetricValues, [string] $Name)

    if ($null -eq $MetricValues) {
        return 0
    }

    $property = $MetricValues.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return 0
    }

    return [double] $property.Value
}

if (-not $SkipBuild) {
    Invoke-Compose -Arguments @("build", "api")
}

Invoke-Compose -Arguments @("down", "-v")
$env:STOCK_STRATEGY = $Strategy
Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "api", "prometheus", "grafana")
Wait-Api

$startedAt = Get-Date -Format "yyyyMMdd-HHmmss"
$warmupRunId = "$startedAt-$Strategy-warmup"
Write-Host "Warm-up run: $warmupRunId"
Reset-ExperimentState
Invoke-K6Run -UserCount 10 -Repeat 0 -RunId $warmupRunId

foreach ($userCount in $Users) {
    for ($repeat = 1; $repeat -le $Repeats; $repeat += 1) {
        $runId = "$startedAt-$Strategy-u$userCount-r$repeat"
        Write-Host "Measured run: $runId"

        Reset-ExperimentState
        Invoke-K6Run -UserCount $userCount -Repeat $repeat -RunId $runId

        $summary = Read-K6Summary $runId
        $duration = $summary.metrics.http_req_duration.values
        $db = Read-DbMetrics
        $redisAvailable = Read-RedisAvailable

        if ($Strategy -eq "redis-lua") {
            $stockDecisionCount = $InitialStock - $redisAvailable
        } else {
            $stockDecisionCount = $db.SoldQuantity
        }

        $row = [pscustomobject]@{
            run_id = $runId
            strategy = $Strategy
            users = $userCount
            repeat = $repeat
            initial_stock = $InitialStock
            http_reqs = Get-MetricValue $summary.metrics.http_reqs.values "count"
            http_failed_rate = Get-MetricValue $summary.metrics.http_req_failed.values "rate"
            http_p50_ms = Get-MetricValue $duration "p(50)"
            http_p95_ms = Get-MetricValue $duration "p(95)"
            http_p99_ms = Get-MetricValue $duration "p(99)"
            successful_purchases = Get-MetricValue $summary.metrics.successful_purchases.values "count"
            sold_out_responses = Get-MetricValue $summary.metrics.sold_out_responses.values "count"
            lock_timeout_responses = Get-MetricValue $summary.metrics.lock_timeout_responses.values "count"
            unexpected_responses = Get-MetricValue $summary.metrics.unexpected_responses.values "count"
            db_initial_quantity = $db.InitialQuantity
            db_sold_quantity = $db.SoldQuantity
            db_order_count = $db.OrderCount
            redis_available = $redisAvailable
            stock_decision_count = $stockDecisionCount
            oversell_count = $db.OversellCount
            decision_order_gap = $db.OrderCount - $stockDecisionCount
        }

        $row | Export-Csv -Path $CsvPath -NoTypeInformation -Append
    }
}

Write-Host "v2 stock strategy run finished."
Write-Host "Strategy: $Strategy"
Write-Host "Raw files: $RawDir"
Write-Host "CSV: $CsvPath"
