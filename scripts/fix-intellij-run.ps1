# Portable IntelliJ Run helper (remote clone OK without EngineeringOS)
# Daily path: IntelliJ Open project root -> SDK 21 -> Gradle bootRun (.idea is local, not git)
# Optional: if EngineeringOS sibling exists, regenerate local .idea configs

$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
$hook = $null
$walk = Split-Path $Root -Parent
for ($i = 0; $i -lt 6; $i++) {
    $cand = Join-Path $walk 'EngineeringOS\eos-minimal\hooks\fix-intellij-run.ps1'
    if (Test-Path $cand) { $hook = $cand; break }
    $parent = Split-Path $walk -Parent
    if (-not $parent -or $parent -eq $walk) { break }
    $walk = $parent
}
if ($hook) {
    & $hook -ProjectRoot $Root
    exit $LASTEXITCODE
}

Write-Host 'Run Anywhere: Open project root, SDK=21, Gradle bootRun (.idea is local only).' -ForegroundColor Green
Write-Host '1. Open project ROOT  2. Project SDK = 21  3. Gradle Sync' -ForegroundColor Cyan
Write-Host '4. Gradle tool window -> bootRun' -ForegroundColor Cyan
Write-Host 'Do NOT use green arrow on *Application.java (0xC0000005)' -ForegroundColor Yellow
$props = Join-Path $PSScriptRoot 'intellij-run.properties'
if (Test-Path $props) {
    Get-Content $props | Where-Object { $_ -match 'BOOT_' } | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
}
