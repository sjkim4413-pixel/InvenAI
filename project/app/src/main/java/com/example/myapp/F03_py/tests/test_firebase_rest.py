import base64
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import f03_api as api
import f03_firebase_rest as firebase


class FirebaseRestUpload(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.enterContext(patch.dict('os.environ', {'IVENAI_F04_DIR': str(self.root / 'missing-f04')}))
        self.enterContext(patch.object(api.app.state, 'upload_dir', self.root / 'uploads'))
        self.enterContext(patch.object(firebase, 'firebase_config', return_value=('test-project', 'test-key')))
        self.client = TestClient(api.app)
        self.requests = []
        self.auth_status = 200
        self.auth_body = {'users': [{'localId': 'signed-user', 'validSince': '100'}]}
        self.profile_status = 200
        self.profile_body = {'fields': {'companyCode': {'stringValue': 'ABC-002'}}}
        self.inventory_company = 'ABC-002'
        claims = {'sub': 'signed-user', 'aud': 'test-project',
                  'iss': 'https://securetoken.google.com/test-project', 'iat': 200}
        encoded = base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip('=')
        self.token = f'header.{encoded}.signature'
        self.headers = {'Authorization': f'Bearer {self.token}'}
        rest = self.enterContext(httpx.Client(transport=httpx.MockTransport(self.respond)))
        self.enterContext(patch.object(firebase, '_client', rest))

    def respond(self, request):
        self.requests.append(request.url.path)
        if request.url.path == '/v1/accounts:lookup':
            self.assertEqual(request.headers['X-Goog-Api-Key'], 'test-key')
            self.assertEqual(json.loads(request.content), {'idToken': self.token})
            return httpx.Response(self.auth_status, json=self.auth_body)
        self.assertEqual(request.headers['Authorization'], self.headers['Authorization'])
        if request.url.path.endswith('/users/signed-user'):
            return httpx.Response(self.profile_status, json=self.profile_body)
        self.assertTrue(request.url.path.endswith('/documents:runQuery'))
        query = json.loads(request.content)['structuredQuery']
        self.assertEqual(query['where']['fieldFilter']['value']['stringValue'], 'ABC-002')
        fields = {k: {'stringValue': v} for k, v in {
            'companyCode': self.inventory_company, 'warehouse': 'AI 이미지 테스트 창고',
            'location': 'AI01구역 P01-5단', 'itemCode': 'SIM-AI01-P01', 'status': 'NORMAL',
        }.items()}
        fields['quantity'] = {'integerValue': '1'}
        return httpx.Response(200, json=[{'document': {'name': 'inventory/fixture', 'fields': fields}}])

    def upload(self):
        data = io.BytesIO()
        Image.new('RGB', (120, 120)).save(data, 'PNG')
        return self.client.post('/f03/audit/upload-image', headers=self.headers,
                                files={'image': ('test.png', data.getvalue(), 'image/png')})

    def test_login_allows_upload_and_scopes_inventory_to_verified_company(self):
        self.assertEqual(self.upload().status_code, 200)
        response = self.client.get('/f03/audit/live/context', params={'warehouse_id': 1}, headers=self.headers)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['company_code'], 'ABC-002')
        self.assertEqual(response.json()['inventory_source_count'], 1)
        self.inventory_company = 'OTHER'
        response = self.client.get('/f03/audit/live/context', params={'warehouse_id': 1}, headers=self.headers)
        self.assertEqual(response.status_code, 503)
        self.assertNotIn('SIM-AI01-P01', response.text)

    def test_invalid_or_revoked_token_is_rejected_before_profile_lookup(self):
        self.auth_status = 400
        self.auth_body = {'error': {'message': 'INVALID_ID_TOKEN'}}
        self.assertEqual(self.upload().status_code, 401)
        self.auth_status = 200
        self.auth_body = {'users': [{'localId': 'signed-user', 'validSince': '201'}]}
        self.assertEqual(self.upload().status_code, 401)
        self.assertEqual(self.requests, ['/v1/accounts:lookup'] * 2)
        self.assertFalse(list(self.root.rglob('*.upload.json')))

    def test_profile_denial_returns_403_before_upload(self):
        self.profile_status = 403
        self.profile_body = {'error': {'message': 'PERMISSION_DENIED'}}
        self.assertEqual(self.upload().status_code, 403)
        self.assertFalse(list(self.root.rglob('*.upload.json')))


if __name__ == '__main__':
    unittest.main()
