[CmdletBinding()]
param(
    [string] $Users = "1000,3000,5000,10000",
    [string] $TargetPath = "/actuator/health",
    [string] $ResultName = "v3-2-k6-generator-sanity",
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $Root

$RawDir = Join-Path $Root "notes\v3-2-generator-sanity\raw"
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

function Read-K6Summary {
    param([string] $RunId)

    $summaryPath = Join-Path $RawDir "$RunId.summary.json"
    if (-not (Test-Path $summaryPath)) {
        throw "Missing k6 summary file: $summaryPath"
    }

    return Get-Content -Raw -Path $summaryPath | ConvertFrom-Json
}

function Convert-Percent {
    param([string] $Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return 0
    }

    return [double] ($Value -replace "%", "")
}

function Convert-MemMiB {
    param([string] $Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return 0
    }

    $used = ($Value -split "/")[0].Trim()
    if ($used.EndsWith("GiB")) {
        return [double] ($used -replace "GiB", "") * 1024
    }
    if ($used.EndsWith("MiB")) {
        return [double] ($used -replace "MiB", "")
    }
    if ($used.EndsWith("KiB")) {
        return [double] ($used -replace "KiB", "") / 1024
    }
    if ($used.EndsWith("B")) {
        return [double] ($used -replace "B", "") / 1024 / 1024
    }

    return 0
}

function Start-StatsSampler {
    param([string] $Path)

    Start-Job -ScriptBlock {
        param([string] $StatsPath)
        while ($true) {
            docker stats --no-stream --format "{{json .}}" | Add-Content -Path $StatsPath
            Start-Sleep -Milliseconds 500
        }
    } -ArgumentList $Path
}

function Read-StatsSummary {
    param([string] $Path)

    $summary = [ordered]@{
        k6_max_cpu_percent = 0
        k6_max_mem_mib = 0
        api_max_cpu_percent = 0
        api_max_mem_mib = 0
        prometheus_max_cpu_percent = 0
        prometheus_max_mem_mib = 0
    }

    if (-not (Test-Path -LiteralPath $Path)) {
        return [pscustomobject] $summary
    }

    Get-Content -Path $Path | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object {
        try {
            $sample = $_ | ConvertFrom-Json
        } catch {
            return
        }

        $name = [string] $sample.Name
        if ([string]::IsNullOrWhiteSpace($name)) {
            $name = [string] $sample.Container
        }

        $cpu = Convert-Percent $sample.CPUPerc
        $mem = Convert-MemMiB $sample.MemUsage

        if ($name -like "*k6*") {
            $summary.k6_max_cpu_percent = [Math]::Max($summary.k6_max_cpu_percent, $cpu)
            $summary.k6_max_mem_mib = [Math]::Max($summary.k6_max_mem_mib, $mem)
        } elseif ($name -eq "limited-goods-api") {
            $summary.api_max_cpu_percent = [Math]::Max($summary.api_max_cpu_percent, $cpu)
            $summary.api_max_mem_mib = [Math]::Max($summary.api_max_mem_mib, $mem)
        } elseif ($name -eq "limited-goods-prometheus") {
            $summary.prometheus_max_cpu_percent = [Math]::Max($summary.prometheus_max_cpu_percent, $cpu)
            $summary.prometheus_max_mem_mib = [Math]::Max($summary.prometheus_max_mem_mib, $mem)
        }
    }

    return [pscustomobject] $summary
}

function Invoke-K6SanityRun {
    param(
        [string] $RunId,
        [int] $VuCount
    )

    $env:K6_RESULTS_DIR = "./notes/v3-2-generator-sanity/raw"
    $env:K6_SCRIPT = "/scripts/v3-2/generator-sanity.js"

    & docker compose --profile load-test run -T --rm `
        -e VUS=$VuCount `
        -e ITERATIONS=$VuCount `
        -e RUN_ID=$RunId `
        -e TARGET_PATH=$TargetPath `
        -e MAX_DURATION=2m `
        k6

    if ($LASTEXITCODE -ne 0) {
        throw "k6 sanity run failed for $RunId with exit code $LASTEXITCODE"
    }
}

if (-not $SkipBuild) {
    Invoke-Compose -Arguments @("build", "api")
}

$env:PURCHASE_ARCHITECTURE = "redis-frontgate"
$env:STOCK_STRATEGY = "rdb-atomic"
$env:PURCHASE_FAILURE_MODE = "off"
$env:PURCHASE_FAILURE_LIMIT = "0"
$env:WAITING_ROOM_ENABLED = "false"
$env:WAITING_ROOM_ADMISSION_SCHEDULER_ENABLED = "false"

Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "api", "prometheus")
Wait-Api

$startedAt = Get-Date -Format "yyyyMMdd-HHmmss"
$userCounts = $Users.Split(",") | ForEach-Object { [int] $_.Trim() }

foreach ($vu in $userCounts) {
    $runId = "$startedAt-generator-sanity-u$vu"
    $statsPath = Join-Path $RawDir "$runId.docker-stats.jsonl"
    Write-Host "Starting $runId"

    $statsJob = Start-StatsSampler -Path $statsPath
    try {
        Start-Sleep -Milliseconds 500
        Invoke-K6SanityRun -RunId $runId -VuCount $vu
    } finally {
        Stop-Job $statsJob -ErrorAction SilentlyContinue | Out-Null
        Remove-Job $statsJob -Force -ErrorAction SilentlyContinue
    }

    $summary = Read-K6Summary $runId
    $duration = $summary.metrics.http_req_duration.values
    $sanityDuration = $summary.metrics.sanity_req_duration.values
    $stats = Read-StatsSummary -Path $statsPath

    [pscustomobject]@{
        run_id = $runId
        target_path = $TargetPath
        users = $vu
        iterations = $vu
        http_reqs = Get-MetricValue $summary.metrics.http_reqs.values "count"
        http_req_failed_rate = Get-MetricValue $summary.metrics.http_req_failed.values "rate"
        http_p50_ms = Get-MetricValue $duration "p(50)"
        http_p95_ms = Get-MetricValue $duration "p(95)"
        http_p99_ms = Get-MetricValue $duration "p(99)"
        sanity_p50_ms = Get-MetricValue $sanityDuration "p(50)"
        sanity_p95_ms = Get-MetricValue $sanityDuration "p(95)"
        sanity_p99_ms = Get-MetricValue $sanityDuration "p(99)"
        sanity_requests = Get-MetricValue $summary.metrics.sanity_requests.values "count"
        sanity_unexpected_responses = Get-MetricValue $summary.metrics.sanity_unexpected_responses.values "count"
        k6_max_cpu_percent = $stats.k6_max_cpu_percent
        k6_max_mem_mib = $stats.k6_max_mem_mib
        api_max_cpu_percent = $stats.api_max_cpu_percent
        api_max_mem_mib = $stats.api_max_mem_mib
        prometheus_max_cpu_percent = $stats.prometheus_max_cpu_percent
        prometheus_max_mem_mib = $stats.prometheus_max_mem_mib
    } | Export-Csv -Path $CsvPath -NoTypeInformation -Append
}

Write-Host "v3.2 k6 generator sanity finished."
Write-Host "Raw files: $RawDir"
Write-Host "CSV: $CsvPath"
