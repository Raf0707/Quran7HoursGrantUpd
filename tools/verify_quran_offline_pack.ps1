$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AssetsRoot = Join-Path $ProjectRoot "app/src/main/assets/quran/qpc-v4"

& (Join-Path $PSScriptRoot "audit_quran_offline_pack.ps1")
$code = $LASTEXITCODE
if ($code -eq 0) {
    $Marker = Join-Path $AssetsRoot "OFFLINE_ASSETS_READY.txt"
    @"
Quran7Hours complete offline QPC pack
layouts=604
v4_tajweed_fonts=604
v2_plain_fonts=604
verified_utc=$([DateTime]::UtcNow.ToString("o"))
"@ | Set-Content -LiteralPath $Marker -Encoding UTF8
    Write-Host "Ready marker written: $Marker"
}
exit $code
