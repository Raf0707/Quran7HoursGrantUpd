param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$ErrorActionPreference = "Stop"
$assets = Join-Path $ProjectRoot "app\src\main\assets\quran\qpc-v4\qul"
$cache = Join-Path $ProjectRoot ".quran-offline-pack-cache"
$zipPath = Join-Path $cache "KFGQPC_V4_layout.zip"
$extract = Join-Path $cache "KFGQPC_V4_layout_extract"
$target = Join-Path $assets "quran_pages.json"
$marker = Join-Path $assets "QUL_LAYOUT_READY.txt"
$url = "https://github.com/SakinaDevGroup/KFGQPC_V4_tajweed/raw/refs/heads/main/KFGQPC_V4_layout.zip"

New-Item -ItemType Directory -Force -Path $assets | Out-Null
New-Item -ItemType Directory -Force -Path $cache | Out-Null

function Test-QulLayout([string]$Path) {
    if (-not (Test-Path $Path -PathType Leaf)) { return $false }
    $file = Get-Item $Path
    if ($file.Length -lt 1000000) { return $false }
    $text = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    $matches = [regex]::Matches($text, '"page_number"\s*:\s*(\d+)')
    $pages = New-Object 'System.Collections.Generic.HashSet[int]'
    foreach ($m in $matches) { [void]$pages.Add([int]$m.Groups[1].Value) }
    if ($pages.Count -ne 604) { return $false }
    for ($p = 1; $p -le 604; $p++) {
        if (-not $pages.Contains($p)) { return $false }
    }
    return $true
}

if (Test-QulLayout $target) {
    Write-Host "QUL/KFGQPC V4 layout is already installed: 604/604 pages." -ForegroundColor Green
    Set-Content -Encoding UTF8 $marker "QUL layout id 19 / KFGQPC V4 1441H`npages=604`nsource=qul.tarteel.ai/mushaf_layouts/19"
    exit 0
}

if (-not (Test-Path $zipPath -PathType Leaf) -or (Get-Item $zipPath).Length -lt 10000000) {
    Write-Host "Downloading one complete KFGQPC V4 layout archive (~49 MB)..."
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        & curl.exe -L --fail --retry 4 --retry-delay 2 -o $zipPath $url
        if ($LASTEXITCODE -ne 0) { throw "curl.exe failed with exit code $LASTEXITCODE" }
    } else {
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zipPath
    }
} else {
    Write-Host "Using cached archive: $zipPath"
}

if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
New-Item -ItemType Directory -Force -Path $extract | Out-Null
Expand-Archive -Path $zipPath -DestinationPath $extract -Force

$candidates = Get-ChildItem -Path $extract -Filter "quran_pages.json" -Recurse -File
if ($candidates.Count -eq 0) { throw "quran_pages.json was not found in the downloaded archive." }
$source = $candidates | Where-Object { $_.FullName -match '[\\/]script[\\/]quran_pages\.json$' } | Select-Object -First 1
if ($null -eq $source) { $source = $candidates | Select-Object -First 1 }
Copy-Item -Force $source.FullName $target

if (-not (Test-QulLayout $target)) {
    throw "Installed quran_pages.json failed the 604-page structural check."
}

Set-Content -Encoding UTF8 $marker "QUL layout id 19 / KFGQPC V4 1441H`npages=604`nsource=qul.tarteel.ai/mushaf_layouts/19"
Write-Host "READY: official QUL/KFGQPC V4 physical layout is installed: 604/604 pages." -ForegroundColor Green
Write-Host "Asset: $target"
Write-Host "The Android app uses it fully offline; Gradle does not download anything."
