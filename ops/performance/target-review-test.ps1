# Offline evidence tests. No database, HTTP, Docker, or k6 calls.
$ErrorActionPreference='Stop'
$directory=Join-Path ([IO.Path]::GetTempPath()) "target-review-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory $directory | Out-Null
function Json($value,$name) { $value | ConvertTo-Json -Depth 20 | Set-Content "$directory/$name" }
function Verify($expected) {
    & "$PSScriptRoot/target-review.ps1" -Directory $directory | Out-Null
    $result=Get-Content "$directory/result.json" -Raw | ConvertFrom-Json
    if ($result.status -ne $expected) { throw "Expected $expected, got $($result | ConvertTo-Json -Depth 6)" }
}
try {
    Json @{scenario='worker';variant='normal';rps=40;durationSeconds=60} 'config.json'
    Json @{status='collected';measurementStart=1800000000;arrivalEnd=1800000060} 'phases.json'
    $state=@{at='2027-01-15T08:00:01Z';inventoryViolations=0;userLimitViolations=0;holdViolations=0;duplicateSuccess=0;waitingDbConnections=0;confirmed=2400;succeeded=2400;pending=0;failed=0;oldestPendingSeconds=0}
    Json $state 'after-db.json'; Json $state 'before-db.json'
    $state | ConvertTo-Json -Compress | Set-Content "$directory/db-samples.jsonl"
    $state.at='2027-01-15T08:00:50Z'
    $state | ConvertTo-Json -Compress | Add-Content "$directory/db-samples.jsonl"
    $metrics=@{}
    foreach ($name in @('target_started','target_finished','target_payment_accepted')) { $metrics[$name]=@{values=@{count=2400}} }
    $metrics.target_payment_ms=@{thresholds=@{'p(95)<=1000'=@{ok=$true}}}
    Json @{metrics=$metrics} 'k6-summary.json'
    Set-Content "$directory/k6-exit.txt" '0'
    foreach ($name in @('redis-samples.jsonl','resources.jsonl','timeline.json')) { Set-Content "$directory/$name" '{}' }
    $container=@{Id='app';Name='worker';RestartCount=0;State=@{Running=$true;OOMKilled=$false}}
    Json @($container) 'before-containers.json'; Json @($container) 'after-containers.json'
    $series=@(1..6 | ForEach-Object { @{metric=@{__name__='up';instance="app$_"};values=@(@(1800000000,'1'),@(1800000060,'1'))} })
    $series+=@(1..6 | ForEach-Object { @{metric=@{__name__='process_start_time_seconds';instance="app$_"};values=@(@(1800000000,'1700000000'),@(1800000060,'1700000000'))} })
    $series+=@(1..4 | ForEach-Object { @{metric=@{__name__='hikaricp_connections_timeout_total';instance="pool$_"};values=@(@(1800000000,'0'),@(1800000060,'0'))} })
    Json @{status='success';data=@{result=$series}} 'prometheus.json'
    Verify 'requires_review' # Even complete evidence must not auto-declare capacity success.
    $state.pending=1
    Json $state 'after-db.json'
    Verify 'failed'
    $state.pending=0; Json $state 'after-db.json'
    $metrics.target_payment_ms.thresholds.'p(95)<=1000'.ok=$false
    Json @{metrics=$metrics} 'k6-summary.json'
    Verify 'failed'
    $metrics.target_payment_ms.thresholds.'p(95)<=1000'.ok=$true
    Json @{metrics=$metrics} 'k6-summary.json'
    $series[-1].values[-1][1]='1'
    Json @{status='success';data=@{result=$series}} 'prometheus.json'
    Verify 'failed'
    Remove-Item -LiteralPath "$directory/prometheus.json"
    Verify 'failed'
    '5 offline result-review checks passed.'
} finally {
    # Delete only this test's newly created, resolved temporary directory.
    $resolved=[IO.Path]::GetFullPath($directory)
    $tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolved -Leaf) -match '^target-review-[a-f0-9]{32}$') {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
