# Actual product codes and warehouse locations

The Android photo-analysis path uses `POST /f03/audit/analyze-and-compare` in live mode by default. It reads Firestore inventory for the exact `companyCode` of the authenticated user.

1. Photograph the load with its printed SKU on the packaging or pallet front, such as `SKU BT-200`, and the location label near the base of the load, such as `R08-1F`.
2. Select the warehouse when multiple warehouses are registered. The only available warehouse is selected automatically.
3. F03 uses OCR coordinates to associate a legible rack label with one load and one ERP rack position. It re-reads selected load and rack-label crops with zoom and local contrast when evidence is weak. It can combine adjacent rack/tier tokens and printed SKU fragments. The company catalog is used to resolve separators in split SKU text, while the ERP expectation at that location is evaluated separately. Complete SKU evidence can come from the load or from the pallet front immediately below it, when the label belongs to a single load. Duplicate location stickers, conflicting codes, ambiguous zones or sides, and OCR scores below 0.7 require review.
4. F04 compares the observed SKU with the ERP position. A different registered SKU is misplaced. A clearly printed unknown SKU is unregistered. Reanalysis uses the current inventory and retains the independent OCR observation.

The SKU reader accepts an explicitly prefixed code or a complete token that matches a registered code. An unknown code requires a visible SKU prefix. Registered QR, EAN and UPC remain available as separate evidence. A structured product QR has the format `{"ivenai":1,"kind":"product","companyCode":"YOUR-COMPANY","itemCode":"REGISTERED-SKU"}`.

The software preserves zone, rack, tier and side. A missing side stays unspecified. Conflicting location/rack fields, missing warehouse fields, invalid quantities and duplicate stock documents are reported and excluded from expected position records. No first-100-record truncation is used: all company records are read up to an explicit bound, and an exceeded bound or read failure fails the operation.

**Product/location match is distinct from complete stock verification.** A matched SKU in an operator-confirmed position is shown separately, while quantity remains unverified. One detected pallet is not equivalent to 50 inventory units. Zero-stock or existing anomaly records are not treated as normal. Firestore quantities and locations are never modified by this workflow.

## Current data boundary

Read-only discovery on 2026-11-04 found 31 inventory documents across six company codes. The stored products are electronics/accessories and placeholder loads; no product/barcode/location master for the Traders photo goods is present. A checked company scope has 11 documents, of which 4 have consistent warehouse/location records and 7 need warehouse or conflict resolution. This is a source-data finding, not a reason to relabel Traders packaging as those unrelated products.

The original 95 photos were scanned for QR/retail codes using the installed native decoders, and no valid code was decoded. The user confirmed no separate actual SKU/location data are available. Therefore the software connection can be exercised and tested, but the actual SKU/rack identity of these old wide photographs cannot be certified. Supply a true product master and verified photo-to-location data or capture legible labels at the actual site to finish that acceptance test.

The previous CSV and bundled appearance profiles require `mode=simulation` on the API. The built-in photo picker uses the same live OCR workflow as camera captures.

## API

- `GET /f03/audit/live/context?warehouse_id=...`: registered warehouses, products, positions and data-quality issues for the signed-in company.
- `POST /f03/audit/live/warehouse-binding`: `{warehouse_id, warehouse_code}`.
- `POST /f03/audit/analyze-and-compare?filename=...&warehouse_id=...` or `POST /f03/audit/live/analyze-and-compare` with the same query. Set `warehouse_code` to the chosen warehouse when selection is required.
- `POST /f03/audit/live/location-binding`: `{filename, warehouse_id, detection_id, position_id}`. Stores an explicit operator confirmation in the user's audit directory.
- `POST /f03/audit/live/identifier-binding`: `{filename, code_id, sku}`. Stores an operator-confirmed binding for a code actually decoded in that photo.

All endpoints require normal Firebase authentication and owned uploaded files. If the external F04 Firebase modules are installed, the backend reuses them. Otherwise it verifies the Android Firebase ID token against the Firebase project in `app/google-services.json`, then reads the user's company profile and company-scoped inventory with the same token under Firestore Security Rules. Set `IVENAI_FIREBASE_CONFIG` to the app config path for a standalone backend checkout. The health response reports the selected authentication backend.
