# run-manual.ps1 - auto screenshots + feature tour + operation manual (HTML + Markdown)
# Not part of gradlew check (real port required). Script: docs/manual/flows.mjs ; runner: docs/manual/build-manual.mjs
# Prereq: bootRun on :8093 (embedded Kafka, no Docker)
# Output: docs/manual/*.html, docs/manual/*.md, docs/manual/shots/*.png
#   Gradle processResources copies them into static/manual -> nav button "manual" opens /manual/...html
#   (re-run bootRun / processResources after regenerating so the served copy is fresh)
# Usage:
#   .\docs\run-manual.ps1             # headless
#   .\docs\run-manual.ps1 -Headed     # watch the browser
#   .\docs\run-manual.ps1 -Open       # open the HTML manual afterwards
param(
    [string]$BaseUrl = 'http://localhost:8093',
    [switch]$Headed,
    [switch]$Open,
    [switch]$InstallDeps
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path (Join-Path $projectRoot 'build.gradle'))) {
    throw "build.gradle not found at $projectRoot"
}
Set-Location $projectRoot
$uiSmokeDir = Join-Path $PSScriptRoot 'ui-smoke'
$manualDir = Join-Path $PSScriptRoot 'manual'

if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js required (node -v).' }

$healthUrl = '{0}/actuator/health' -f $BaseUrl.TrimEnd('/')
try {
    $r = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5
    if ($r.status -ne 'UP') { throw 'not UP' }
} catch {
    throw "not UP: $healthUrl  (run .\gradlew.bat bootRun first)"
}

$localPuppeteer = Join-Path $uiSmokeDir 'node_modules\puppeteer'
if ($InstallDeps -or -not (Test-Path $localPuppeteer)) {
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { throw 'npm required.' }
    Write-Host 'Installing puppeteer in docs/ui-smoke (one-time)...' -ForegroundColor Yellow
    $prevNodeOpts = $env:NODE_OPTIONS
    if (-not $env:NODE_OPTIONS) { $env:NODE_OPTIONS = '--use-system-ca' }
    Push-Location $uiSmokeDir
    try {
        npm install --no-fund --no-audit
        if ($LASTEXITCODE -ne 0) { throw 'npm install failed' }
    } finally {
        Pop-Location
        if ($null -eq $prevNodeOpts) { Remove-Item Env:NODE_OPTIONS -ErrorAction SilentlyContinue } else { $env:NODE_OPTIONS = $prevNodeOpts }
    }
}

$mode = if ($Headed) { 'headed' } else { 'headless' }
Write-Host "Operation manual ($mode) -> $BaseUrl" -ForegroundColor Cyan
$nodeArgs = @((Join-Path $manualDir 'build-manual.mjs'), "--baseUrl=$BaseUrl")
if ($Headed) { $nodeArgs += '--headed' }
& node @nodeArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host 'FAILED - see docs\manual\shots\_fail-*.png' -ForegroundColor Red
    exit $LASTEXITCODE
}

# ASCII-only script (PowerShell 5 reads BOM-less UTF-8 as ANSI): locate the generated HTML by extension
$html = Get-ChildItem $manualDir -Filter '*.html' | Select-Object -First 1 -ExpandProperty FullName
Write-Host ''
Write-Host "[OK] manual: $html" -ForegroundColor Green
Write-Host '     served at /manual/ after next bootRun (processResources)' -ForegroundColor DarkGray
if ($Open) { Start-Process $html }
