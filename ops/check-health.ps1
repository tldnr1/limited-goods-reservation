#requires -Version 7.0
param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $services = @()
    foreach ($service in @('postgres','redis','mock-pg','api1','api2','worker','nginx')) {
        $id = docker compose ps -q $service
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($id)) {
            throw "$service 컨테이너가 없습니다. 먼저 Compose를 --wait 옵션으로 기동하세요."
        }
        $raw = docker inspect $id
        if ($LASTEXITCODE -ne 0) { throw "$service 상태 조회 실패" }
        $container = @($raw | ConvertFrom-Json)[0]
        if (-not $container.State.Running) { throw "$service 컨테이너가 실행 중이 아닙니다." }
        if ($service -ne 'nginx' -and $container.State.Health.Status -ne 'healthy') {
            throw "$service 준비 상태가 healthy가 아닙니다."
        }
        $services += [pscustomobject]@{ service=$service; status=$container.State.Status; health=$container.State.Health.Status }
    }
    # Read-only request: verifies Nginx routing and the DB-backed API.
    $response = Invoke-WebRequest "$BaseUrl/api/sales/00000000-0000-0000-0000-000000000000" -SkipHttpErrorCheck -TimeoutSec 5
    if ($response.StatusCode -ne 404 -or ($response.Content | ConvertFrom-Json).code -ne 'SALE_NOT_FOUND') {
        throw 'Nginx/API 경로 확인 실패'
    }
    [pscustomobject]@{ status='ready'; baseUrl=$BaseUrl; services=$services }
} finally { Pop-Location }
