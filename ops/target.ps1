#requires -Version 7.0
param([ValidateSet('Check','Start','Smoke','Stop')][string]$Action='Check',[switch]$RedisOutage)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
Push-Location (Split-Path $PSScriptRoot -Parent)
$compose=@('compose','-f','compose.yaml','-f','compose.target.yml')
function Invoke-Docker([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker failed: $($Arguments -join ' ')" }
}
try {
    if ($Action -eq 'Start') {
        if (Invoke-Docker @('ps','--filter','name=^/goods-k6$','-q')) { throw '기존 k6 부하를 먼저 종료하세요.' }
        foreach ($service in @('api1','api2','worker','mock-pg','reservation','payment')) {
            $id=Invoke-Docker ($compose+@('ps','-q',$service))
            if ($id) {
                $info=@((Invoke-Docker @('inspect',$id) | ConvertFrom-Json))[0]
                if ($info.Config.Env -contains 'DB_URL=jdbc:postgresql://postgres:5432/limited_goods_perf') {
                    throw 'perf 프로세스를 먼저 종료하세요. Target 시작으로 기존 실험을 중단하지 않습니다.'
                }
            }
        }
        if ($env:DB_NAME -and $env:DB_NAME -ne 'limited_goods_dev') { throw 'Target 기능 확인은 dev DB를 사용합니다.' }
        if ($env:APP_ENV -and $env:APP_ENV -ne 'dev') { throw 'Target 기능 확인은 dev Redis namespace를 사용합니다.' }
        & ./gradlew.bat --no-daemon bootJar
        if ($LASTEXITCODE -ne 0) { throw 'JAR 빌드 실패' }
        Invoke-Docker ($compose+@('build','api1'))
        Invoke-Docker ($compose+@('up','-d','--wait','--wait-timeout','300'))
    } elseif ($Action -eq 'Stop') {
        Invoke-Docker ($compose+@('stop','nginx','checkout-nginx','api1','api2','reservation','payment','worker','mock-pg'))
        return
    } elseif ($Action -eq 'Smoke') {
        & (Join-Path $PSScriptRoot 'target-smoke.ps1') -RedisOutage:$RedisOutage
        return
    }
    Invoke-Docker ($compose+@('config','--quiet'))
    $profiles=@{api1='waiting';api2='waiting';reservation='reservation';payment='payment';worker='worker';'mock-pg'='mockpg'}
    $status=foreach ($service in @('postgres','redis','api1','api2','reservation','payment','worker','mock-pg','nginx','checkout-nginx')) {
        $id=Invoke-Docker ($compose+@('ps','-q',$service))
        if (-not $id) { throw "$service 실행 안 됨" }
        $info=@((Invoke-Docker @('inspect',$id) | ConvertFrom-Json))[0]
        if (-not $info.State.Running -or ($info.State.Health -and $info.State.Health.Status -ne 'healthy')) { throw "$service 준비 안 됨" }
        if ($profiles.ContainsKey($service) -and $info.Config.Env -notcontains "SPRING_PROFILES_ACTIVE=$($profiles[$service])") { throw "$service Target 실행 역할 불일치" }
        [pscustomobject]@{service=$service;profile=($info.Config.Env | Where-Object { $_ -like 'SPRING_PROFILES_ACTIVE=*' });status=$info.State.Status}
    }
    $status | ConvertTo-Json
} finally { Pop-Location }
