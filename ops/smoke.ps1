#requires -Version 7.0
param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'check-health.ps1') -BaseUrl $BaseUrl | Out-Null
$saleInput = @{
    name = 'java-smoke'
    opensAt = [DateTime]::UtcNow.AddMinutes(-1).ToString('o')
    items = @(@{ name='goods'; price=10000; total=3; perUserLimit=1 })
}
$sale = Invoke-RestMethod "$BaseUrl/api/sales" -Method Post -ContentType 'application/json' -Body ($saleInput | ConvertTo-Json -Depth 5)
$results = @()
foreach ($scenario in @('SUCCESS','LOST_RESPONSE','DELAYED_SUCCESS')) {
    $userId = "smoke-$scenario-$($sale.id)"
    $headers = @{ 'X-User-Id'=$userId; 'Idempotency-Key'='purchase' }
    $body = @{ saleId=$sale.id; items=@(@{saleItemId=$sale.items[0].id; quantity=1}) }
    $order = Invoke-RestMethod "$BaseUrl/api/purchases" -Method Post -Headers $headers -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 5)
    $headers['Idempotency-Key']='payment'
    $attempt = Invoke-RestMethod "$BaseUrl/api/orders/$($order.id)/payments" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{scenario=$scenario} | ConvertTo-Json)
    $confirmed=$false
    for ($i=0; $i -lt 30; $i++) {
        $status = Invoke-RestMethod "$BaseUrl/api/orders/$($order.id)" -Headers $headers
        if ($status.status -eq 'CONFIRMED') { $confirmed=$true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $confirmed) { throw "$scenario 결제 확정 시간 초과: $($status | ConvertTo-Json -Depth 8 -Compress)" }
    $results += [pscustomobject]@{ scenario=$scenario; orderId=$order.id; attemptId=$attempt.id; status=$status.status }
}
$final = Invoke-RestMethod "$BaseUrl/api/sales/$($sale.id)"
if ($final.items[0].available -ne 0 -or $final.items[0].held -ne 0 -or $final.items[0].sold -ne 3) { throw '최종 재고 불변식 실패' }
try {
    $extraHeaders=@{ 'X-User-Id'="smoke-extra-$($sale.id)"; 'Idempotency-Key'='purchase' }
    Invoke-RestMethod "$BaseUrl/api/purchases" -Method Post -Headers $extraHeaders -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 5) | Out-Null
    throw '소진 후 신규 구매가 거절되지 않았습니다.'
} catch {
    if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409) { throw }
}
$replayHeaders=@{ 'X-User-Id'="smoke-SUCCESS-$($sale.id)"; 'Idempotency-Key'='purchase' }
$replay=Invoke-RestMethod "$BaseUrl/api/purchases" -Method Post -Headers $replayHeaders -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 5)
if ($replay.id -ne $results[0].orderId) { throw '소진 후 멱등 재조회 실패' }
[pscustomobject]@{ saleId=$sale.id; sold=$final.items[0].sold; results=$results } | ConvertTo-Json -Depth 8
