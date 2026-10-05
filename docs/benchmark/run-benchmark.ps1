param(
    [string]$Jar = "d:\it\AllProject\AAE-short-link\target\short-link-0.1.0-SNAPSHOT.jar",
    [string]$Jmeter = "D:\it\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat",
    [string]$Jmx = "d:\it\AllProject\AAE-short-link\docs\benchmark\redirect-benchmark.jmx",
    [string]$OutDir = "d:\it\AllProject\AAE-short-link\docs\benchmark\results",
    [string]$Analyzer = "d:\it\AllProject\AAE-short-link\docs\benchmark\analyze-jtl.ps1",
    [int]$Threads = 50,
    [int]$Loops = 200,
    [int]$Ramp = 5,
    [int]$WarmThreads = 10,
    [int]$WarmLoops = 10,
    [string]$Code = "3",
    [string]$Only = ""
)

$scenarios = @(
    @{ id = "S1"; name = "S1_baseline_nocache_nostats"; args = @("--shortlink.cache.enabled=false", "--shortlink.stats-enabled=false", "--shortlink.mq.enabled=false") },
    @{ id = "S2"; name = "S2_cache_nostats"; args = @("--shortlink.cache.enabled=true", "--shortlink.stats-enabled=false", "--shortlink.mq.enabled=false") },
    @{ id = "S3"; name = "S3_cache_stats_V1_syncstats"; args = @("--shortlink.cache.enabled=true", "--shortlink.stats-enabled=true", "--shortlink.mq.enabled=false") },
    @{ id = "S4"; name = "S4_nocache_stats"; args = @("--shortlink.cache.enabled=false", "--shortlink.stats-enabled=true", "--shortlink.mq.enabled=false") },
    @{ id = "S5"; name = "S5_cache_stats_V2_mq"; args = @("--shortlink.cache.enabled=true", "--shortlink.stats-enabled=true", "--shortlink.mq.enabled=true") }
)

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

function Write-Step($msg) {
    Write-Output ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg)
}

function Stop-App {
    $line = netstat -ano | Select-String ":8080\s+.*LISTENING" | Select-Object -First 1
    if ($line) {
        $procId = ($line.Line.Trim() -split '\s+')[-1]
        taskkill /PID $procId /F | Out-Null
        Start-Sleep -Seconds 2
    }
}

function Start-App($extraArgs) {
    $log = Join-Path $OutDir "app.log"
    $all = @("-jar", $Jar) + $extraArgs
    Start-Process -FilePath "java" -ArgumentList $all -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        if (netstat -ano | Select-String ":8080\s+.*LISTENING") { return $true }
    }
    return $false
}

function Invoke-Jmeter($jtl, [int]$t, [int]$l, [int]$ramp) {
    Write-Step ("JMeter start: threads={0} loops={1} file={2}" -f $t, $l, (Split-Path $jtl -Leaf))
    # 关键：JMeter 默认向已存在的 JTL 追加样本，必须 -f 强制覆盖，否则统计会被历史数据污染
    Remove-Item $jtl -ErrorAction SilentlyContinue
    Remove-Item "$jtl.log" -ErrorAction SilentlyContinue
    $sw = [Diagnostics.Stopwatch]::StartNew()
    & $Jmeter -f -n -t $Jmx "-Jthreads=$t" "-Jloops=$l" "-Jramp=$ramp" "-Jcode=$Code" "-JresultFile=$jtl" "-j$jtl.log" 2>&1 | Out-Null
    $sw.Stop()
    Write-Step ("JMeter done in {0}s" -f [Math]::Round($sw.Elapsed.TotalSeconds, 1))
}

foreach ($sc in $scenarios) {
    if ($Only -and ($Only -notlike "*$($sc.id)*")) { continue }
    Write-Step ("=== scenario {0}: {1} ===" -f $sc.id, ($sc.args -join ' '))
    Stop-App
    redis-cli DEL "short-link:link:localhost:8080:$Code" | Out-Null
    Write-Step "cache key cleared, starting app..."
    if (-not (Start-App $sc.args)) { Write-Step "ERROR: app start timeout"; continue }
    Write-Step "app ready on 8080"

    Invoke-Jmeter (Join-Path $OutDir "$($sc.name)_warmup.jtl") $WarmThreads $WarmLoops 1
    $jtl = Join-Path $OutDir "$($sc.name).jtl"
    Invoke-Jmeter $jtl $Threads $Loops $Ramp
    & powershell -NoProfile -ExecutionPolicy Bypass -File $Analyzer -Jtl $jtl -Title $sc.name
}
Stop-App
Write-Step "all scenarios finished"
