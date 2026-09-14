param(
    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Root = Join-Path $ProjectRoot "app/src/main/assets/quran/qpc-v4"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $ProjectRoot "quran-offline-pack-audit.json"
}

$layoutMissing = New-Object System.Collections.Generic.List[int]
$v4Missing = New-Object System.Collections.Generic.List[int]
$v2Missing = New-Object System.Collections.Generic.List[int]
$layoutInvalid = New-Object System.Collections.Generic.List[int]
$v4Invalid = New-Object System.Collections.Generic.List[int]
$v2Invalid = New-Object System.Collections.Generic.List[int]

function Test-Ttf([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $info = Get-Item -LiteralPath $Path
    if ($info.Length -lt 1024) { return $false }
    $fs = [System.IO.File]::OpenRead($Path)
    try {
        $buf = New-Object byte[] 4
        if ($fs.Read($buf, 0, 4) -ne 4) { return $false }
        $sig = [System.Text.Encoding]::ASCII.GetString($buf)
        $isSfnt = ($buf[0] -eq 0 -and $buf[1] -eq 1 -and $buf[2] -eq 0 -and $buf[3] -eq 0)
        return $isSfnt -or $sig -eq "OTTO" -or $sig -eq "ttcf" -or $sig -eq "true" -or $sig -eq "typ1"
    }
    finally { $fs.Dispose() }
}

# IMPORTANT: do not use Windows PowerShell 5.1 ConvertFrom-Json here.
# Large Mushaf page JSON with private-use QPC glyphs caused false negatives
# (in the reported case pages 100..604 were marked invalid even after all
# 604 files had just been copied from the official layout archive).
# We only need a deterministic structural audit before packaging assets.
function Test-Layout([string]$Path, [int]$Page) {
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $info = Get-Item -LiteralPath $Path
    if ($info.Length -lt 128) { return $false }
    try {
        $raw = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
        if ([string]::IsNullOrWhiteSpace($raw)) { return $false }

        $pagePattern = '"(?:page|pageNumber)"\s*:\s*' + [regex]::Escape($Page.ToString()) + '(?=\s*[,}])'
        if (-not [regex]::IsMatch($raw, $pagePattern)) { return $false }
        if (-not [regex]::IsMatch($raw, '"lines"\s*:\s*\[')) { return $false }
        # A real mushaf-layout page contains QPC glyph data. This also rejects
        # accidental word-by-word/page JSON files with a matching page number.
        if (-not [regex]::IsMatch($raw, '"qpcV2"\s*:')) { return $false }
        return $true
    }
    catch { return $false }
}

for ($page = 1; $page -le 604; $page++) {
    $padded = $page.ToString("000")
    $layout = Join-Path $Root "layout/page-$padded.json"
    $v4 = Join-Path $Root "fonts/v4/p$page.ttf"
    $v2 = Join-Path $Root "fonts/v2/p$page.ttf"

    if (-not (Test-Path -LiteralPath $layout)) { $layoutMissing.Add($page) }
    elseif (-not (Test-Layout $layout $page)) { $layoutInvalid.Add($page) }

    if (-not (Test-Path -LiteralPath $v4)) { $v4Missing.Add($page) }
    elseif (-not (Test-Ttf $v4)) { $v4Invalid.Add($page) }

    if (-not (Test-Path -LiteralPath $v2)) { $v2Missing.Add($page) }
    elseif (-not (Test-Ttf $v2)) { $v2Invalid.Add($page) }
}

$badPages = @($layoutMissing + $layoutInvalid + $v4Missing + $v4Invalid + $v2Missing + $v2Invalid | Sort-Object -Unique)
$ok = $badPages.Count -eq 0

$report = [ordered]@{
    schema = 3
    checked_utc = [DateTime]::UtcNow.ToString("o")
    complete = $ok
    expected_pages = 604
    expected_assets = 1812
    layout = [ordered]@{
        valid = 604 - $layoutMissing.Count - $layoutInvalid.Count
        missing_pages = @($layoutMissing)
        invalid_pages = @($layoutInvalid)
    }
    v4_tajweed = [ordered]@{
        valid = 604 - $v4Missing.Count - $v4Invalid.Count
        missing_pages = @($v4Missing)
        invalid_pages = @($v4Invalid)
    }
    v2_plain = [ordered]@{
        valid = 604 - $v2Missing.Count - $v2Invalid.Count
        missing_pages = @($v2Missing)
        invalid_pages = @($v2Invalid)
    }
    incomplete_pages = @($badPages)
}

$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

Write-Host "Quran7Hours offline Mushaf audit"
Write-Host "  layouts:      $($report.layout.valid)/604"
Write-Host "  V4 Tajweed:   $($report.v4_tajweed.valid)/604"
Write-Host "  V2 plain:     $($report.v2_plain.valid)/604"
Write-Host "  total:        $(($report.layout.valid + $report.v4_tajweed.valid + $report.v2_plain.valid))/1812"
Write-Host "  report:       $ReportPath"

if (-not $ok) {
    Write-Warning "Incomplete QPC pack: $($badPages.Count) page(s) have at least one missing/invalid resource."
    if ($badPages.Count -gt 0) {
        Write-Host ("  first pages:   " + (($badPages | Select-Object -First 30) -join ", "))
    }
    exit 2
}

Write-Host "OK: complete 604-page QPC pack is present."
exit 0
