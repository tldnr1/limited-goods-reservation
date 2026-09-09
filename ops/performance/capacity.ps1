# Post-run verification only. HTTP thresholds live in k6/capacity.js.
function Save-CapacityResult {
    param([string]$Directory, $PoolsBefore)
    $evidence = Get-PurchaseEvidence -Directory $Directory -Phase measured
    $state = Get-PurchaseState -SaleId $evidence.saleId
    $stateCheckedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $after = @(Get-PurchasePools)
    $after | ConvertTo-Json | Set-Content "$Directory/measured-pools-after.json" -Encoding utf8
    $issues = @()
    if ($evidence.accepted -le 0 -or $evidence.rejected -ne 0 -or $evidence.outcomes -ne $evidence.iterations) {
        $issues += '성공 구매 없음/거절 발생/iteration 결과 불일치'
    }
    foreach ($field in @('orders','confirmedOrders','attempts','succeededAttempts','confirmedReservations')) {
        if ($state.$field -ne $evidence.accepted) { $issues += "$field != accepted" }
    }
    if ($state.inventoryViolations -ne 0 -or $state.userLimitViolations -ne 0) { $issues += '정합성 위반' }
    foreach ($pool in $after) {
        $before = @($PoolsBefore | Where-Object service -eq $pool.service)
        if ($before.Count -ne 1 -or $pool.started -ne $before[0].started -or $pool.timeouts -ne $before[0].timeouts) {
            $issues += "$($pool.service) 프로세스 변경/Hikari timeout"
        }
    }
    $samples = @(Get-Content "$Directory/order-progress.txt" | Where-Object { $_ -match '^\{' } |
        ForEach-Object { $_ | ConvertFrom-Json })
    $progress = @($samples | ForEach-Object { $_.sales } | Where-Object saleId -eq $evidence.saleId)
    if ($progress.Count -eq 0) { $issues += '본 측정 판매의 Worker 진행 표본 없음' }
    [ordered]@{
        evidence=$evidence; finalState=$state; stateCheckedAt=$stateCheckedAt; issues=$issues; finalStatePassed=($issues.Count -eq 0);
        maxPendingPayments=($progress.pendingPayments | Measure-Object -Maximum).Maximum;
        maxOldestPendingPaymentSeconds=($progress.oldestPendingPaymentSeconds | Measure-Object -Maximum).Maximum;
        sustainability='requires_review: order-progress.txt의 부하 중 backlog/age 추세와 확정 처리율을 확인. 최종 drain 성공만으로 지속 용량 통과 아님.'
    } | ConvertTo-Json -Depth 6 | Set-Content "$Directory/capacity-result.json" -Encoding utf8
    if ($issues.Count -gt 0) { throw "Capacity 최종 판정 실패: $($issues -join '; ')" }
}
