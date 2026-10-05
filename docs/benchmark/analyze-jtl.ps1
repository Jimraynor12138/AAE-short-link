param(
    [Parameter(Mandatory = $true)][string]$Jtl,
    [string]$Title = "benchmark"
)

$rows = Import-Csv -Path $Jtl
if (-not $rows -or $rows.Count -eq 0) {
    Write-Output ("[{0}] no data" -f $Title)
    exit 1
}

$values = @($rows | ForEach-Object { [double]$_.elapsed } | Sort-Object)
$count = $values.Count
$errors = @($rows | Where-Object { $_.success -ne 'true' }).Count
$sum = ($values | Measure-Object -Sum).Sum
$ts = @($rows | ForEach-Object { [double]$_.timeStamp })
$minTs = ($ts | Measure-Object -Minimum).Minimum
$maxTs = ($ts | Measure-Object -Maximum).Maximum
$durationSec = ($maxTs - $minTs) / 1000.0
if ($durationSec -le 0) { $durationSec = 0.001 }

function Get-Percentile($sorted, [double]$p) {
    $idx = [int][Math]::Ceiling($p * $sorted.Count) - 1
    if ($idx -lt 0) { $idx = 0 }
    if ($idx -ge $sorted.Count) { $idx = $sorted.Count - 1 }
    return $sorted[$idx]
}

$avg = [Math]::Round($sum / $count, 2)
$errRate = [Math]::Round(100.0 * $errors / $count, 2)
$qps = [Math]::Round($count / $durationSec, 1)
$p50 = [Math]::Round((Get-Percentile $values 0.50), 1)
$p95 = [Math]::Round((Get-Percentile $values 0.95), 1)
$p99 = [Math]::Round((Get-Percentile $values 0.99), 1)

$line1 = "[{0}] samples={1} errors={2} errRate={3}% duration={4}s" -f $Title, $count, $errors, $errRate, [Math]::Round($durationSec, 1)
$line2 = "[{0}] QPS={1} avg={2}ms p50={3}ms p95={4}ms p99={5}ms min={6}ms max={7}ms" -f $Title, $qps, $avg, $p50, $p95, $p99, $values[0], $values[$count - 1]
Write-Output $line1
Write-Output $line2
