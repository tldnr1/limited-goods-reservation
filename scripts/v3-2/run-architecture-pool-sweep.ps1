[CmdletBinding()]
param(
    [int] $ProductId = 1,
    [int] $InitialStock = 100,
    [string] $Users = "3000",
    [string] $Architectures = "rdb-atomic",
    [string] $Scenarios = "normal,sold-out",
    [string] $PoolSizes = "5,10,20,40",
    [int] $DuplicateRequests = 2,
    [int] $FailureLimit = 10,
    [int] $HikariConnectionTimeoutMs = 30000,
    [string] $ResultName = "v3-2-pool-sweep",
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $Root

$CsvPath = Join-Path $Root "records\experiments\$ResultName.csv"
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

if (-not $SkipBuild) {
    Invoke-Compose -Arguments @("build", "api")
}

$matrixScript = Join-Path $PSScriptRoot "run-architecture-vu-matrix.ps1"
$poolValues = $PoolSizes.Split(",") | ForEach-Object { [int] $_.Trim() }

foreach ($poolSize in $poolValues) {
    Write-Host "Starting pool sweep with Hikari max pool size $poolSize"

    & $matrixScript `
        -ProductId $ProductId `
        -InitialStock $InitialStock `
        -Users $Users `
        -Architectures $Architectures `
        -Scenarios $Scenarios `
        -DuplicateRequests $DuplicateRequests `
        -FailureLimit $FailureLimit `
        -HikariMaxPoolSize $poolSize `
        -HikariConnectionTimeoutMs $HikariConnectionTimeoutMs `
        -ResultName $ResultName `
        -SkipBuild `
        -AppendResult

    if ($LASTEXITCODE -ne 0) {
        throw "Pool sweep failed at Hikari max pool size $poolSize"
    }
}

Write-Host "v3.2 architecture pool sweep finished."
Write-Host "CSV: $CsvPath"
