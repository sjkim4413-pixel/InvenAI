import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import f03_fixture_inventory as fixtures
from f03_catalog import AuditConfigError
from f03_live_inventory import build_snapshot


class AdditionalFixtureManifest(unittest.TestCase):
    def test_new_photos_select_only_their_own_registered_test_positions(self):
        folder = fixtures.DEFAULT_FIXTURE_DIR.parent / 'test_photos/rack_labelled'
        plan = json.loads((folder / 'extra_inventory_plan.json').read_text(encoding='utf-8'))
        stock = build_snapshot('ABC-002', [(row['id'], row['data']) for row in plan['records']])
        self.assertEqual(stock['issues'], [])
        for name, expected in (('QAE_rack_labels.png', 12), ('QAF_rack_labels.png', 10), ('QAG_rack_labels.png', 4)):
            with self.subTest(photo=name):
                content = (fixtures.DEFAULT_FIXTURE_DIR / name).read_bytes()
                self.assertLessEqual(len(content), 16 * 1024 * 1024)
                self.assertEqual(content, (folder / name).read_bytes())
                upload = {'source_sha256': hashlib.sha256(content).hexdigest(), 'sha256': 'normalized-jpeg'}
                warehouse, positions, codes = fixtures.fixture_rack_codes(upload, stock, 1)
                self.assertEqual(warehouse, fixtures.ADDITIONAL_WAREHOUSE)
                self.assertEqual(len(positions), expected)
                self.assertEqual(len(codes), expected)
                self.assertTrue(all(code.startswith(name[:3]) for code in codes.values()))

    def test_photo_filename_cannot_authorize_an_unregistered_image(self):
        self.assertIsNone(fixtures.additional_fixture_photo(
            {'source_sha256': 'f' * 64, 'filename': 'QAE_rack_labels.png'}, 'ABC-002', 1))

    def test_registered_photo_is_scoped_to_its_company_and_warehouse_id(self):
        manifest = json.loads((fixtures.DEFAULT_FIXTURE_DIR / 'rack_labelled_photos.json').read_text(encoding='utf-8'))
        upload = {'source_sha256': manifest['QAE_rack_labels.png']}
        self.assertIsNone(fixtures.additional_fixture_photo(upload, 'ABC-001', 1))
        self.assertIsNone(fixtures.additional_fixture_photo(upload, 'ABC-002', 2))

    def test_manifest_rejects_paths_malformed_hashes_and_duplicate_digests(self):
        invalid = [
            {'../QAE_rack_labels.png': 'a' * 64},
            {'QAE_rack_labels.png': 'not-a-hash'},
            {'QAE_rack_labels.png': 'a' * 64, 'QAF_rack_labels.png': 'a' * 64},
        ]
        with tempfile.TemporaryDirectory() as temporary, patch.object(fixtures, 'FIXTURE_DIR', Path(temporary)):
            for manifest in invalid:
                with self.subTest(manifest=manifest):
                    (Path(temporary) / 'rack_labelled_photos.json').write_text(json.dumps(manifest), encoding='utf-8')
                    with self.assertRaises(AuditConfigError):
                        fixtures.additional_fixture_photo({'source_sha256': 'a' * 64}, 'ABC-002', 1)


if __name__ == '__main__':
    unittest.main()
