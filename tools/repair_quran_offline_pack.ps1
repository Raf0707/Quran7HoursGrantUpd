param(
    [switch]$ForceDownload,
    [switch]$KeepArchives
)
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AssetsRoot = Join-Path $ProjectRoot "app/src/main/assets/quran/qpc-v4"

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

Write-Host "Checking complete 604-page QPC pack..."
& (Join-Path $PSScriptRoot "audit_quran_offline_pack.ps1")
if ($LASTEXITCODE -eq 0 -and -not $ForceDownload) {
    Write-ReadyMarker
    Write-Host "Nothing to repair. All 1812 mandatory assets are valid. Ready marker refreshed."
    exit 0
}

Write-Host ""
Write-Host "Repair required. Installing complete offline pack..."
$args = @()
if ($ForceDownload) { $args += "-ForceDownload" }
if ($KeepArchives) { $args += "-KeepArchives" }
& (Join-Path $PSScriptRoot "install_quran_offline_pack.ps1") @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& (Join-Path $PSScriptRoot "audit_quran_offline_pack.ps1")
if ($LASTEXITCODE -eq 0) { Write-ReadyMarker }
exit $LASTEXITCODE
