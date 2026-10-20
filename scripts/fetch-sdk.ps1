# Fetches the WearTAK plugin SDK (.aar) from the TAK.gov plugin template into app/libs.
param([string]$Ref = "master")
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("weartak-sdk-" + [guid]::NewGuid())
try {
    git clone --quiet --depth 1 --branch $Ref --filter=blob:none --sparse `
        https://git.tak.gov/weartak-plugins/weartak-plugin-template.git $tmp
    if ($LASTEXITCODE -ne 0) { throw "Clone failed - check git.tak.gov access." }
    git -C $tmp sparse-checkout set app/libs
    if ($LASTEXITCODE -ne 0) { throw "Sparse checkout failed." }
    $dest = Join-Path $root "app\libs"
    New-Item -ItemType Directory -Force $dest | Out-Null
    Copy-Item (Join-Path $tmp "app\libs\weartak-plugin-sdk-*.aar") $dest -Force
    Get-ChildItem $dest -Filter *.aar | ForEach-Object { "Fetched $($_.Name)" }
} finally {
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
}