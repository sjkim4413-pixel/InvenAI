# F03 photo recognition and local ERP comparison

Whole-photo analysis now runs English OCR on original-resolution object/slot crops. It returns actual text, confidence and normalized coordinates. Korean Windows paths and EXIF rotation are supported. OCR runs even if YOLO finds no objects. OCR errors are reported as analysis failures rather than successful empty inventories.

Revision 2026-11-04.7 also reads a whole-photo overview on every call, so a missed object can still contribute visible product text. Detections are compared at 640, 1024 and 1280 input sizes. A fused detection keeps an actual highest-confidence box and score; it does not average coordinates, inflate confidence, or merge two detections from the same inference. Automatic verification additionally requires the object to be supported at two or more sizes. Single-size and ambiguous extra candidates remain review cases. OCR phrases used for verification must be contained in the actual object, and a stronger crop reading replaces a weaker duplicate overview reading.

Product identities come from a server-owned catalog, independent of the expected ERP row. Exact printed codes are supported. Each alias requires all listed phrases. A code-based PASS requires one unambiguous object, OCR >= 0.85, YOLO >= 0.50, verified spatial containment, an exact expected inventory and a confirmed location. Ambiguity and insufficient evidence require review.

The default `config/product_catalog.json` and `config/location_map.json` are empty. Populate them with real SKU/model identifiers and operator-reviewed location mappings before real inventory use. Merely supplying a rack query does not verify its location. The API does not accept client-supplied verification flags. Map bindings require the exact photo hash, upright dimensions, authenticated company and warehouse. Catalog and map revisions invalidate cached analysis.

## Included Traders test profile

`config/traders_demo` contains the user-authorized synthetic ERP, 12 virtual packaging-family codes and 12 manually reviewed regions in four unmodified source photographs. The location map contains no expected SKU. Product aliases are matched against actual OCR pixels; changing ERP expectations cannot change the recognized product. This profile does not establish manufacturer SKU, serial number, box quantity, receipt history or real rack/tier identity.

Run `./run_traders_demo.ps1 -CompanyCode YOUR_FIREBASE_COMPANY -WarehouseId 1` from this backend directory and pass `mode=simulation` to the analysis API to use that profile. The script keeps test uploads separate, binds the reviewed test map to that company/warehouse, and requires normal Firebase login. The API listens locally on port 8082.

For real configuration, set `IVENAI_PRODUCT_CATALOG`, `IVENAI_LOCATION_MAP`, and `IVENAI_ERP_PATH` to server-owned files. Catalog schema: `version:1`, `products:[{sku, aliases:[[required phrase, ...], ...]}]`. Map schema: `version:1`, `company_code`, `warehouse_id`, `images:[{sha256:[...],width,height,regions:[{rack,floor,bbox:[x1,y1,x2,y2]}]}]`. Map regions must not overlap. Include only independently reviewed locations; do not derive the observed SKU from the expected ERP product.

The Android review screen displays observed text, observed products, ERP expectations and the test profile label. Green boxes require a final ERP PASS. Product mismatches are red and review cases are amber. English OCR weights are installed locally; Korean OCR is not enabled or implicitly downloaded.

The live analysis and F04 review routes use Firestore by default. Pass `mode=simulation` to select a local test profile. Set `IVENAI_F04_DIR` when using an external F04 installation.

In simulation mode for ABC-002 and warehouse ID 1, exact `test_image_ai` photo hashes select the bundled location map and product catalog. F04 reads expected items from the Firestore warehouse `AI 이미지 테스트 창고`. Set `IVENAI_TEST_IMAGE_DIR` if the test folder is deployed outside the project root. A match confirms the product and location. Quantity requires a separate check.

`test_image_ai/visual_references.json` registers package-only crops from the four test images. F03 compares each verified location's pixels with every registered crop. It can localize a package by matching the shape and color of a large, distinctive pattern entirely within the mapped area, or corroborate a visual match with one stable YOLO detection. F04 compares the observed test packaging code with the independent Firestore record. The screen labels matches from this method as test appearance and location matches. These references qualify the bundled test packaging. Real SKU verification continues to use product codes and the configured identity workflow.
