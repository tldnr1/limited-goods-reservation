[CmdletBinding()]
param(
    [ValidateSet("direct", "fixed", "hybrid")]
    [string[]] $Policies = @("direct", "fixed", "hybrid"),
    [int[]] $Users = @(1000),
    [int] $Repeats = 1,
    [int] $InitialStock = 100,
    [int] $ProductId = 1,
    [int] $FixedBatchSize = 20,
    [int] $FixedActiveCapacity = 10000,
    [int] $HybridBatchSize = 20,
    [int] $HybridActiveCapacity = 100,
    [int] $AdmissionIntervalMs = 1000,
    [int] $TokenTtlSeconds = 60,
    [int] $MaxPolls = 10,
    [string] $ThinkTimes = "0",
    [string] $MaxDuration = "2m",
    [string] $ResultName = "v3-1-entry-control-initial",
    [switch] $Smoke,
    [switch] $SkipBuild,
    [switch] $AppendResult
)

$ErrorActionPreference = "Stop"

if ($Smoke) {
    $Users = @(30)
    $Repeats = 1
    $MaxPolls = 3
    $ResultName = "v3-1-entry-control-smoke"
}

$ThinkTimeValues = $ThinkTimes.Split(",") |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -ne "" } |
        ForEach-Object { [int] $_ }

$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $Root

$RawDir = Join-Path $Root "notes\v3-1-entry-control\raw"
$CsvPath = Join-Path $Root "records\experiments\$ResultName.csv"
New-Item -ItemType Directory -Force -Path $RawDir | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $CsvPath) | Out-Null
if ((-not $AppendResult) -and (Test-Path -LiteralPath $CsvPath)) {
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

    & docker compose exec -T redis redis-cli SET "stock:available:$ProductId" "$InitialStock" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to initialize Redis stock key."
    }
}

function Start-ApiForPolicy {
    param(
        [string] $Policy,
        [int] $BatchSize,
        [int] $ActiveCapacity
    )

    $env:STOCK_STRATEGY = "redis-lua"
    $env:WAITING_ROOM_PRODUCT_ID = "$ProductId"
    $env:WAITING_ROOM_ADMISSION_BATCH_SIZE = "$BatchSize"
    $env:WAITING_ROOM_ADMISSION_ACTIVE_CAPACITY = "$ActiveCapacity"
    $env:WAITING_ROOM_ACTIVE_TOKEN_TTL_SECONDS = "$TokenTtlSeconds"
    $env:WAITING_ROOM_ADMISSION_INTERVAL_MS = "$AdmissionIntervalMs"
    $env:WAITING_ROOM_ENABLED = if ($Policy -eq "direct") { "false" } else { "true" }

    Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "api", "prometheus")
    Wait-Api
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

function Read-RedisInteger {
    param([string[]] $Arguments)

    $value = & docker compose exec -T redis redis-cli @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read Redis value: redis-cli $($Arguments -join ' ')"
    }

    $line = $value | Where-Object { $null -ne $_ -and $_.ToString().Trim() } | Select-Object -First 1
    if (-not $line) {
        return $null
    }

    return [int] $line.ToString().Trim()
}

function Invoke-K6Run {
    param(
        [string] $Policy,
        [int] $BatchSize,
        [int] $ActiveCapacity,
        [int] $ThinkTimeSeconds,
        [int] $UserCount,
        [int] $Repeat,
        [string] $RunId
    )

    $env:K6_RESULTS_DIR = "./notes/v3-1-entry-control/raw"
    $env:K6_SCRIPT = if ($Policy -eq "direct") {
        "/scripts/v3-1/direct-purchase.js"
    } else {
        "/scripts/v3-1/waiting-room.js"
    }

    & docker compose --profile load-test run -T --rm `
        -e ADMISSION_POLICY=$Policy `
        -e PRODUCT_ID=$ProductId `
        -e VUS=$UserCount `
        -e ITERATIONS=$UserCount `
        -e RUN_ID=$RunId `
        -e REPEAT=$Repeat `
        -e INITIAL_STOCK=$InitialStock `
        -e MAX_POLLS=$MaxPolls `
        -e MAX_DURATION=$MaxDuration `
        -e PRE_PURCHASE_SLEEP_SECONDS=$ThinkTimeSeconds `
        -e WAITING_ROOM_ADMISSION_BATCH_SIZE=$BatchSize `
        -e WAITING_ROOM_ADMISSION_ACTIVE_CAPACITY=$ActiveCapacity `
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

function Get-PolicyConfig {
    param([string] $Policy)

    if ($Policy -eq "direct") {
        return [pscustomobject]@{
            BatchSize = 0
            ActiveCapacity = 0
        }
    }

    if ($Policy -eq "fixed") {
        return [pscustomobject]@{
            BatchSize = $FixedBatchSize
            ActiveCapacity = $FixedActiveCapacity
        }
    }

    return [pscustomobject]@{
        BatchSize = $HybridBatchSize
        ActiveCapacity = $HybridActiveCapacity
    }
}

if (-not $SkipBuild) {
    Invoke-Compose -Arguments @("build", "api")
}

Invoke-Compose -Arguments @("down", "-v")
$startedAt = Get-Date -Format "yyyyMMdd-HHmmss"

foreach ($policy in $Policies) {
    $config = Get-PolicyConfig -Policy $policy
    Write-Host "Starting policy=$policy batchSize=$($config.BatchSize) activeCapacity=$($config.ActiveCapacity)"
    Start-ApiForPolicy -Policy $policy -BatchSize $config.BatchSize -ActiveCapacity $config.ActiveCapacity

    foreach ($userCount in $Users) {
        foreach ($thinkTime in $ThinkTimeValues) {
            for ($repeat = 1; $repeat -le $Repeats; $repeat += 1) {
                $runId = "$startedAt-$policy-u$userCount-t$thinkTime-r$repeat"
                Write-Host "Measured run: $runId"

                Reset-ExperimentState
                Wait-Api
                Invoke-K6Run `
                    -Policy $policy `
                    -BatchSize $config.BatchSize `
                    -ActiveCapacity $config.ActiveCapacity `
                    -ThinkTimeSeconds $thinkTime `
                    -UserCount $userCount `
                    -Repeat $repeat `
                    -RunId $runId

                $summary = Read-K6Summary $runId
                $duration = $summary.metrics.http_req_duration.values
                $purchaseDuration = $summary.metrics.purchase_req_duration.values
                $db = Read-DbMetrics
                $redisAvailable = Read-RedisInteger -Arguments @("GET", "stock:available:$ProductId")
                $waitingQueueSize = Read-RedisInteger -Arguments @("ZCARD", "waiting:queue:$ProductId")
                $activeTokenCurrent = Read-RedisInteger -Arguments @("ZCARD", "active-token:index:$ProductId")
                $stockDecisionCount = $InitialStock - $redisAvailable

                $row = [pscustomobject]@{
                    run_id = $runId
                    policy = $policy
                    users = $userCount
                    repeat = $repeat
                    initial_stock = $InitialStock
                    batch_size = $config.BatchSize
                    active_capacity = $config.ActiveCapacity
                    max_polls = $MaxPolls
                    think_time_seconds = $thinkTime
                    http_reqs = Get-MetricValue $summary.metrics.http_reqs.values "count"
                    http_failed_rate = Get-MetricValue $summary.metrics.http_req_failed.values "rate"
                    http_p50_ms = Get-MetricValue $duration "p(50)"
                    http_p95_ms = Get-MetricValue $duration "p(95)"
                    http_p99_ms = Get-MetricValue $duration "p(99)"
                    purchase_p50_ms = Get-MetricValue $purchaseDuration "p(50)"
                    purchase_p95_ms = Get-MetricValue $purchaseDuration "p(95)"
                    purchase_p99_ms = Get-MetricValue $purchaseDuration "p(99)"
                    waiting_entries = Get-MetricValue $summary.metrics.waiting_entries.values "count"
                    active_statuses = Get-MetricValue $summary.metrics.active_statuses.values "count"
                    not_admitted_within_window = Get-MetricValue $summary.metrics.not_admitted_within_window.values "count"
                    purchase_attempts = Get-MetricValue $summary.metrics.purchase_attempts.values "count"
                    successful_purchases = Get-MetricValue $summary.metrics.successful_purchases.values "count"
                    sold_out_responses = Get-MetricValue $summary.metrics.sold_out_responses.values "count"
                    active_token_required_responses = Get-MetricValue $summary.metrics.active_token_required_responses.values "count"
                    unexpected_responses = Get-MetricValue $summary.metrics.unexpected_responses.values "count"
                    db_initial_quantity = $db.InitialQuantity
                    db_sold_quantity = $db.SoldQuantity
                    db_order_count = $db.OrderCount
                    redis_available = $redisAvailable
                    stock_decision_count = $stockDecisionCount
                    oversell_count = $db.OversellCount
                    decision_order_gap = $db.OrderCount - $stockDecisionCount
                    waiting_queue_size_after = $waitingQueueSize
                    active_token_current_after = $activeTokenCurrent
                }

                $row | Export-Csv -Path $CsvPath -NoTypeInformation -Append
            }
        }
    }
}

Write-Host "v3.1 entry-control matrix finished."
Write-Host "Raw files: $RawDir"
Write-Host "CSV: $CsvPath"
