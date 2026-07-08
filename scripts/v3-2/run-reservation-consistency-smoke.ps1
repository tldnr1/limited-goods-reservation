[CmdletBinding()]
param(
    [int] $ProductId = 1,
    [int] $InitialStock = 5,
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $Root

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
        [string] $FailureMode,
        [int] $FailureLimit
    )

    $env:STOCK_STRATEGY = "redis-lua"
    $env:PURCHASE_FAILURE_MODE = $FailureMode
    $env:PURCHASE_FAILURE_LIMIT = "$FailureLimit"
    $env:WAITING_ROOM_ENABLED = "true"
    $env:WAITING_ROOM_PRODUCT_ID = "$ProductId"
    $env:WAITING_ROOM_ADMISSION_SCHEDULER_ENABLED = "true"
    $env:WAITING_ROOM_ADMISSION_BATCH_SIZE = "10"
    $env:WAITING_ROOM_ADMISSION_ACTIVE_CAPACITY = "10"
    $env:WAITING_ROOM_ACTIVE_TOKEN_TTL_SECONDS = "60"
    $env:WAITING_ROOM_ADMISSION_INTERVAL_MS = "1000"

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

function Invoke-Json {
    param(
        [string] $Method,
        [string] $Uri,
        [hashtable] $Headers,
        [object] $Body = $null
    )

    $jsonBody = $null
    if ($null -ne $Body) {
        $jsonBody = $Body | ConvertTo-Json -Compress
    }

    try {
        $response = Invoke-WebRequest `
            -UseBasicParsing `
            -Method $Method `
            -Uri $Uri `
            -Headers $Headers `
            -ContentType "application/json" `
            -Body $jsonBody `
            -TimeoutSec 10
        return [pscustomobject]@{
            StatusCode = [int] $response.StatusCode
            Body = Parse-JsonBody $response.Content
        }
    } catch {
        $errorResponse = $_.Exception.Response
        if ($null -eq $errorResponse) {
            throw
        }

        $reader = New-Object System.IO.StreamReader($errorResponse.GetResponseStream())
        $content = $reader.ReadToEnd()
        return [pscustomobject]@{
            StatusCode = [int] $errorResponse.StatusCode
            Body = Parse-JsonBody $content
        }
    }
}

function Parse-JsonBody {
    param([string] $Content)

    if ([string]::IsNullOrWhiteSpace($Content)) {
        return $null
    }

    return $Content | ConvertFrom-Json
}

function Assert-Equals {
    param(
        [object] $Actual,
        [object] $Expected,
        [string] $Message
    )

    if ($Actual -ne $Expected) {
        throw "$Message Expected=[$Expected] Actual=[$Actual]"
    }
}

function Enter-And-Wait-Active {
    param([long] $UserId)

    $headers = @{ "X-USER-ID" = "$UserId" }
    $enter = Invoke-Json `
        -Method "POST" `
        -Uri "http://localhost:8080/api/v3/waiting-room/enter" `
        -Headers $headers `
        -Body @{ productId = $ProductId }
    Assert-Equals $enter.StatusCode 200 "waiting room enter should succeed."

    for ($i = 0; $i -lt 10; $i += 1) {
        $status = Invoke-Json `
            -Method "GET" `
            -Uri "http://localhost:8080/api/v3/waiting-room/status?productId=$ProductId" `
            -Headers $headers
        Assert-Equals $status.StatusCode 200 "waiting room status should succeed."
        if ($status.Body.status -eq "ACTIVE") {
            return
        }

        Start-Sleep -Seconds 1
    }

    throw "User did not become ACTIVE. userId=$UserId"
}

function Purchase {
    param(
        [long] $UserId,
        [string] $IdempotencyKey,
        [string] $RunId = "smoke"
    )

    return Invoke-Json `
        -Method "POST" `
        -Uri "http://localhost:8080/api/v1/purchases" `
        -Headers @{
            "X-USER-ID" = "$UserId"
            "X-IDEMPOTENCY-KEY" = $IdempotencyKey
            "X-RUN-ID" = $RunId
        } `
        -Body @{ productId = $ProductId }
}

function Read-DbState {
    $query = "select count(*) filter (where status = 'RESERVED') from reservations where product_id = $ProductId;"
    $output = & docker compose exec -T postgres psql -U limited_goods -d limited_goods_reservation -q -t -A -c $query
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read reservation count."
    }

    $line = $output | Where-Object { $_ -and $_.Trim() } | Select-Object -Last 1
    return [int] $line.Trim()
}

function Read-RedisInteger {
    param([string[]] $Arguments)

    $value = & docker compose exec -T redis redis-cli @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read Redis value."
    }

    $line = $value | Where-Object { $null -ne $_ -and $_.ToString().Trim() } | Select-Object -First 1
    if (-not $line) {
        return $null
    }

    return [int] $line.ToString().Trim()
}

function Assert-Gap {
    param([int] $ExpectedReservedCount)

    $reservedCount = Read-DbState
    $redisAvailable = Read-RedisInteger -Arguments @("GET", "stock:available:$ProductId")
    $stockDecisionCount = $InitialStock - $redisAvailable
    $gap = $reservedCount - $stockDecisionCount

    Assert-Equals $reservedCount $ExpectedReservedCount "reserved count mismatch."
    Assert-Equals $gap 0 "Redis decision and DB reservation gap should be zero."
}

if (-not $SkipBuild) {
    Invoke-Compose -Arguments @("build", "api")
}

Invoke-Compose -Arguments @("down", "-v")

Write-Host "v3.2 normal reservation smoke"
Start-Api -FailureMode "off" -FailureLimit 0
Reset-State

Enter-And-Wait-Active -UserId 1001
$created = Purchase -UserId 1001 -IdempotencyKey "normal-1001" -RunId "normal-smoke"
Assert-Equals $created.StatusCode 201 "first reservation should be created."
Assert-Equals $created.Body.status "RESERVED" "reservation status should be RESERVED."

$idempotent = Purchase -UserId 1001 -IdempotencyKey "normal-1001" -RunId "normal-smoke"
Assert-Equals $idempotent.StatusCode 200 "same idempotency key should reuse reservation."
Assert-Equals $idempotent.Body.reservationId $created.Body.reservationId "idempotent response should reuse reservation id."

$duplicate = Purchase -UserId 1001 -IdempotencyKey "normal-1001-duplicate" -RunId "normal-smoke"
Assert-Equals $duplicate.StatusCode 409 "same user/product with different idempotency key should be rejected."
Assert-Equals $duplicate.Body.code "ALREADY_RESERVED" "duplicate reservation should return ALREADY_RESERVED."

$noToken = Purchase -UserId 1002 -IdempotencyKey "normal-1002" -RunId "normal-smoke"
Assert-Equals $noToken.StatusCode 409 "request without active token should be rejected."
Assert-Equals $noToken.Body.code "ACTIVE_TOKEN_REQUIRED" "missing active token should return ACTIVE_TOKEN_REQUIRED."
Assert-Gap -ExpectedReservedCount 1

Write-Host "v3.2 failure compensation smoke"
Start-Api -FailureMode "AFTER_STOCK_DECISION_BEFORE_RESERVATION_SAVE" -FailureLimit 1
Reset-State

Enter-And-Wait-Active -UserId 2001
$failed = Purchase -UserId 2001 -IdempotencyKey "failure-2001" -RunId "failure-smoke"
Assert-Equals $failed.StatusCode 503 "injected reservation failure should be retryable."
Assert-Equals $failed.Body.code "RESERVATION_FAILED_RETRYABLE" "failure should be exposed as retryable reservation failure."
Assert-Gap -ExpectedReservedCount 0

$restoredToken = Read-RedisInteger -Arguments @("EXISTS", "active-token:$ProductId`:2001")
Assert-Equals $restoredToken 1 "active token should be restored after successful compensation."

$retried = Purchase -UserId 2001 -IdempotencyKey "failure-2001" -RunId "failure-smoke"
Assert-Equals $retried.StatusCode 201 "retry after token restore should create reservation."
Assert-Equals $retried.Body.status "RESERVED" "retried reservation status should be RESERVED."
Assert-Gap -ExpectedReservedCount 1

Write-Host "Restoring API to non-failure mode and clean state"
Start-Api -FailureMode "off" -FailureLimit 0
Reset-State

Write-Host "v3.2 reservation consistency smoke passed."
