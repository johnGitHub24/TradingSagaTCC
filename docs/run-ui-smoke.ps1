# run-ui-smoke.ps1 - L1 UI Smoke (headless browser)
# Usage:
#   .\docs\run-ui-smoke.ps1              # assumes bootRun on :8093
#   .\docs\run-ui-smoke.ps1 -StartApp    # start bootRun then test
#   .\docs\run-ui-smoke.ps1 -InstallDeps # npm install in docs/ui-smoke first
#   .\docs\run-ui-smoke.ps1 -Headed      # visible browser (新建專案必做)
param(
    [string]$BaseUrl = 'http://localhost:8093',
    [switch]$StartApp,
    [switch]$InstallDeps,
    [switch]$Headed,
    [int]$HealthTimeoutSec = 120
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path (Join-Path $projectRoot 'build.gradle'))) {
    throw "build.gradle not found at $projectRoot"
}
Set-Location $projectRoot

$uiSmokeDir = Join-Path $PSScriptRoot 'ui-smoke'
$bootJob = $null

function Stop-BootJob {
    if ($bootJob) {
        Write-Host 'Stopping bootRun job...' -ForegroundColor Yellow
        Stop-Job $bootJob -ErrorAction SilentlyContinue
        Remove-Job $bootJob -Force -ErrorAction SilentlyContinue
    }
}

function Ensure-Puppeteer {
    $localPuppeteer = Join-Path $uiSmokeDir 'node_modules\puppeteer'
    if ($InstallDeps -or -not (Test-Path $localPuppeteer)) {
        if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
            throw 'npm required for UI Smoke. Install Node.js or run API Smoke only.'
        }
        Write-Host 'Installing puppeteer in docs/ui-smoke (one-time)...' -ForegroundColor Yellow
        $prevNodeOpts = $env:NODE_OPTIONS
        if (-not $env:NODE_OPTIONS) { $env:NODE_OPTIONS = '--use-system-ca' }
        Push-Location $uiSmokeDir
        try {
            npm install --no-fund --no-audit
        } finally {
            Pop-Location
            if ($null -eq $prevNodeOpts) { Remove-Item Env:NODE_OPTIONS -ErrorAction SilentlyContinue }
            else { $env:NODE_OPTIONS = $prevNodeOpts }
        }
    }
    return Join-Path $uiSmokeDir 'node_modules\puppeteer'
}

try {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        throw 'Node.js required (node -v). Install Node or use .\docs\run-api-smoke.ps1 for L1.'
    }

    $healthUrl = '{0}/actuator/health' -f $BaseUrl.TrimEnd('/')
    $healthy = $false
    try {
        $r = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
        if ($r.status -eq 'UP') { $healthy = $true }
    } catch { }

    if (-not $healthy -and $StartApp) {
        Write-Host "Starting bootRun ($BaseUrl)..." -ForegroundColor Yellow
        $bootJob = Start-Job -ScriptBlock {
            Set-Location $using:projectRoot
            & .\gradlew.bat bootRun 2>&1
        }
        $deadline = (Get-Date).AddSeconds($HealthTimeoutSec)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 3
            try {
                $r = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5
                if ($r.status -eq 'UP') {
                    $healthy = $true
                    break
                }
            } catch { }
        }
    }

    if (-not $healthy) {
        throw "Service not ready: $healthUrl not UP. Run gradlew bootRun or use -StartApp"
    }

    $null = Ensure-Puppeteer

    $headedMsg = if ($Headed) { 'headed (visible browser)' } else { 'headless' }
    Write-Host "Health UP - running UI smoke ($headedMsg)..." -ForegroundColor Cyan
    $mjs = Join-Path $uiSmokeDir 'run-headless.mjs'
    Push-Location $uiSmokeDir
    try {
        $nodeArgs = @($mjs, "--baseUrl=$BaseUrl")
        if ($Headed) { $nodeArgs += '--headed' }
        & node @nodeArgs
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        Pop-Location
    }

    Write-Host ''
    Write-Host '[OK] UI Smoke L1 passed - SERVICE COMPLETED' -ForegroundColor Green
} finally {
    Stop-BootJob
}
