# run-smoke-l1.ps1 - L1 orchestrator: API (required) + UI (optional)
# Usage:
#   .\docs\run-smoke-l1.ps1
#   .\docs\run-smoke-l1.ps1 -SkipUi          # API only; UI automation=N/A
#   .\docs\run-smoke-l1.ps1 -InstallUiDeps   # pass through to UI smoke
param(
    [string]$BaseUrl = 'http://localhost:8093',
    [switch]$SkipUi,
    [switch]$InstallUiDeps
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot

Write-Host '=== L1 Smoke (API + optional UI) ===' -ForegroundColor Yellow

& (Join-Path $here 'run-api-smoke.ps1') -BaseUrl $BaseUrl
$apiExit = $LASTEXITCODE
if ($apiExit -and $apiExit -ne 0) { exit $apiExit }

$uiAuto = 'N/A'
if (-not $SkipUi) {
    $uiArgs = @{ BaseUrl = $BaseUrl }
    if ($InstallUiDeps) { $uiArgs['InstallDeps'] = $true }
    & (Join-Path $here 'run-ui-smoke.ps1') @uiArgs
    $uiExit = $LASTEXITCODE
    if ($uiExit -and $uiExit -ne 0) {
        Write-Host 'UI automation=FAIL' -ForegroundColor Red
        exit $uiExit
    }
    $uiAuto = 'PASS'
}

Write-Host ''
Write-Host '=== L1 Smoke complete ===' -ForegroundColor Green
Write-Host "UI automation=$uiAuto"
Write-Host 'Copy to report: level=L1; see run-api-smoke output for 劇情'
