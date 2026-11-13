param(
    [Parameter(Mandatory=$true)][string]$CompanyCode,
    [int]$WarehouseId = 1,
    [int]$Port = 8082
)
$ErrorActionPreference = 'Stop'
if ($WarehouseId -lt 1) { throw 'WarehouseId must be positive.' }
$profileRoot = Join-Path $PSScriptRoot 'config\traders_demo'
$runtimeRoot = Join-Path $PSScriptRoot 'audit_demo_runtime'
New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
$locationMap = Get-Content -LiteralPath (Join-Path $profileRoot 'location_map.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$locationMap.company_code = $CompanyCode.Trim().ToUpperInvariant()
$locationMap.warehouse_id = $WarehouseId
$runtimeMap = Join-Path $runtimeRoot 'location_map.json'
$locationMap | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $runtimeMap -Encoding UTF8
$env:IVENAI_PRODUCT_CATALOG = Join-Path $profileRoot 'product_catalog.json'
$env:IVENAI_LOCATION_MAP = $runtimeMap
$env:IVENAI_ERP_PATH = Join-Path $profileRoot 'erp.csv'
$env:IVENAI_UPLOAD_DIR = Join-Path $runtimeRoot 'uploads'
$env:YOLO_AUTOINSTALL = 'false'
Write-Host 'TRADERS TEST ERP: virtual packaging codes and 12 reviewed regions in 4 original photos.'
Write-Host "Company: $($locationMap.company_code), Warehouse: $WarehouseId. Firebase login is required."
Push-Location $PSScriptRoot
try { & (Join-Path $PSScriptRoot '.venv\Scripts\python.exe') -m uvicorn f03_api:app --host 0.0.0.0 --port $Port }
finally { Pop-Location }
