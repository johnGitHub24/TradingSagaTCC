# portable-env.ps1 — Honor OS JAVA_HOME only (never embed / never invent JDK paths)
# If the machine already has JAVA_HOME, use it. Projects do not configure JDK location.

param(
    [int] $MinJavaVersion = 21
)

$ErrorActionPreference = 'Stop'

# Run Anywhere: console + child processes always UTF-8 (not MS950), even without JAVA_HOME
try {
    cmd /c "chcp 65001 >NUL"
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [Console]::OutputEncoding = $utf8
    [Console]::InputEncoding = $utf8
    $OutputEncoding = $utf8
} catch {
    # non-interactive hosts may lack a console
}
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
if ($env:JAVA_TOOL_OPTIONS -notmatch 'file\.encoding=UTF-8') {
    $env:JAVA_TOOL_OPTIONS = ("$($env:JAVA_TOOL_OPTIONS) -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8").Trim()
}

function Get-JavaMajorVersion([string] $JavaExe) {
    if (-not (Test-Path $JavaExe)) { return 0 }
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & $JavaExe -version 2>&1 | Out-String
    $ErrorActionPreference = $prevEap
    if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    if ($out -match 'version "1\.(\d+)') { return [int]$Matches[1] }
    return 0
}

# Inherit OS env: Process first, else User/Machine (no scanning disks)
if (-not $env:JAVA_HOME) {
    foreach ($scope in @('User', 'Machine')) {
        $v = [Environment]::GetEnvironmentVariable('JAVA_HOME', $scope)
        if ($v) { $env:JAVA_HOME = $v.TrimEnd('\', '/'); break }
    }
}

if (-not $env:JAVA_HOME) {
    Write-Host "WARN: JAVA_HOME not in environment — rely on PATH / Gradle toolchain." -ForegroundColor Yellow
    return
}

$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    Write-Host "WARN: JAVA_HOME=$($env:JAVA_HOME) has no bin\java.exe" -ForegroundColor Yellow
    return
}

$ver = Get-JavaMajorVersion $javaExe
if ($ver -lt $MinJavaVersion) {
    Write-Host "WARN: JAVA_HOME is Java $ver (want $MinJavaVersion+)" -ForegroundColor Yellow
    return
}

$bin = Join-Path $env:JAVA_HOME 'bin'
if ($env:Path -notlike "$bin;*") {
    $env:Path = "$bin;" + $env:Path
}
Write-Host "JAVA_HOME=$($env:JAVA_HOME) (Java $ver) UTF-8" -ForegroundColor DarkGray
