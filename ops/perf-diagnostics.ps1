# Loaded by performance.ps1. The observer uses one separate, read-only PostgreSQL connection.
function Start-DbObserver {
    param([string]$Directory, [int]$SampleCount = 450,
        [string]$SqlPath = "$PSScriptRoot/db-waits.sql", [string]$Label = 'db-waits',
        [Guid]$ExcludeSaleId = [Guid]::Empty)
    $name = 'goods-perf-observer-' + [Guid]::NewGuid().ToString('N')
    Copy-Item -LiteralPath $SqlPath -Destination "$Directory/$Label.sql"
    $process = Start-Process -FilePath (Get-Command docker).Source -WindowStyle Hidden -PassThru `
        -ArgumentList @('compose','--env-file','ops/perf.env','exec','-T','-e',"PGAPPNAME=$name",
            'postgres','psql','-X','-qAt','-v','ON_ERROR_STOP=1','-v',"sample_count=$SampleCount",'-v',"exclude_sale_id=$ExcludeSaleId",
            '-U','goods','-d','limited_goods_perf') `
        -RedirectStandardInput "$Directory/$Label.sql" `
        -RedirectStandardOutput "$Directory/$Label.txt" `
        -RedirectStandardError "$Directory/$Label-errors.txt"
    [pscustomobject]@{ Process=$process; Name=$name; Directory=$Directory; Label=$Label }
}

function Wait-DbObserver {
    param($Observer)
    for ($i=0; $i -lt 25; $i++) {
        if ($Observer.Process.HasExited) { throw "$($Observer.Label) 관측 프로세스가 부하 전에 종료됐습니다." }
        if (Select-String -LiteralPath "$($Observer.Directory)/$($Observer.Label).txt" -Pattern '"sampled_at"' -Quiet) { return }
        Start-Sleep -Milliseconds 200
    }
    throw "$($Observer.Label) 관측 준비 미완료. 부하는 시작하지 않았습니다."
}

function Stop-DbObserver {
    param($Observer)
    if (-not $Observer) { return }
    if (-not $Observer.Process.HasExited) {
        # Only terminate this run's observer, never an application session.
        $sql = "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='$($Observer.Name)' AND datname='limited_goods_perf'"
        & docker compose --env-file ops/perf.env exec -T postgres psql -X -qAt -v ON_ERROR_STOP=1 `
            -U goods -d limited_goods_perf -c $sql | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'DB 관측 세션 종료 실패 (표본 횟수 상한 도달 시 자체 종료)' }
        if (-not $Observer.Process.WaitForExit(5000)) { throw 'DB 관측 프로세스 종료 확인 실패' }
        Set-Content "$($Observer.Directory)/$($Observer.Label)-status.txt" 'stopped by collector; PostgreSQL termination message is expected'
    } else {
        $Observer.Process.WaitForExit()
        if ($Observer.Process.ExitCode -ne 0) { throw "DB 관측 실패: exit=$($Observer.Process.ExitCode)" }
        Set-Content "$($Observer.Directory)/$($Observer.Label)-status.txt" 'sample count limit reached'
    }
    if (-not (Select-String -LiteralPath "$($Observer.Directory)/$($Observer.Label).txt" -Pattern '"sampled_at"' -Quiet)) {
        throw 'DB 관측 표본 없음'
    }
}

function Save-Prometheus {
    param([string]$Directory, [long]$Start, [long]$End)
    $query = [Uri]::EscapeDataString('{job="goods"}')
    $url = "http://127.0.0.1:9090/api/v1/query_range?query=$query&start=$Start&end=$End&step=1"
    Invoke-WebRequest $url -TimeoutSec 30 -OutFile "$Directory/prometheus.json"
    $series = Get-Content "$Directory/prometheus.json" -Raw | ConvertFrom-Json
    if ($series.status -ne 'success' -or @($series.data.result).Count -eq 0) {
        throw 'Prometheus 시계열 수집 결과가 비어 있습니다.'
    }
}
