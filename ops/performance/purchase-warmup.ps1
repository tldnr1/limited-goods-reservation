# Experiment-specific checks. Loaded by performance.ps1; never called by application code.
function Get-WarmupEvidence {
    param([string]$Directory)
    $summary = Get-Content "$Directory/warmup-summary.json" -Raw | ConvertFrom-Json
    if ($null -eq $summary.metrics.unexpected_errors.value -or $summary.metrics.unexpected_errors.value -ne 0 -or
        $null -eq $summary.metrics.dropped_iterations.count -or $summary.metrics.dropped_iterations.count -ne 0) {
        throw '워밍업 오류/요청 누락 지표가 없거나 0이 아닙니다.'
    }
    $outcomes = @(Get-Content "$Directory/warmup-raw.json" | ForEach-Object { $_ | ConvertFrom-Json } |
        Where-Object { $_.type -eq 'Point' -and $_.metric -eq 'purchase_outcomes' })
    $saleIds = @($outcomes.data.tags.sale_id | Sort-Object -Unique)
    if ($saleIds.Count -ne 1 -or $saleIds[0] -notmatch '^[0-9a-fA-F-]{36}$') {
        throw '워밍업 판매 ID를 하나로 식별할 수 없습니다.'
    }
    $saleId = [Guid]::Parse($saleIds[0]).ToString()
    $accepted = @($outcomes | Where-Object { $_.data.tags.outcome -eq 'held' }).Count
    if ($accepted -le 0 -or $outcomes.Count -ne $summary.metrics.iterations.count) {
        throw '워밍업 성공 구매가 없거나 원시 결과와 완료 iteration 수가 다릅니다.'
    }
    [pscustomobject]@{ saleId=$saleId; iterations=$outcomes.Count; accepted=$accepted;
        rejected=$outcomes.Count-$accepted }
}

function Get-WarmupPools {
    $result = @()
    foreach ($service in @('api1','api2','worker','mock-pg')) {
        $raw = (Invoke-Docker -Arguments ($compose + @('exec','-T',$service,'curl','--fail','--silent',
            '--max-time','5','http://127.0.0.1:8080/actuator/prometheus'))) -join "`n"
        $timeouts = [regex]::Matches($raw,'(?m)^hikaricp_connections_timeout_total(?:\{[^\r\n]*\})?\s+([0-9.eE+-]+)')
        $start = [regex]::Match($raw,'(?m)^process_start_time_seconds(?:\{[^\r\n]*\})?\s+([0-9.eE+-]+)')
        if ($timeouts.Count -ne 1 -or -not $start.Success) { throw "$service Hikari/프로세스 지표 확인 실패" }
        $result += [pscustomobject]@{ service=$service; started=$start.Groups[1].Value;
            timeouts=[double]::Parse($timeouts[0].Groups[1].Value,[Globalization.CultureInfo]::InvariantCulture) }
    }
    $result
}

function Wait-PurchaseWarmup {
    param([string]$Directory, $PoolsBefore)
    $evidence = Get-WarmupEvidence -Directory $Directory
    $evidence | ConvertTo-Json | Set-Content "$Directory/warmup-evidence.json" -Encoding utf8
    $drained = $false
    for ($i=0; $i -lt 15; $i++) {
        $raw = Get-Content "$PSScriptRoot/purchase-warmup.sql" |
            & docker @compose exec -T postgres psql -X -qAt -v ON_ERROR_STOP=1 -v "sale_id=$($evidence.saleId)" `
                -U goods -d limited_goods_perf
        if ($LASTEXITCODE -ne 0) { throw '워밍업 판매 상태 조회 실패' }
        $state = $raw | ConvertFrom-Json
        $state | ConvertTo-Json | Set-Content "$Directory/warmup-db.json" -Encoding utf8
        if ($state.inventoryViolations -ne 0 -or $state.userLimitViolations -ne 0) {
            throw '워밍업 재고/인당 한도 불변식 위반'
        }
        if ($state.orders -eq $evidence.accepted -and $state.confirmedOrders -eq $evidence.accepted -and
            $state.attempts -eq $evidence.accepted -and $state.succeededAttempts -eq $evidence.accepted -and
            $state.confirmedReservations -eq $evidence.accepted) { $drained=$true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $drained) { throw "워밍업 미확정/건수 불일치: 성공 구매=$($evidence.accepted), 상태=$($state | ConvertTo-Json -Compress)" }
    $after = @(Get-WarmupPools)
    $after | ConvertTo-Json | Set-Content "$Directory/warmup-pools-after.json" -Encoding utf8
    foreach ($pool in $after) {
        $before = @($PoolsBefore | Where-Object service -eq $pool.service)
        if ($before.Count -ne 1 -or $pool.started -ne $before[0].started -or $pool.timeouts -ne $before[0].timeouts) {
            throw "$($pool.service) 워밍업 중 프로세스 변경 또는 Hikari timeout 발생"
        }
    }
    Write-Output "워밍업 통과: 실제 성공 구매 $($evidence.accepted)건 모두 확정, 거절 $($evidence.rejected)건, 정합성/Hikari 정상. 초기 지연은 합격 기준에서 제외."
}
