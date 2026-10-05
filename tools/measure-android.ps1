param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$package='net.monindev.shelfie.lab.filament'
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$out=Join-Path $repo "artifacts\measurements\$stamp"
New-Item -ItemType Directory -Path $out -Force | Out-Null
function Run-Adb([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments) {
    $result=& $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $Arguments" }
    return $result
}
$model=Run-Adb @('shell','getprop','ro.product.model')
$sdk=Run-Adb @('shell','getprop','ro.build.version.sdk')
$virtual=Run-Adb @('shell','getprop','ro.kernel.qemu')
[ordered]@{ model=$model; api=$sdk; emulator=($virtual -eq '1'); renderer='filament'; capturedAt=$stamp; build='debug'; physicalPerformanceVerdict='not evaluated' } |
    ConvertTo-Json | Set-Content (Join-Path $out 'environment.json')
Run-Adb @('shell','dumpsys','meminfo',$package) | Set-Content (Join-Path $out 'memory-before.txt')
Run-Adb @('shell','dumpsys','thermalservice') | Set-Content (Join-Path $out 'thermal-before.txt')
Run-Adb @('push',(Join-Path $PSScriptRoot 'perfetto-shelfie.pbtxt'),'/data/local/tmp/shelfie-trace.pbtxt') | Out-Null
Write-Output 'Capturing 15 seconds. Move books and zoom during this interval.'
# Perfetto cannot open shell_data_file paths under SELinux; feed the config on stdin.
Run-Adb @('shell','cat /data/local/tmp/shelfie-trace.pbtxt | perfetto --txt -c - -o /data/misc/perfetto-traces/shelfie.perfetto-trace') | Out-Null
Run-Adb @('pull','/data/misc/perfetto-traces/shelfie.perfetto-trace',(Join-Path $out 'frames.perfetto-trace')) | Out-Null
Run-Adb @('shell','dumpsys','meminfo',$package) | Set-Content (Join-Path $out 'memory-after.txt')
Run-Adb @('shell','dumpsys','thermalservice') | Set-Content (Join-Path $out 'thermal-after.txt')
Run-Adb @('shell','dumpsys','SurfaceFlinger','--list') | Select-String $package | Set-Content (Join-Path $out 'surfaces.txt')
Write-Output "Saved measurement inputs to $out. Inspect renderer SurfaceView frames separately from Compose; no automatic FPS claim is made."
