param([Parameter(Mandatory=$true)][string]$GeneratedImagePath)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectPath = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$targetPath = Join-Path $projectPath 'test_image_ai/276ec63e-e08f-4915-b585-582e9529bdaa.png'
$backupPath = Join-Path $PSScriptRoot '276ec63e-before-A02-5F.png'
$outputPath = Join-Path $PSScriptRoot '276ec63e-with-A02-5F.png'
$originalHash = '84a830553e3b86a7d8c361703da3635d5bd1d15f6d624e8816e53472fd285135'
if ((Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $originalHash) {
    throw 'The source photo differs from the reviewed original. Do not apply twice.'
}
if (-not (Test-Path -LiteralPath $backupPath)) { Copy-Item -LiteralPath $targetPath -Destination $backupPath }
if ((Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $originalHash) {
    throw 'Original backup does not match.'
}
$originalImage = [System.Drawing.Bitmap]::new($targetPath)
$generatedImage = [System.Drawing.Bitmap]::new($GeneratedImagePath)
$editedImage = $null
$graphics = $null
try {
    if ($originalImage.Width -ne 1086 -or $originalImage.Height -ne 1448 -or
        $generatedImage.Width -ne 1086 -or $generatedImage.Height -ne 1448) { throw 'Unexpected dimensions' }
    $editedImage = $originalImage.Clone()
    $graphics = [System.Drawing.Graphics]::FromImage($editedImage)
    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    # Integrate only the AI-edited sticker area; keep every outside pixel intact.
    $sticker = [System.Drawing.Rectangle]::new(272,380,84,38)
    $graphics.DrawImage($generatedImage,$sticker,$sticker,[System.Drawing.GraphicsUnit]::Pixel)
    $graphics.Dispose()
    $graphics = $null
    $editedImage.Save($outputPath,[System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    if ($null -ne $graphics) { $graphics.Dispose() }
    if ($null -ne $editedImage) { $editedImage.Dispose() }
    $originalImage.Dispose()
    $generatedImage.Dispose()
}
Copy-Item -LiteralPath $outputPath -Destination $targetPath -Force
[PSCustomObject]@{
    original_sha256=$originalHash
    edited_sha256=(Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash.ToLowerInvariant()
    changed_region=@(272,380,356,418)
    label='A02-5F'
    method='Built-in imagegen edit; integrate generated sticker region only to preserve other labels'
    prompt='Add a clear 72x30 pixel A02-5F label beneath the top row second pallet; preserve all other content.'
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'A02-5F-edit.json') -Encoding utf8
