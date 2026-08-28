# TradingSagaTCC - portable env (no hardcoded JDK paths)
# Copy portable-env.ps1 from eos-minimal/hooks when cloning; env.ps1 stays thin.

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$portable = Join-Path $here 'portable-env.ps1'
if (-not (Test-Path $portable)) {
    $walk = Split-Path $here -Parent
    for ($i = 0; $i -lt 6; $i++) {
        $eos = Join-Path $walk 'EngineeringOS\eos-minimal\hooks\portable-env.ps1'
        if (Test-Path $eos) { $portable = $eos; break }
        $parent = Split-Path $walk -Parent
        if (-not $parent -or $parent -eq $walk) { break }
        $walk = $parent
    }
}
if (Test-Path $portable) {
    . $portable
} else {
    Write-Host 'WARN: portable-env.ps1 not found — OS JAVA_HOME / PATH will be used as-is' -ForegroundColor Yellow
}
