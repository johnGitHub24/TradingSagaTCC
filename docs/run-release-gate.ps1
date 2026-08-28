# run-release-gate.ps1 - Release gate: Pure check + optional L1 Smoke (docs layer)
# Does NOT modify scripts/check.ps1 (Pure boundary).
# Usage:
#   .\gradlew.bat bootRun                    # terminal 1
#   .\docs\run-release-gate.ps1              # terminal 2: check + L1
#   .\docs\run-release-gate.ps1 -SkipSmoke   # check only
#   .\docs\run-release-gate.ps1 -SkipCheck   # smoke only (check already green)
#   .\docs\run-release-gate.ps1 -SkipUi      # API L1 only; UI automation=N/A
param(
    [string]$BaseUrl = 'http://localhost:8093',
    [switch]$SkipCheck,
    [switch]$SkipSmoke,
    [switch]$SkipUi,
    [switch]$InstallUiDeps
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$here = $PSScriptRoot

Write-Host '=== Release Gate (check + optional L1 Smoke) ===' -ForegroundColor Yellow
Write-Host 'Graph: docs/graph-routing.md' -ForegroundColor DarkGray

if (-not $SkipCheck) {
    Write-Host ''
    Write-Host '[1/2] Pure check (scripts/check.ps1)...' -ForegroundColor Cyan
    & (Join-Path $projectRoot 'scripts\check.ps1')
    $checkExit = $LASTEXITCODE
    if ($checkExit -and $checkExit -ne 0) { exit $checkExit }
    Write-Host '[OK] check passed' -ForegroundColor Green
} else {
    Write-Host '[1/2] SkipCheck — assuming check already green' -ForegroundColor DarkYellow
}

if ($SkipSmoke) {
    Write-Host ''
    Write-Host '[2/2] SkipSmoke — Release gate ends at check (L0/L1 manual)' -ForegroundColor DarkYellow
    Write-Host 'ALL_RELEASE_GATE_CHECK_OK' -ForegroundColor Green
    exit 0
}

Write-Host ''
Write-Host '[2/2] L1 Smoke (bootRun must be UP on :8093)...' -ForegroundColor Cyan
$healthUrl = '{0}/actuator/health' -f $BaseUrl.TrimEnd('/')
try {
    $h = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5
    if ($h.status -ne 'UP') { throw "health=$($h.status)" }
} catch {
    Write-Host "Service not ready: $healthUrl" -ForegroundColor Red
    Write-Host 'Start bootRun in another terminal, then re-run this script.' -ForegroundColor Yellow
    exit 1
}

$smokeArgs = @{ BaseUrl = $BaseUrl }
if ($SkipUi) { $smokeArgs['SkipUi'] = $true }
if ($InstallUiDeps) { $smokeArgs['InstallUiDeps'] = $true }
& (Join-Path $here 'run-smoke-l1.ps1') @smokeArgs
$smokeExit = $LASTEXITCODE
if ($smokeExit -and $smokeExit -ne 0) { exit $smokeExit }

Write-Host ''
Write-Host 'ALL_RELEASE_GATE_OK' -ForegroundColor Green
Write-Host 'Copy EOS-LOOP-RELEASE evidence from run-smoke-l1 output; EOS-GRAPH=N/A if single Agent.'
