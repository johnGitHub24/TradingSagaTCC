# run-l0-smoke.ps1 - L0 probe only (health + UI 200)
# Usage: .\docs\run-l0-smoke.ps1
param(
    [string]$BaseUrl = 'http://localhost:8093'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$healthUrl = '{0}/actuator/health' -f $BaseUrl

Write-Host "L0 Smoke -> $BaseUrl" -ForegroundColor Cyan

$h = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5
if ($h.status -ne 'UP') { throw "health not UP: $($h.status)" }
Write-Host 'health=UP' -ForegroundColor Green

$homeRes = Invoke-WebRequest -Uri $BaseUrl -UseBasicParsing -TimeoutSec 5
if ($homeRes.StatusCode -ne 200) { throw "UI home not 200: $($homeRes.StatusCode)" }
Write-Host 'UI=200' -ForegroundColor Green

$runner = Invoke-WebRequest -Uri ('{0}/test/runner.html' -f $BaseUrl) -UseBasicParsing -TimeoutSec 5
if ($runner.StatusCode -ne 200) { throw "runner not 200: $($runner.StatusCode)" }
Write-Host 'runner=200' -ForegroundColor Green

Write-Host ''
Write-Host 'ALL_L0_SMOKE_OK' -ForegroundColor Green
