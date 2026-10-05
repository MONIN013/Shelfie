param(
    [string[]]$Tasks = @(':core:test', ':renderer-api:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug'),
    [string]$Stage = (Join-Path $env:LOCALAPPDATA ('ShelfieBuild/' + [guid]::NewGuid().ToString('N'))),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Sdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [switch]$RerunTasks
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) { throw 'Set JAVA_HOME to JDK 17 or pass -JavaHome.' }
# Gradle file locks cannot be used on the WSL UNC filesystem. Build an isolated
# Windows copy, leaving all source editing and Git operations in the repository.
if ((Test-Path -LiteralPath $Stage) -and (Get-ChildItem -LiteralPath $Stage -Force | Select-Object -First 1)) {
    throw 'Stage must be an empty dedicated directory so deleted source files cannot survive a build.'
}
New-Item -ItemType Directory -Path $Stage -Force | Out-Null
$exclude = @('.git', '.gradle', '.kotlin', 'build', 'artifacts', '.asset-work', '.idea', '.tools', 'node_modules', 'server')
& robocopy $repo $Stage /E /R:0 /W:0 /NFL /NDL /NJH /NJS /NP /XD @exclude /XF local.properties | Out-Null
if ($LASTEXITCODE -ge 8) { throw 'Failed to copy source into Windows build stage.' }
Set-Content -LiteralPath (Join-Path $Stage 'local.properties') -Value ('sdk.dir=' + $Sdk.Replace('\','/').Replace(':','\:'))
$env:JAVA_HOME = $JavaHome
Push-Location $Stage
try {
    $options = @('--console', 'plain')
    if ($RerunTasks) { $options += '--rerun-tasks' }
    & .\gradlew.bat @Tasks @options
    $code = $LASTEXITCODE
    $out = Join-Path $repo 'artifacts'
    New-Item -ItemType Directory -Path $out -Force | Out-Null
    foreach ($module in @('app','core','renderer-api')) {
        $reports = Join-Path $Stage "$module\build\reports"
        if (Test-Path -LiteralPath $reports) {
            & robocopy $reports (Join-Path $out "$module-reports") /E /R:0 /W:0 /NFL /NDL /NJH /NJS /NP | Out-Null
            if ($LASTEXITCODE -ge 8) { throw "Could not copy $module reports." }
        }
    }
    if ($code -ne 0) { throw "Gradle failed ($code). Reports copied, APKs not refreshed. Stage: $Stage" }
    Get-ChildItem -LiteralPath (Join-Path $Stage 'app') -Recurse -Filter '*.apk' | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $out -Force
    }
} finally { Pop-Location }
