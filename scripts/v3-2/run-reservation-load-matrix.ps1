[CmdletBinding()]
param(
    [int] $ProductId = 1,
    [int] $InitialStock = 100,
    [int] $NormalUsers = 1000,
    [int] $FailureUsers = 100,
    [int] $DuplicateUsers = 100,
    [string] $ResultName = "v3-2-frontgate-load",
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $Root

$RawDir = Join-Path $Root "notes\v3-2-reservation-load\raw"
$CsvPath = Join-Path $Root "records\experiments\$ResultName.csv"
New-Item -ItemType Directory -Force -Path $RawDir | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $CsvPath) | Out-Null
if (Test-Path -LiteralPath $CsvPath) {
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

function Start-Api {
    param(
        [string] $Architecture,
        [string] $FailureMode,
        [int] $FailureLimit
    )

    $env:PURCHASE_ARCHITECTURE = $Architecture
    $env:STOCK_STRATEGY = "rdb-atomic"
    $env:PURCHASE_FAILURE_MODE = $FailureMode
    $env:PURCHASE_FAILURE_LIMIT = "$FailureLimit"
    $env:WAITING_ROOM_ENABLED = "false"
    $env:WAITING_ROOM_ADMISSION_SCHEDULER_ENABLED = "false"
    Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "api")
    Wait-Api
}

function Reset-State {
    $resetSql = "TRUNCATE TABLE reservations, orders RESTART IDENTITY; UPDATE product_stock SET initial_quantity = $InitialStock, sold_quantity = 0, updated_at = now() WHERE product_id = $ProductId;"
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

function Invoke-K6Run {
    param(
        [string] $RunId,
        [string] $Architecture,
        [string] $ScenarioMode,
        [int] $Users,
        [int] $DuplicateRequests
    )

    $env:K6_RESULTS_DIR = "./notes/v3-2-reservation-load/raw"
    $env:K6_SCRIPT = "/scripts/v3-2/reservation-consistency.js"

    & docker compose --profile load-test run -T --rm `
        -e PURCHASE_ARCHITECTURE=$Architecture `
        -e STOCK_STRATEGY=rdb-atomic `
        -e PRODUCT_ID=$ProductId `
        -e VUS=$Users `
        -e ITERATIONS=$Users `
        -e RUN_ID=$RunId `
        -e INITIAL_STOCK=$InitialStock `
        -e SCENARIO_MODE=$ScenarioMode `
        -e DUPLICATE_REQUESTS=$DuplicateRequests `
        -e MAX_DURATION=2m `
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

function Read-DbMetrics {
    $query = "select ps.initial_quantity, ps.sold_quantity, (select count(*) from reservations r where r.product_id = $ProductId and r.status = 'RESERVED'), coalesce((select sum(cnt - 1) from (select count(*) cnt from reservations where product_id = $ProductId group by product_id, user_id having count(*) > 1) duplicates), 0) from product_stock ps where ps.product_id = $ProductId;"
    $output = & docker compose exec -T postgres psql -U limited_goods -d limited_goods_reservation -q -t -A -F "," -c $query
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read PostgreSQL metrics."
    }

    $line = $output | Where-Object { $_ -and $_.Trim() } | Select-Object -Last 1
    $parts = $line.Split(",")
    return [pscustomobject]@{
        InitialQuantity = [int] $parts[0]
        SoldQuantity = [int] $parts[1]
        ReservedCount = [int] $parts[2]
        DuplicateReservationCount = [int] $parts[3]
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

function Read-ActuatorCount {
    param(
        [string] $MetricName,
        [string] $Architecture
    )

    try {
        $encodedName = [System.Uri]::EscapeDataString($MetricName)
        $response = Invoke-RestMethod -Uri "http://localhost:8080/actuator/metrics/$encodedName`?tag=strategy:$Architecture" -TimeoutSec 5
        $countMeasurement = $response.measurements | Where-Object { $_.statistic -eq "COUNT" } | Select-Object -First 1
        if ($null -ne $countMeasurement) {
            return [double] $countMeasurement.value
        }

        return 0
    } catch {
        return 0
    }
}

function Get-StockDecisionCount {
    param(
        [string] $Architecture,
        $DbMetrics
    )

    if ($Architecture -eq "redis-frontgate") {
        $redisAvailable = Read-RedisInteger -Arguments @("GET", "stock:available:$ProductId")
        return $InitialStock - $redisAvailable
    }

    return $DbMetrics.SoldQuantity
}

if (-not $SkipBuild) {
    Invoke-Compose -Arguments @("build", "api")
}

Invoke-Compose -Arguments @("down", "-v")

$startedAt = Get-Date -Format "yyyyMMdd-HHmmss"
$runs = @(
    [pscustomobject]@{ Architecture = "redis-frontgate"; ScenarioMode = "normal"; Users = $NormalUsers; FailureMode = "off"; FailureLimit = 0; DuplicateRequests = 1 },
    [pscustomobject]@{ Architecture = "rdb-atomic"; ScenarioMode = "normal"; Users = $NormalUsers; FailureMode = "off"; FailureLimit = 0; DuplicateRequests = 1 },
    [pscustomobject]@{ Architecture = "redis-frontgate"; ScenarioMode = "failure"; Users = $FailureUsers; FailureMode = "AFTER_STOCK_DECISION_BEFORE_RESERVATION_SAVE"; FailureLimit = 10; DuplicateRequests = 1 },
    [pscustomobject]@{ Architecture = "rdb-atomic"; ScenarioMode = "failure"; Users = $FailureUsers; FailureMode = "AFTER_STOCK_DECISION_BEFORE_RESERVATION_SAVE"; FailureLimit = 10; DuplicateRequests = 1 },
    [pscustomobject]@{ Architecture = "redis-frontgate"; ScenarioMode = "duplicate"; Users = $DuplicateUsers; FailureMode = "off"; FailureLimit = 0; DuplicateRequests = 2 },
    [pscustomobject]@{ Architecture = "rdb-atomic"; ScenarioMode = "duplicate"; Users = $DuplicateUsers; FailureMode = "off"; FailureLimit = 0; DuplicateRequests = 2 }
)

foreach ($run in $runs) {
    $runId = "$startedAt-$($run.Architecture)-$($run.ScenarioMode)-u$($run.Users)"
    Write-Host "Starting $runId"
    Start-Api -Architecture $run.Architecture -FailureMode $run.FailureMode -FailureLimit $run.FailureLimit
    Reset-State
    Wait-Api
    Invoke-K6Run `
        -RunId $runId `
        -Architecture $run.Architecture `
        -ScenarioMode $run.ScenarioMode `
        -Users $run.Users `
        -DuplicateRequests $run.DuplicateRequests

    $summary = Read-K6Summary $runId
    $duration = $summary.metrics.http_req_duration.values
    $reservationDuration = $summary.metrics.reservation_req_duration.values
    $db = Read-DbMetrics
    $stockDecisionCount = Get-StockDecisionCount -Architecture $run.Architecture -DbMetrics $db
    $redisAvailable = if ($run.Architecture -eq "redis-frontgate") {
        Read-RedisInteger -Arguments @("GET", "stock:available:$ProductId")
    } else {
        $null
    }

    $row = [pscustomobject]@{
        run_id = $runId
        architecture = $run.Architecture
        strategy = $run.Architecture
        scenario_mode = $run.ScenarioMode
        users = $run.Users
        initial_stock = $InitialStock
        failure_limit = $run.FailureLimit
        duplicate_requests = $run.DuplicateRequests
        http_p50_ms = Get-MetricValue $duration "p(50)"
        http_p95_ms = Get-MetricValue $duration "p(95)"
        http_p99_ms = Get-MetricValue $duration "p(99)"
        reservation_p50_ms = Get-MetricValue $reservationDuration "p(50)"
        reservation_p95_ms = Get-MetricValue $reservationDuration "p(95)"
        reservation_p99_ms = Get-MetricValue $reservationDuration "p(99)"
        reservation_attempts = Get-MetricValue $summary.metrics.reservation_attempts.values "count"
        reservation_created = Get-MetricValue $summary.metrics.reservation_created.values "count"
        reservation_reused = Get-MetricValue $summary.metrics.reservation_reused.values "count"
        sold_out_responses = Get-MetricValue $summary.metrics.sold_out_responses.values "count"
        already_reserved_responses = Get-MetricValue $summary.metrics.already_reserved_responses.values "count"
        retryable_failure_responses = Get-MetricValue $summary.metrics.retryable_failure_responses.values "count"
        active_token_required_responses = Get-MetricValue $summary.metrics.active_token_required_responses.values "count"
        unexpected_responses = Get-MetricValue $summary.metrics.unexpected_responses.values "count"
        db_sold_quantity = $db.SoldQuantity
        db_reserved_count = $db.ReservedCount
        duplicate_reservation_count = $db.DuplicateReservationCount
        redis_available = $redisAvailable
        stock_decision_count = $stockDecisionCount
        decision_reservation_gap = $db.ReservedCount - $stockDecisionCount
        oversell_count = [Math]::Max($db.ReservedCount - $InitialStock, 0)
        idempotency_hit_metric = Read-ActuatorCount -MetricName "reservation.idempotency.hit" -Architecture $run.Architecture
        duplicate_rejected_metric = Read-ActuatorCount -MetricName "reservation.duplicate.rejected" -Architecture $run.Architecture
        front_gate_accepted_metric = Read-ActuatorCount -MetricName "reservation.front-gate.accepted" -Architecture $run.Architecture
        compensation_success_metric = Read-ActuatorCount -MetricName "reservation.compensation.success" -Architecture $run.Architecture
        compensation_failure_metric = Read-ActuatorCount -MetricName "reservation.compensation.failure" -Architecture $run.Architecture
    }

    $row | Export-Csv -Path $CsvPath -NoTypeInformation -Append
}

Write-Host "v3.2 reservation load draft finished."
Write-Host "Raw files: $RawDir"
Write-Host "CSV: $CsvPath"
