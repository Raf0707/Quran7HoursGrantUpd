param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$ErrorActionPreference = "Stop"
$path = Join-Path $ProjectRoot "app\src\main\assets\quran\qpc-v4\qul\quran_pages.json"
if (-not (Test-Path $path -PathType Leaf)) {
    Write-Error "QUL layout is missing: $path"
    exit 2
}
$file = Get-Item $path
if ($file.Length -lt 1000000) {
    Write-Error "QUL layout file is unexpectedly small: $($file.Length) bytes"
    exit 3
}
$text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
$matches = [regex]::Matches($text, '"page_number"\s*:\s*(\d+)')
$pages = New-Object 'System.Collections.Generic.HashSet[int]'
foreach ($m in $matches) { [void]$pages.Add([int]$m.Groups[1].Value) }
$missing = @()
for ($p = 1; $p -le 604; $p++) { if (-not $pages.Contains($p)) { $missing += $p } }
Write-Host "QUL/KFGQPC V4 layout audit"
Write-Host "  file:  $path"
Write-Host "  size:  $($file.Length) bytes"
Write-Host "  pages: $($pages.Count)/604"
if ($missing.Count -gt 0) {
    Write-Error "Missing page_number entries: $($missing[0..([Math]::Min($missing.Count - 1, 29))] -join ', ')"
    exit 4
}
Write-Host "OK: QUL layout id 19 is complete: 604/604 pages." -ForegroundColor Green
