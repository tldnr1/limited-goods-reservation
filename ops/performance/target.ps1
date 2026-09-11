#requires -Version 7.0
param(
    [ValidateSet('Check','Prepare','Run')][string]$Action='Check',
    [ValidateSet('worker','waiting','reservation','isolation','business')][string]$Scenario='worker',
    [ValidateSet('normal','burst','late-payment','abandon','retry','pg-failure')][string]$Variant='normal',
    [int]$Rps=0,[int]$DurationSeconds=60,[int]$Stock=1000,[int]$Users=50000,[int]$PaymentRps=40,
    [int]$Vus=100,[int]$MaxVus=2000,[int]$WaitingRate=25,[int]$ReservationRate=25,[int]$Permits=8,
    [int]$Seed=20260911,[switch]$Diagnostics,[switch]$Reset
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$compose=@('compose','--env-file','ops/perf.env','-f','compose.yaml','-f','compose.target.yml','-f','ops/performance/target-compose.yml','--profile','observe')
$services=@('api1','api2','reservation','payment','worker','mock-pg')
$apps=@('nginx','checkout-nginx')+$services
$saved=@{}
$directory=$null
$observer=$null
$loadStarted=$null
$loadExit=$null
$ids=@{}
$executionLock=$null
function Invoke-Docker([string[]]$Arguments) {
    # Keep progress attached to the terminal. Only query callers capture stdout.
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker failed (exit=$LASTEXITCODE): $($Arguments -join ' ')" }
}
function Sql([string]$Statement) {
    $output=$Statement | & docker @compose exec -T postgres psql -X -qAt -v ON_ERROR_STOP=1 -U goods -d limited_goods_perf
    if ($LASTEXITCODE -ne 0) { throw 'perf SQL failed' }
    return $output
}
function Assert-NoOtherWork {
    foreach ($name in @('goods-k6','goods-target-k6')) {
        if (Invoke-Docker @('ps','--filter',"name=^/$name$",'-q')) { throw "$name 실행 중. 먼저 종료하세요." }
    }
    foreach ($service in $services) {
        $id=Invoke-Docker ($compose+@('ps','-q',$service))
        if ($id) {
            $info=@((Invoke-Docker @('inspect',$id) | ConvertFrom-Json))[0]
            if ($info.Config.Env -notcontains 'DB_URL=jdbc:postgresql://postgres:5432/limited_goods_perf') {
                throw "$service dev/baseline 실행을 먼저 명시적으로 종료하세요. 자동 중단하지 않습니다."
            }
        }
    }
    $postgres=Invoke-Docker ($compose+@('ps','-q','postgres'))
    if ($postgres) {
        $active=Invoke-Docker @('exec',$postgres,'psql','-X','-At','-U','goods','-d','postgres','-c',
            "SELECT count(*) FROM pg_stat_activity WHERE datname IN ('limited_goods_dev','limited_goods_test') AND backend_type='client backend' AND application_name<>'psql'")
        if ([int]$active -gt 0) { throw 'dev/test DB 연결이 있습니다. perf와 동시 실행하지 마세요.' }
    }
}
function Assert-Ready([switch]$WaitForScrape) {
    $profiles=@{api1='waiting';api2='waiting';reservation='reservation';payment='payment';worker='worker';'mock-pg'='mockpg'}
    foreach ($service in ($apps+@('postgres','redis','prometheus'))) {
        $id=Invoke-Docker ($compose+@('ps','-q',$service))
        if (-not $id) { throw "$service 준비 안 됨. Prepare를 먼저 실행하세요." }
        $info=@((Invoke-Docker @('inspect',$id) | ConvertFrom-Json))[0]
        if (-not $info.State.Running -or ($info.State.Health -and $info.State.Health.Status -ne 'healthy')) { throw "$service unhealthy" }
        $ids[$service]=$id
        if ($profiles.ContainsKey($service)) {
            foreach ($setting in @("SPRING_PROFILES_ACTIVE=$($profiles[$service])",'DB_URL=jdbc:postgresql://postgres:5432/limited_goods_perf','REDIS_NAMESPACE=goods:perf:')) {
                if ($info.Config.Env -notcontains $setting) { throw "$service 설정 불일치: $setting" }
            }
        }
        $expected=@()
        if ($service -in @('api1','api2')) { $expected=@("APP_WAITING_RATE=$WaitingRate") }
        if ($service -eq 'reservation') { $expected=@("RESERVATION_RATE=$ReservationRate","ADMISSION_PERMITS=$Permits") }
        foreach ($setting in $expected) { if ($info.Config.Env -notcontains $setting) { throw "Prepare 설정과 불일치: $setting" } }
    }
    $query=[Uri]::EscapeDataString('up{job="target"}')
    for ($attempt=0;$attempt -lt $(if ($WaitForScrape) {10} else {1});$attempt++) {
        $up=Invoke-RestMethod "http://127.0.0.1:9090/api/v1/query?query=$query" -TimeoutSec 5
        if ($up.status -eq 'success' -and @($up.data.result).Count -eq 6 -and
            @($up.data.result | Where-Object { $_.value[1] -ne '1' }).Count -eq 0) { return }
        if ($WaitForScrape -and $attempt -lt 9) { Start-Sleep -Seconds 1 }
    }
    throw 'Target Prometheus 6개 수집 대상 준비 안 됨'
}
function Save-Json($Value,[string]$Path) { $Value | ConvertTo-Json -Depth 30 | Set-Content $Path -Encoding utf8 }

Push-Location $root
try {
    if ($Diagnostics) { throw 'Target은 공통 관측을 항상 수집합니다. baseline -Diagnostics를 혼용하지 마세요.' }
    if ($Reset -and $Action -ne 'Run') { throw '-Reset은 -Action Run 전용입니다.' }
    if ($WaitingRate -lt 1 -or $ReservationRate -lt 1 -or $Permits -lt 1) { throw 'Rate/permit은 양수여야 합니다.' }
    if ($Action -eq 'Run') {
        if ($DurationSeconds -lt 1 -or $DurationSeconds -gt 180 -or $Vus -lt 1 -or $MaxVus -lt $Vus -or $PaymentRps -lt 1 -or
            $Stock -lt 1 -or $Stock -gt 1000000 -or $Users -lt 10 -or $Users -gt 50000 -or $Users % 10 -ne 0 -or
            ($Scenario -ne 'business' -and ($Rps -lt 1 -or $Rps -gt 100000))) { throw '잘못된 부하 설정. 실행 가이드의 범위를 확인하세요.' }
        if ($Scenario -ne 'business' -and $Variant -ne 'normal' -and -not ($Scenario -in @('waiting','isolation') -and $Variant -eq 'abandon')) {
            throw '이 variant는 Business 전용입니다. Waiting/Isolation은 normal 또는 abandon만 지원합니다.'
        }
        if ($Scenario -eq 'reservation' -and $Stock -lt $Rps*$DurationSeconds) { throw 'Reservation은 전체 유입 이상 재고를 지정하세요.' }
    }
    foreach ($entry in @{DB_NAME='limited_goods_perf';APP_ENV='perf';ADMISSION_ENABLED='true';TARGET_WAITING_RATE="$WaitingRate";TARGET_RESERVATION_RATE="$ReservationRate";TARGET_PERMITS="$Permits"}.GetEnumerator()) {
        $saved[$entry.Key]=[Environment]::GetEnvironmentVariable($entry.Key,'Process')
        [Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')
    }
    # Hold through drain and collection too: k6 may have exited while evidence is still being written.
    if ($Action -ne 'Check') {
        $artifactRoot=Join-Path $root 'artifacts/performance'
        New-Item -ItemType Directory -Force $artifactRoot | Out-Null
        try { $executionLock=[IO.File]::Open("$artifactRoot/.target.lock",'OpenOrCreate','ReadWrite','None') }
        catch { throw '다른 Target Prepare/Run 또는 결과 수집이 진행 중입니다.' }
    }
    Assert-NoOtherWork
    Invoke-Docker ($compose+@('config','--quiet'))
    if ($Action -eq 'Prepare') {
        & ./gradlew.bat --no-daemon bootJar
        if ($LASTEXITCODE -ne 0) { throw 'JAR build failed' }
        Invoke-Docker ($compose+@('build','api1'))
        Invoke-Docker ($compose+@('stop')+$apps)
        # Mock PG runs Flyway before Reservation; existing service_healthy dependencies gate the rest.
        Invoke-Docker ($compose+@('up','-d','--no-build','--wait','--wait-timeout','300'))
        Assert-Ready -WaitForScrape
        Write-Output 'Target perf 배포/준비 확인 완료. 데이터를 초기화하거나 부하를 실행하지 않았습니다. 반복 실험은 Run -Reset으로 실행하세요.'
        return
    }
    Assert-Ready
    if ($Action -eq 'Check') { Write-Output 'Target perf 역할/DB/namespace/rate/관측 준비 확인 완료 (읽기 전용).'; return }
    Invoke-Docker @('image','inspect','grafana/k6:0.54.0','--format','{{.Id}}') | Out-Null
    if (-not $Reset -and [int](Sql 'SELECT count(*) FROM sales') -ne 0) { throw '이전 fixture가 있습니다. Run -Reset으로 별도 실험을 시작하세요.' }
    $runId=[Guid]::NewGuid().ToString('N')
    $directory=Join-Path $root "artifacts/performance/$(Get-Date -Format yyyyMMdd-HHmmss-fff)-target-$Scenario-$Variant"
    New-Item -ItemType Directory $directory | Out-Null
    $config=[ordered]@{runId=$runId;scenario=$Scenario;variant=$Variant;rps=$Rps;durationSeconds=$DurationSeconds;stock=$Stock;users=$Users;paymentRps=$PaymentRps;vus=$Vus;maxVus=$MaxVus;waitingRate=$WaitingRate;reservationRate=$ReservationRate;permits=$Permits;seed=$Seed;reset=[bool]$Reset}
    Save-Json $config "$directory/config.json"
    Invoke-Docker ($compose+@('config')) | Set-Content "$directory/compose.yaml"
    git rev-parse HEAD | Set-Content "$directory/commit.txt"
    git diff HEAD | Set-Content "$directory/working-tree.patch"
    git status --short | Set-Content "$directory/git-status.txt"
    Copy-Item k6/target -Destination "$directory/scripts" -Recurse
    Copy-Item ops/performance/target*.ps1,ops/performance/target*.sql -Destination $directory
    Get-FileHash build/libs/limited-goods.jar | Select-Object Algorithm,Hash | ConvertTo-Json | Set-Content "$directory/jar-hash.json"
    if ($Reset) {
        Invoke-Docker @('image','inspect','limited-goods-java:local','--format','{{.Id}}') | Out-Null
        Invoke-Docker ($compose+@('stop')+$apps)
        # Preserve the previous durable state before deleting it. A failed capture aborts reset.
        Sql (Get-Content "$PSScriptRoot/target-state.sql" -Raw) | Set-Content "$directory/pre-reset-db.json"
        Sql (Get-Content "$PSScriptRoot/target-timeline.sql" -Raw) | Set-Content "$directory/pre-reset-timeline.json"
        & ./ops/reset-db.ps1 -Environment perf -AppsStopped
        if (-not $?) { throw 'perf reset failed' }
        Invoke-Docker ($compose+@('up','-d','--no-build','--pull','never','--wait','--wait-timeout','300'))
        Assert-Ready -WaitForScrape
    }
    Save-Json (Invoke-Docker (@('inspect')+@($ids.Values)) | ConvertFrom-Json) "$directory/before-containers.json"
    # Fixture sales and held orders are setup, excluded from the measured arrival window.
    $saleBody=@{name="target-$runId";opensAt=[DateTimeOffset]::UtcNow.AddSeconds(-10).ToString('o');items=@(@{name='goods';price=10000;total=$Stock;perUserLimit=1})}
    $sale=Invoke-RestMethod 'http://127.0.0.1:8082/api/sales' -Method Post -ContentType 'application/json' -Body ($saleBody | ConvertTo-Json -Depth 5) -TimeoutSec 5
    $orders=@()
    if ($Scenario -in @('worker','isolation')) {
        $count=($DurationSeconds * $(if ($Scenario -eq 'worker') { $Rps } else { $PaymentRps }))+1
        if ($count -gt 1000000) { throw 'Payment fixture exceeds maximum stock' }
        $saleBody.name="payment-$runId"; $saleBody.items[0].total=$count
        $paymentSale=Invoke-RestMethod 'http://127.0.0.1:8082/api/sales' -Method Post -ContentType 'application/json' -Body ($saleBody | ConvertTo-Json -Depth 5) -TimeoutSec 5
        $saleId=[Guid]$paymentSale.id; $itemId=[Guid]$paymentSale.items[0].id
        # State-consistent setup only. All measured payment acceptance still goes through Payment API.
        $seedSql=@"
BEGIN;
CREATE TEMP TABLE fixture_orders AS SELECT gen_random_uuid() id,n FROM generate_series(0,$($count-1)) n;
INSERT INTO orders SELECT id,'fixture-'||n,'$saleId','purchase','$saleId' || ':' || '$itemId' || '=1','PAYMENT_PENDING',10000,now() FROM fixture_orders;
INSERT INTO order_items SELECT gen_random_uuid(),id,'$itemId',1,10000 FROM fixture_orders;
INSERT INTO reservations SELECT id,'ACTIVE',now()+interval '300 seconds',NULL FROM fixture_orders;
UPDATE sale_items SET available=0,held=total WHERE id='$itemId';
SELECT json_agg(json_build_object('id',id,'user','fixture-'||n) ORDER BY n) FROM fixture_orders;
COMMIT;
"@
        $orders=@(Sql $seedSql | ConvertFrom-Json)
    }
    Save-Json @{sale=$sale} "$directory/fixture.json"
    ConvertTo-Json -InputObject @($orders) -Depth 5 | Set-Content "$directory/orders.json" -Encoding utf8
    # Bounded readiness check, not a workload: projection must exist before measurement.
    $projected=$false
    for ($i=0;$i -lt 10;$i++) {
        $value=Invoke-Docker @('exec',$ids.redis,'redis-cli','GET',"goods:perf:catalog:$($sale.id)")
        if ($value -match ':AVAILABLE$') { $projected=$true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $projected) { throw 'Sale projection not ready' }
    Sql (Get-Content "$PSScriptRoot/target-state.sql" -Raw) | Set-Content "$directory/before-db.json"
    $observationStart=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $observer=Start-Job -FilePath "$PSScriptRoot/target-observe.ps1" -ArgumentList $directory,$ids.postgres,$ids.redis,@($ids.Values),$(if ($Scenario -eq 'business') { 1 } else { 5 })
    for ($i=0;$i -lt 20 -and -not (Test-Path "$directory/observer-ready");$i++) {
        if ($observer.State -eq 'Failed' -or (Test-Path "$directory/observer-error.txt")) { throw 'Observer startup failed' }
        Start-Sleep -Seconds 1
    }
    if (-not (Test-Path "$directory/observer-ready")) { throw 'Observer readiness timed out' }
    $network=@((Invoke-Docker @('inspect',$ids.nginx) | ConvertFrom-Json))[0].NetworkSettings.Networks.PSObject.Properties.Name | Select-Object -First 1
    $loadStarted=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    Save-Json @{observationStart=$observationStart;dockerStart=$loadStarted;status='running'} "$directory/phases.json"
    $arguments=@('run','--rm','--name','goods-target-k6','--network',$network,'--cpus','1.5','--memory','1g',
        '--mount',"type=bind,source=$directory/scripts,target=/scripts,readonly",
        '--mount',"type=bind,source=$directory,target=/results",
        '-e','TARGET_CONFIG=/results/config.json','-e','TARGET_FIXTURE=/results/fixture.json','-e','TARGET_ORDERS=/results/orders.json',
        'grafana/k6:0.54.0','run','--out','json=/results/raw.json',"/scripts/$Scenario.js")
    Write-Output "실제 부하: $Scenario / $Variant. 자동 상승/재실행 없음. 결과: $directory"
    & docker @arguments 2>&1 | Tee-Object "$directory/k6.log"
    $loadExit=$LASTEXITCODE
    Set-Content "$directory/k6-exit.txt" $loadExit
    $loadFinished=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    # Success or threshold failure: keep an equal, bounded drain observation window.
    Start-Sleep -Seconds 30
    $match=Select-String -Path "$directory/k6.log" -Pattern 'TARGET_MEASUREMENT_START=(\d+)' | Select-Object -First 1
    if (-not $match) { throw 'k6 measurement boundary missing' }
    $measurementStart=[double]$match.Matches[0].Groups[1].Value / 1000
    Save-Json @{observationStart=$observationStart;measurementStart=$measurementStart;arrivalEnd=$measurementStart+$(if ($Scenario -eq 'business') { if ($Variant -eq 'abandon') {360} else {60} } else {$DurationSeconds});loadFinished=$loadFinished;drainEnd=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds();status='collected'} "$directory/phases.json"
} catch {
    if ($directory) { $_ | Out-String | Set-Content "$directory/error.txt" }
    throw
} finally {
    if ($directory) {
        if ($observer) {
            Set-Content "$directory/stop-observer" 'stop'
            $null=Wait-Job $observer -Timeout 20
            if ($observer.State -eq 'Running') { Stop-Job $observer }
            Receive-Job $observer -ErrorAction Continue 2>&1 | Out-File "$directory/observer.log"
            Remove-Job $observer
        }
        # Preserve each independent piece of evidence even after a run/collection failure.
        $collectors=@{
            'after-db.json'={ Sql (Get-Content "$PSScriptRoot/target-state.sql" -Raw) };
            'timeline.json'={ Sql (Get-Content "$PSScriptRoot/target-timeline.sql" -Raw) };
            'after-containers.json'={ Invoke-Docker (@('inspect')+@($ids.Values)) };
            'redis-slowlog.txt'={ Invoke-Docker @('exec',$ids.redis,'redis-cli','SLOWLOG','GET','128') };
            'services.log'={ Invoke-Docker ($compose+@('logs','--no-color','--since',$(if ($loadStarted) {$loadStarted} else {[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()}))) }
        }
        foreach ($entry in $collectors.GetEnumerator()) {
            try { & $entry.Value | Set-Content "$directory/$($entry.Key)" }
            catch { $_ | Out-String | Add-Content "$directory/collection-errors.txt" }
        }
        if ($loadStarted) {
            try {
                $query=[Uri]::EscapeDataString('{job="target",__name__=~"up|goods_.*|hikaricp_.*|process_.*|jvm_.*|http_server_requests_.*"}')
                $end=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
                Invoke-WebRequest "http://127.0.0.1:9090/api/v1/query_range?query=$query&start=$observationStart&end=$end&step=1" -TimeoutSec 60 -OutFile "$directory/prometheus.json"
            } catch { $_ | Out-String | Add-Content "$directory/collection-errors.txt" }
            try { & "$PSScriptRoot/target-review.ps1" -Directory $directory }
            catch { $_ | Out-String | Add-Content "$directory/collection-errors.txt" }
        }
    }
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
    if ($executionLock) { $executionLock.Dispose() }
    Pop-Location
}
if ($loadExit -ne 0 -or (Test-Path "$directory/error.txt") -or (Test-Path "$directory/collection-errors.txt")) { throw "실행/수집 실패. $directory 자료를 확인하세요." }
if ((Get-Content "$directory/result.json" -Raw | ConvertFrom-Json).status -eq 'failed') { throw "자동 검사 실패. $directory/result.json을 확인하세요." }
Write-Output "수집 완료. result.json의 자동 검사와 수동 판정을 함께 확인하세요: $directory"
