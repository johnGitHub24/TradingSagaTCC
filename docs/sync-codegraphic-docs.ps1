# sync-codegraphic-docs.ps1 - re-embed Markdown docs into docs/codeGraphic.html (tab "docs").
# When : after editing any .md listed by a <script type="text/markdown" data-src="..."> block in codeGraphic.html.
# Run  : .\docs\sync-codegraphic-docs.ps1          (rewrite embedded copies)
#        .\docs\sync-codegraphic-docs.ps1 -Check   (exit 1 if any embedded copy is stale; no write)
# OK   : prints every doc id and "done" (or "up to date" with -Check).
# The doc list lives in codeGraphic.html itself (data-src, relative to docs/), so this file stays ASCII-only.
param([switch]$Check)

$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding $false
try { [Console]::OutputEncoding = $utf8 } catch { }
$docsDir = $PSScriptRoot
$htmlPath = Join-Path $docsDir 'codeGraphic.html'
$original = [IO.File]::ReadAllText($htmlPath, $utf8)

$pattern = '(?s)(<script type="text/markdown" data-doc="([^"]+)" data-src="([^"]+)">)(.*?)(</script>)'
$found = [regex]::Matches($original, $pattern)
if ($found.Count -eq 0) { throw "no embedded markdown blocks found in $htmlPath" }

$stale = New-Object System.Collections.Generic.List[string]
$updated = [regex]::Replace($original, $pattern, {
    param($m)
    $id = $m.Groups[2].Value
    $src = [Uri]::UnescapeDataString($m.Groups[3].Value)
    $mdPath = [IO.Path]::GetFullPath((Join-Path $docsDir $src))
    if (-not (Test-Path -LiteralPath $mdPath)) { throw "doc '$id' source missing: $mdPath" }
    # "</script" inside the block would close the HTML element early; the page reverses this escape.
    $md = [IO.File]::ReadAllText($mdPath, $utf8) -replace '(?i)</script', '<\/script'
    $block = $m.Groups[1].Value + "`n" + $md.Trim() + "`n" + $m.Groups[5].Value
    if ($block -ne $m.Value) { $stale.Add($id) }
    Write-Host ("  " + $id.PadRight(14) + " <- " + $src)
    return $block
})

if ($Check) {
    if ($stale.Count -gt 0) {
        Write-Host ("STALE: " + ($stale -join ', ') + " -> run .\docs\sync-codegraphic-docs.ps1") -ForegroundColor Red
        exit 1
    }
    Write-Host 'codeGraphic embedded docs: up to date' -ForegroundColor Green
    exit 0
}

if ($updated -ne $original) {
    [IO.File]::WriteAllText($htmlPath, $updated, $utf8)
    Write-Host ("updated: " + ($stale -join ', ')) -ForegroundColor Green
}
Write-Host 'done' -ForegroundColor Green
