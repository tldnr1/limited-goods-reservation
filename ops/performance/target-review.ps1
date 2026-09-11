# Offline review only; never calls Docker, HTTP, k6, or changes the database.
param([Parameter(Mandatory)][string]$Directory)
$ErrorActionPreference='Stop'
$issues=[System.Collections.Generic.List[string]]::new()
$review=[System.Collections.Generic.List[string]]::new()
$capacity=$null
function Read-Json([string]$Name) { Get-Content (Join-Path $Directory $Name) -Raw | ConvertFrom-Json }
function Require([bool]$Condition,[string]$Message) { if (-not $Condition) { $issues.Add($Message) } }
try {
    foreach ($name in @('config.json','phases.json','k6-summary.json','k6-exit.txt','before-db.json','after-db.json','db-samples.jsonl','redis-samples.jsonl','resources.jsonl','prometheus.json','before-containers.json','after-containers.json','timeline.json')) {
        Require (Test-Path "$Directory/$name") "Missing evidence: $name"
    }
    foreach ($name in @('error.txt','collection-errors.txt','observer-error.txt')) { Require (-not (Test-Path "$Directory/$name")) "Run/collection error: $name" }
    if ($issues.Count -eq 0) {
        $config=Read-Json 'config.json'; $summary=Read-Json 'k6-summary.json'; $state=Read-Json 'after-db.json'; $phases=Read-Json 'phases.json'
        Require ((Get-Content "$Directory/k6-exit.txt") -eq '0') 'k6 exited nonzero'
        Require ($phases.status -eq 'collected') 'Measurement did not finish'
        foreach ($field in @('inventoryViolations','userLimitViolations','holdViolations','duplicateSuccess','waitingDbConnections')) {
            Require ($null -ne $state.$field -and $state.$field -eq 0) "$field must be zero"
        }
        $requiredThresholds=@{dropped_iterations='count==0';target_unexpected='rate<=0.001'}
        if ($config.scenario -in @('worker','isolation','business')) { $requiredThresholds.target_payment_ms='p(95)<=1000' }
        if ($config.scenario -in @('worker','isolation')) { $requiredThresholds.target_payment_rejected='rate==0' }
        if ($config.scenario -in @('reservation','business')) { $requiredThresholds.target_purchase_accepted_ms='p(99)<=1000' }
        foreach ($entry in $requiredThresholds.GetEnumerator()) {
            Require ($summary.metrics.($entry.Key).thresholds.($entry.Value).ok -eq $true) "Missing/failed threshold: $($entry.Key): $($entry.Value)"
        }
        foreach ($metric in $summary.metrics.PSObject.Properties) {
            if ($metric.Value.thresholds) {
                foreach ($threshold in $metric.Value.thresholds.PSObject.Properties) { Require ($threshold.Value.ok -eq $true) "$($metric.Name): $($threshold.Name)" }
            }
        }
        $started=$summary.metrics.target_started.values.count
        $finished=$summary.metrics.target_finished.values.count
        Require ($null -ne $started -and $started -gt 0 -and $started -eq $finished) 'Missing/interrupted browser/payment iterations'
        $expected=if ($config.scenario -eq 'business') { $config.users+$(if ($config.variant -eq 'abandon') {$config.stock} else {0}) }
            elseif ($config.scenario -eq 'isolation') { ($config.rps+$config.paymentRps)*$config.durationSeconds }
            else { $config.rps*$config.durationSeconds }
        $tolerance=if ($config.scenario -eq 'business') {0} elseif ($config.scenario -eq 'isolation') {2} else {1}
        Require ($started -ge $expected -and $started -le $expected+$tolerance) 'Actual arrivals differ from configured workload'
        $held=$summary.metrics.target_held.values.count
        $accepted=$summary.metrics.target_payment_accepted.values.count
        if ($config.scenario -in @('worker','isolation')) {
            Require ($accepted -gt 0 -and $state.confirmed -eq $accepted -and $state.succeeded -eq $accepted -and $state.pending -eq 0 -and $state.failed -eq 0) 'Accepted payments not fully confirmed after drain'
        }
        if ($config.scenario -in @('reservation','business')) { Require ($held -gt 0 -and $state.orders -eq $held) 'No purchases or HTTP/DB order count mismatch' }
        if ($config.scenario -eq 'waiting') { Require ($state.orders -eq 0 -and $state.attempts -eq 0) 'Waiting created durable orders/payments' }
        $samples=@(Get-Content "$Directory/db-samples.jsonl" | ForEach-Object { $_ | ConvertFrom-Json })
        Require ($samples.Count -ge 2) 'Insufficient DB samples'
        foreach ($sample in $samples) {
            foreach ($field in @('inventoryViolations','userLimitViolations','holdViolations','duplicateSuccess','waitingDbConnections')) { Require ($sample.$field -eq 0) "Observed $field at $($sample.at)" }
        }
        $window=@($samples | Where-Object {
            $t=([DateTimeOffset]$_.at).ToUnixTimeMilliseconds()/1000
            $t -ge $phases.measurementStart -and $t -le $phases.arrivalEnd
        })
        if ($window.Count -ge 2) {
            $first=$window[0]; $last=$window[-1]
            $seconds=([DateTimeOffset]$last.at-[DateTimeOffset]$first.at).TotalSeconds
            $capacity=@{sampleSeconds=$seconds;confirmedPerSecond=($last.confirmed-$first.confirmed)/$seconds;backlogSlope=($last.pending-$first.pending)/$seconds;maxPending=($window.pending | Measure-Object -Maximum).Maximum;maxOldestSeconds=($window.oldestPendingSeconds | Measure-Object -Maximum).Maximum}
        } else { $review.Add('Supply window has fewer than two samples; no capacity estimate.') }
        if ($config.scenario -eq 'business' -and $config.variant -eq 'normal') {
            $by120=@($samples | Where-Object { ([DateTimeOffset]$_.at).ToUnixTimeMilliseconds()/1000 -le $phases.measurementStart+120 })
            $confirmedBy120=($by120.confirmed | Measure-Object -Maximum).Maximum
            Require ($confirmedBy120 -ge [Math]::Ceiling($config.stock*0.95)) '95% stock not observed CONFIRMED by 120 seconds'
            $timeline=Read-Json 'timeline.json'
            $heldBy60=@($timeline.orders | Where-Object { ([DateTimeOffset]$_.created_at).ToUnixTimeMilliseconds()/1000 -le $phases.measurementStart+60 }).Count
            Require ($heldBy60 -ge $config.stock) 'Initial stock not fully held by 60 seconds'
            if ($config.users -ne 50000 -or $config.stock -ne 1000) { $review.Add('Scaled run: cannot claim the 50,000-user / 1,000-stock target.') }
        }
        $before=@(Read-Json 'before-containers.json'); $after=@(Read-Json 'after-containers.json')
        foreach ($container in $before) {
            $current=@($after | Where-Object Id -eq $container.Id)
            Require ($current.Count -eq 1 -and $current[0].RestartCount -eq $container.RestartCount -and -not $current[0].State.OOMKilled -and $current[0].State.Running) "Container changed/restarted/stopped/OOM: $($container.Name)"
        }
        $prom=Read-Json 'prometheus.json'
        Require ($prom.status -eq 'success') 'Prometheus query failed'
        $up=@($prom.data.result | Where-Object { $_.metric.__name__ -eq 'up' })
        Require ($up.Count -eq 6) 'Missing Prometheus targets'
        $timeouts=@($prom.data.result | Where-Object { $_.metric.__name__ -eq 'hikaricp_connections_timeout_total' })
        $starts=@($prom.data.result | Where-Object { $_.metric.__name__ -eq 'process_start_time_seconds' })
        Require ($timeouts.Count -eq 4 -and $starts.Count -eq 6) 'Missing Hikari/process evidence'
        foreach ($series in $prom.data.result) {
            if ($series.metric.__name__ -eq 'up') { Require (@($series.values | Where-Object { $_[1] -ne '1' }).Count -eq 0) "Scrape failure: $($series.metric.instance)" }
            if ($series.metric.__name__ -in @('hikaricp_connections_timeout_total','process_start_time_seconds')) {
                Require ($series.values[0][1] -eq $series.values[-1][1]) "Hikari timeout or process restart: $($series.metric.instance)"
            }
        }
    }
} catch { $issues.Add("Review incomplete: $_") }
$review.Add('Inspect per-endpoint/status p95/p99, 429/503, READY versus actual purchase rate, Redis latency/CPU, DB lock/Hikari and generator resources.')
$review.Add('Sustained capacity needs stable backlog/age within the supply window and 3 independent trials; drain alone is insufficient. Worker 32/s required, 40/s normal target.')
$review.Add('For return/late/PG scenarios inspect timeline and state samples. Sampled projection timing is not an exact release-event timestamp; do not certify <=5s from coarse samples.')
$result=@{status=$(if ($issues.Count) {'failed'} else {'requires_review'});automatedChecksPassed=($issues.Count -eq 0);issues=@($issues);capacity=$capacity;manualReview=@($review)}
$result | ConvertTo-Json -Depth 10 | Set-Content "$Directory/result.json"
$result | ConvertTo-Json -Depth 10
