param(
    [switch]$ForceDownload,
    [switch]$KeepArchives
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AssetsRoot = Join-Path $ProjectRoot "app/src/main/assets/quran/qpc-v4"
$LayoutDest = Join-Path $AssetsRoot "layout"
$V4Dest = Join-Path $AssetsRoot "fonts/v4"
$V2Dest = Join-Path $AssetsRoot "fonts/v2"
$Cache = Join-Path $ProjectRoot ".quran-offline-pack-cache"
$Extract = Join-Path $Cache "extract"

New-Item -ItemType Directory -Force -Path $LayoutDest,$V4Dest,$V2Dest,$Cache | Out-Null

function Invoke-Audit {
    & (Join-Path $PSScriptRoot "audit_quran_offline_pack.ps1")
    return $LASTEXITCODE
}

function Write-ReadyMarker {
    $Marker = Join-Path $AssetsRoot "OFFLINE_ASSETS_READY.txt"
    @"
Quran7Hours complete offline QPC pack
layouts=604
v4_tajweed_fonts=604
v2_plain_fonts=604
installed_utc=$([DateTime]::UtcNow.ToString("o"))
"@ | Set-Content -LiteralPath $Marker -Encoding UTF8
}

# Fast path: with the fixed auditor an already-complete pack is accepted
# immediately and the ready marker is restored without any download.
if (-not $ForceDownload) {
    $audit = Invoke-Audit
    if ($audit -eq 0) {
        Write-ReadyMarker
        Write-Host "Offline QPC pack is already complete. Ready marker refreshed; no download needed."
        exit 0
    }
    Write-Host "Existing pack is partial/corrupt; repairing from complete archives."
}

$V4Zip = Join-Path $Cache "qcf-v4.zip"
$V2Zip = Join-Path $Cache "qcf-v2.zip"
$LayoutZip = Join-Path $Cache "mushaf-layout-main.zip"

function Get-Archive([string]$Url, [string]$Destination) {
    if ((-not $ForceDownload) -and (Test-Path $Destination) -and ((Get-Item $Destination).Length -gt 1024)) {
        Write-Host "Using cached archive: $([IO.Path]::GetFileName($Destination))"
        return
    }

    if (Test-Path $Destination) { Remove-Item -Force $Destination }
    Write-Host "Downloading $Url"

    # curl.exe handles redirects/content-length quirks more reliably than BITS
    # for GitHub/CDN archive endpoints on current Windows builds.
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($null -ne $curl) {
        & $curl.Source -L --fail --retry 4 --retry-delay 2 --connect-timeout 20 -o $Destination $Url
        if ($LASTEXITCODE -ne 0) { throw "curl failed ($LASTEXITCODE): $Url" }
    }
    else {
        Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing
    }

    if (-not (Test-Path $Destination) -or (Get-Item $Destination).Length -lt 1024) {
        throw "Downloaded archive is missing or too small: $Destination"
    }
}

function Expand-Clean([string]$Zip, [string]$Name) {
    $Dir = Join-Path $Extract $Name
    if (Test-Path $Dir) { Remove-Item -Recurse -Force $Dir }
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    Expand-Archive -LiteralPath $Zip -DestinationPath $Dir -Force
    return $Dir
}

function Install-FontSet([string]$SourceDir, [string]$Destination, [string]$Label) {
    $map = @{}
    Get-ChildItem -Path $SourceDir -Recurse -File -Filter "*.ttf" | ForEach-Object {
        $name = $_.BaseName
        $page = $null
        if ($name -match '(?i)^p0*([1-9][0-9]{0,2})(?:\D.*)?$') {
            $page = [int]$Matches[1]
        }
        elseif ($name -match '(?i)(?:^|\D)([1-9][0-9]{0,2})(?:\D|$)') {
            $candidate = [int]$Matches[1]
            if ($candidate -ge 1 -and $candidate -le 604) { $page = $candidate }
        }
        if ($null -ne $page -and $page -ge 1 -and $page -le 604 -and -not $map.ContainsKey($page)) {
            $map[$page] = $_.FullName
        }
    }

    if ($map.Count -ne 604) { throw "$Label archive contained $($map.Count)/604 recognizable page fonts." }
    for ($page=1; $page -le 604; $page++) {
        Copy-Item -LiteralPath $map[$page] -Destination (Join-Path $Destination "p$page.ttf") -Force
    }
    Write-Host "$Label installed: 604/604"
}

function Find-MushafLayoutDir([string]$SourceDir) {
    # Never recursively pick arbitrary page-*.json files. Use the repository's
    # canonical /mushaf folder only, which contains page-001..page-604.
    $candidates = Get-ChildItem -Path $SourceDir -Recurse -Directory | Where-Object {
        $_.Name -eq "mushaf" -and
        (Test-Path -LiteralPath (Join-Path $_.FullName "page-001.json")) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName "page-604.json"))
    }
    $dir = $candidates | Select-Object -First 1
    if ($null -eq $dir) { throw "Canonical mushaf layout folder was not found in GitHub archive." }
    return $dir.FullName
}

function Install-Layouts([string]$SourceDir) {
    $MushafDir = Find-MushafLayoutDir $SourceDir
    for ($page=1; $page -le 604; $page++) {
        $padded = $page.ToString("000")
        $source = Join-Path $MushafDir "page-$padded.json"
        if (-not (Test-Path -LiteralPath $source)) { throw "Missing layout in archive: page-$padded.json" }
        Copy-Item -LiteralPath $source -Destination (Join-Path $LayoutDest "page-$padded.json") -Force
    }
    Write-Host "Layouts installed from canonical /mushaf directory: 604/604"
}

Write-Host "Quran7Hours offline Mushaf installer"
Write-Host "Assets: $AssetsRoot"
Write-Host "Downloads: V4 ZIP + V2 ZIP + canonical mushaf-layout ZIP."
Write-Host ""

# Font bundle endpoints published by Al Furqan/Quran Foundation integration docs.
Get-Archive "https://alfurqan.online/api/v1/fonts/qcf-v4.zip" $V4Zip
Get-Archive "https://alfurqan.online/api/v1/fonts/qcf-v2.zip" $V2Zip
# codeload is a direct static ZIP endpoint and avoids github.com archive response quirks.
Get-Archive "https://codeload.github.com/zonetecde/mushaf-layout/zip/refs/heads/main" $LayoutZip

$V4Extract = Expand-Clean $V4Zip "v4"
$V2Extract = Expand-Clean $V2Zip "v2"
$LayoutExtract = Expand-Clean $LayoutZip "layout"

Install-FontSet $V4Extract $V4Dest "QPC/QCF V4 Tajweed"
Install-FontSet $V2Extract $V2Dest "QPC/QCF V2 plain"
Install-Layouts $LayoutExtract

$audit = Invoke-Audit
if ($audit -ne 0) { throw "Offline pack audit failed after installation." }
Write-ReadyMarker

if (-not $KeepArchives) {
    if (Test-Path $Extract) { Remove-Item -Recurse -Force $Extract }
    Write-Host "Downloaded ZIPs remain cached in $Cache for future repair."
}

Write-Host ""
Write-Host "READY: 604 layouts + 604 V4 + 604 V2 are packaged locally."
Write-Host "Now rebuild/reinstall the APK; no Quran asset download happens on the phone."
