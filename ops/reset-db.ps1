param(
    [ValidateSet('test','perf','dev')][string]$Environment = 'test',
    [switch]$AllowDevReset
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if ($Environment -eq 'dev' -and -not $AllowDevReset) {
    throw 'dev 초기화에는 -AllowDevReset을 명시해야 합니다.'
}
$dbName = "limited_goods_$Environment"
$prefix = 'goods:' + $Environment + ':'
# Stop only this Compose project; preserve volumes and other projects.
docker compose stop nginx api1 api2 worker mock-pg
if ($LASTEXITCODE -ne 0) { throw '서비스 중단 실패. 초기화를 수행하지 않았습니다.' }
$actual = docker compose exec -T postgres psql -U goods -d $dbName -Atc 'select current_database()'
if ($LASTEXITCODE -ne 0 -or $actual.Trim() -ne $dbName) { throw '초기화 대상 DB 검증 실패' }
$sql = @'
BEGIN;
TRUNCATE mock_pg_receipts, payment_attempts, reservations, order_items, orders, sale_items, sales;
COMMIT;
'@
$sql | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U goods -d $dbName
if ($LASTEXITCODE -ne 0) { throw '초기화 실패. 대상 DB에 Flyway 마이그레이션을 먼저 적용하세요.' }
$keys = @(docker compose exec -T redis redis-cli --scan --pattern ($prefix + '*'))
if ($LASTEXITCODE -ne 0) { throw 'Redis namespace 조회 실패' }
foreach ($key in $keys) {
    if (-not [string]::IsNullOrWhiteSpace($key)) {
        docker compose exec -T redis redis-cli DEL $key | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Redis namespace 초기화 실패' }
    }
}
Write-Output "$dbName 및 $prefix 초기화 완료. Flyway 이력과 볼륨은 보존했습니다."
Write-Output '서비스는 중단 상태입니다. dev: docker compose up -d / perf: docker compose --env-file ops/perf.env up -d'
