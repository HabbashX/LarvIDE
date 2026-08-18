Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = "Stop"

function New-LarvBitmap([int]$size, [float]$scale, [float]$offsetFrac) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias

    $S = $size / 1080.0 * $scale
    $off = $size * $offsetFrac

    # Blob background gradient (orange)
    $rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, `
        [System.Drawing.Color]::FromArgb(255, 251, 167, 30), `
        [System.Drawing.Color]::FromArgb(255, 255, 203, 125), `
        [System.Drawing.Drawing2D.LinearGradientMode]::Vertical)
    $g.FillRectangle($grad, $rect)

    # Black square: x=258 y=175 w=597 h=597
    $black = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 10, 10, 10))
    $sqX = 258 * $S + $off
    $sqY = 175 * $S + $off
    $sqW = 597 * $S
    $sqH = 597 * $S
    $g.FillRectangle($black, $sqX, $sqY, $sqW, $sqH)

    # "Larv" text - font-weight 800 (ExtraBold), size 205, baseline at y=480
    $fontSize = 205 * $S
    $font = New-Object System.Drawing.Font("Arial", $fontSize, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $ff = $font.FontFamily
    $ascent = $ff.GetCellAscent([System.Drawing.FontStyle]::Bold)
    $em = $ff.GetEmHeight([System.Drawing.FontStyle]::Bold)
    $ascentPx = $fontSize * $ascent / $em
    $baseline = 480 * $S + $off
    $textTop = $baseline - $ascentPx
    $white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $g.DrawString("Larv", $font, $white, 290 * $S + $off, $textTop)

    # Underscore bar: x=292 y=605 w=300 h=40
    $g.FillRectangle($white, 292 * $S + $off, 605 * $S + $off, 300 * $S, 40 * $S)

    $g.Dispose()
    return $bmp
}

$sizes = @{
    "mdpi"   = 48
    "hdpi"   = 72
    "xhdpi"  = 96
    "xxhdpi" = 144
    "xxxhdpi" = 192
}

foreach ($k in $sizes.Keys) {
    $size = $sizes[$k]
    $bmp = New-LarvBitmap $size 1.0 0.0
    $path = "app\src\main\res\mipmap-$k\ic_launcher.png"
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "Wrote $path"
}

# Adaptive icon foreground: content scaled to 0.66 and centered (safe zone)
$bmp = New-LarvBitmap 432 0.6 0.2
$path = "app\src\main\res\drawable-nodpi\ic_launcher_foreground.png"
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "Wrote $path"