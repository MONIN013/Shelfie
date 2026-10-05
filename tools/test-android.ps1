param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$OutputDirectory = 'artifacts/android',
    [string]$ApplicationId = 'net.monindev.shelfie.validation.filament',
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$out=Join-Path $repo $OutputDirectory
New-Item -ItemType Directory -Path $out -Force | Out-Null
# Instrumentation replaces the shelf, so use a validation application.
if ($ApplicationId -ne 'net.monindev.shelfie.validation.filament') { throw 'Build with -PshelfieValidation=true and use the validation application ID.' }
foreach ($suffix in @('', '-androidTest')) {
    $apk=Join-Path $repo "artifacts\app-debug$suffix.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "Build the app and its instrumentation APK first: $apk" }
    & $Adb -s $Serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "Could not install $apk" }
}
$result=[System.Collections.Generic.List[string]]::new()
& $Adb -s $Serial shell am instrument -w -r "$ApplicationId.test/androidx.test.runner.AndroidJUnitRunner" |
    Tee-Object -FilePath (Join-Path $out 'instrumentation.txt') |
    ForEach-Object { $result.Add([string]$_); Write-Output $_ }
$code=$LASTEXITCODE
# adb can return zero even when native instrumentation crashes.
$joined=$result -join "`n"
if ($code -ne 0 -or $joined -notmatch 'OK \(\d+ tests?\)' -or $joined -notmatch 'INSTRUMENTATION_CODE: -1') {
    throw "Instrumentation failed. Inspect $OutputDirectory/instrumentation.txt"
}
