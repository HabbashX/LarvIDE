# Generates the legacy (pre-adaptive-icon) launcher PNGs from design/app-icon.svg.
#
# The SVG in design/ is the single source of truth: the adaptive icon vectors in
# app/src/main/res/drawable/ mirror it. Devices on API 26+ use the adaptive
# icon (mipmap-anydpi-v26); API 24-25 launchers need raster PNGs, so they are
# rendered here with headless Chrome and downscaled with GDI+.
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts/gen_icons.ps1
#
# Requires Chrome or Edge (any Chromium 109+ supports --headless=new).

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$svg = Join-Path $repo "design\app-icon.svg"
if (-not (Test-Path $svg)) { throw "Missing master artwork: $svg" }

$renderDir = Join-Path $repo "build\icon-render"
New-Item -ItemType Directory -Force -Path $renderDir | Out-Null
$master = Join-Path $renderDir "icon-master.png"

# ---------------------------------------------------------------- render
$browsers = @(
    "C:\Program Files\Google\Chrome\Application\chrome.exe",
    "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "C:\Program Files\Microsoft\Edge\Application\msedge.exe"
)
$chrome = $browsers | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $chrome) {
    throw "Chrome or Edge not found. Install a Chromium browser or render design/app-icon.svg manually."
}

$uri = "file:///" + ($svg -replace '\\', '/')
# Chrome logs harmless registry warnings to stderr; discard it and verify the file instead.
$ErrorActionPreference = "Continue"
& $chrome --headless=new --disable-gpu --hide-scrollbars `
    --force-device-scale-factor=1 --window-size=512,512 `
    --default-background-color=00000000 --screenshot=$master $uri 2>$null | Out-Null
$ErrorActionPreference = "Stop"

if (-not (Test-Path $master)) { throw "Headless render produced no file: $master" }

$src = [System.Drawing.Image]::FromFile($master)
if ($src.Width -ne 512 -or $src.Height -ne 512) {
    throw "Unexpected render size $($src.Width)x$($src.Height) (expected 512x512)"
}

# ---------------------------------------------------------------- helpers
function New-IconBitmap([System.Drawing.Image]$source, [int]$size, [bool]$round) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    if ($round) {
        $clip = New-Object System.Drawing.Drawing2D.GraphicsPath
        $clip.AddEllipse(0, 0, $size, $size)
        $g.SetClip($clip)
    }

    $rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $g.DrawImage($source, $rect, 0, 0, $source.Width, $source.Height,
        [System.Drawing.GraphicsUnit]::Pixel)

    if ($round) { $g.ResetClip() }
    $g.Dispose()
    return $bmp
}

# ---------------------------------------------------------------- output
$sizes = [ordered]@{
    "mdpi"    = 48
    "hdpi"    = 72
    "xhdpi"   = 96
    "xxhdpi"  = 144
    "xxxhdpi" = 192
}

foreach ($density in $sizes.Keys) {
    $size = $sizes[$density]
    $dir = Join-Path $repo "app\src\main\res\mipmap-$density"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $square = New-IconBitmap $src $size $false
    $squarePath = Join-Path $dir "ic_launcher.png"
    $square.Save($squarePath, [System.Drawing.Imaging.ImageFormat]::Png)
    $square.Dispose()

    $roundBmp = New-IconBitmap $src $size $true
    $roundPath = Join-Path $dir "ic_launcher_round.png"
    $roundBmp.Save($roundPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $roundBmp.Dispose()

    Write-Output ("Wrote mipmap-{0}/ic_launcher.png + ic_launcher_round.png ({1}px)" -f $density, $size)
}

$src.Dispose()
Write-Output "Done. Source: design/app-icon.svg"
