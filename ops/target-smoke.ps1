#requires -Version 7.0
param([string]$WaitingUrl='http://127.0.0.1:8080',[string]$CheckoutUrl='http://127.0.0.1:8082',[switch]$RedisOutage)
$ErrorActionPreference='Stop'
$compose=@('compose','-f','compose.yaml','-f','compose.target.yml')
Push-Location (Split-Path $PSScriptRoot -Parent)
function Request([string]$Url,[string]$Method='Get',$Headers=@{},$Body=$null) {
    $requestArgs=@{Uri=$Url;Method=$Method;Headers=$Headers;TimeoutSec=8}
    if ($null -ne $Body) { $requestArgs.ContentType='application/json'; $requestArgs.Body=$Body | ConvertTo-Json -Depth 8 }
    try { Invoke-RestMethod @requestArgs }
    catch { Write-Error "HTTP failure: $Method $Url : $($_.Exception.Message)" -ErrorAction Continue; throw }
}
function Ready([string]$User,$Body) {
    $headers=@{'X-User-Id'=$User;'Idempotency-Key'='purchase'}
    $joined=$null
    for ($i=0;$i -lt 10;$i++) {
        try { $joined=Request "$WaitingUrl/api/admissions" Post $headers $Body; break }
        catch { if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 503) { throw }; Start-Sleep -Milliseconds 500 }
    }
    if (-not $joined) { throw '판매 상태 projection 준비 시간 초과' }
    for ($i=0;$i -lt 20;$i++) {
        $state=Request "$WaitingUrl/api/admissions/$($joined.id)" Get $headers
        if ($state.state -eq 'READY') { $headers['X-Admission-Ticket']=$state.ticket; return [pscustomobject]@{headers=$headers;state=$state} }
        if ($state.state -in @('EXPIRED','SOLD_OUT')) { throw "예상하지 않은 대기 종료: $($state.state)" }
        Start-Sleep -Milliseconds 1100
    }
    throw 'READY 시간 초과'
}
function Confirm($Order,$Headers,[string]$Scenario) {
    $paymentHeaders=$Headers.Clone(); $paymentHeaders['Idempotency-Key']='payment'
    $attempt=Request "$CheckoutUrl/api/orders/$($Order.id)/payments" Post $paymentHeaders @{scenario=$Scenario}
    for ($i=0;$i -lt 30;$i++) {
        $status=Request "$CheckoutUrl/api/orders/$($Order.id)" Get $Headers
        if ($status.status -eq 'CONFIRMED') { return [pscustomobject]@{scenario=$Scenario;order=$Order.id;attempt=$attempt.id;status=$status.status} }
        Start-Sleep -Milliseconds 500
    }
    throw "결제 확정 시간 초과: $($Order.id)"
}
try {
    & (Join-Path $PSScriptRoot 'target.ps1') -Action Check | Out-Null
    $stock=if ($RedisOutage) { 4 } else { 3 }
    $sale=Request "$CheckoutUrl/api/sales" Post @{} @{
        name='target-functional';opensAt=[DateTime]::UtcNow.AddMinutes(-1).ToString('o')
        items=@(@{name='goods';price=10000;total=$stock;perUserLimit=1})
    }
    $body=@{saleId=$sale.id;items=@(@{saleItemId=$sale.items[0].id;quantity=1})}
    $gone=Ready "gone-$($sale.id)" $body
    # A real READY expiry, without submitting any reservation request.
    Start-Sleep -Seconds 11
    $expired=Request "$WaitingUrl/api/admissions/$($gone.state.id)" Get $gone.headers
    if ($expired.state -ne 'EXPIRED') { throw '미사용 READY 만료 실패' }
    $unchanged=Request "$CheckoutUrl/api/sales/$($sale.id)"
    if ($unchanged.items[0].held -ne 0) { throw '이탈 사용자에게 ghost reservation 생성됨' }
    $results=@()
    foreach ($scenario in @('SUCCESS','LOST_RESPONSE','DELAYED_SUCCESS')) {
        $ready=Ready "target-$scenario-$($sale.id)" $body
        $order=Request "$CheckoutUrl/api/purchases" Post $ready.headers $body
        $remaining=([DateTimeOffset]$order.holdExpiresAt-[DateTimeOffset]::UtcNow).TotalSeconds
        if ($remaining -lt 285 -or $remaining -gt 301) { throw "300초 점유시간 불일치: $remaining" }
        $replay=Request "$CheckoutUrl/api/purchases" Post $ready.headers $body
        if ($replay.id -ne $order.id) { throw '중복 점유 발생' }
        $results+=Confirm $order $ready.headers $scenario
    }
    if ($RedisOutage) {
        $ready=Ready "target-outage-$($sale.id)" $body
        $order=Request "$CheckoutUrl/api/purchases" Post $ready.headers $body
        try {
            & docker @compose stop redis | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Redis 중단 실패' }
            $replay=Request "$CheckoutUrl/api/purchases" Post $ready.headers $body
            if ($replay.id -ne $order.id) { throw 'Redis 장애 중 DB 주문 복구 실패' }
            $results+=Confirm $order $ready.headers 'DELAYED_SUCCESS'
        } finally {
            & docker @compose start redis | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Redis 복구 실패' }
        }
    }
    $final=Request "$CheckoutUrl/api/sales/$($sale.id)"
    if ($final.items[0].sold -ne $stock -or $final.items[0].held -ne 0 -or $final.items[0].available -ne 0) { throw '최종 재고 불일치' }
    [pscustomobject]@{saleId=$sale.id;ghostHold=0;holdSeconds=300;redisOutage=[bool]$RedisOutage;sold=$stock;results=$results} | ConvertTo-Json -Depth 8
} finally { Pop-Location }
