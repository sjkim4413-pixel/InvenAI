"""Read Firebase data with the caller's ID token and existing security rules."""
import base64
import json
import os
import re
from pathlib import Path
from urllib.parse import quote

import httpx

_AUTH_URL = 'https://identitytoolkit.googleapis.com/v1/accounts:lookup'
_INVALID_TOKEN = {'INVALID_ID_TOKEN', 'TOKEN_EXPIRED', 'USER_DISABLED', 'USER_NOT_FOUND',
                  'PROJECT_NUMBER_MISMATCH', 'INVALID_REFRESH_TOKEN'}
_INVENTORY_FIELDS = ('companyCode', 'warehouse', 'zone', 'rack', 'rackId', 'tier',
                     'location', 'itemCode', 'itemName', 'quantity', 'status', 'isTestData',
                     'dataSource', 'photoReference', 'alternatePhotoReference', 'photoLocationLabel', 'photoLabelIssue')
_client = httpx.Client(timeout=10.)


class FirebaseAccessError(RuntimeError):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def firebase_config():
    configured = os.environ.get('IVENAI_FIREBASE_CONFIG')
    path = Path(configured) if configured else next(
        (directory / 'google-services.json' for directory in Path(__file__).resolve().parents
         if (directory / 'google-services.json').is_file()), None,
    )
    if path is None:
        raise FirebaseAccessError(503, 'F03 서버에서 Firebase 앱 설정 파일을 찾을 수 없습니다.')
    try:
        config = json.loads(path.read_text(encoding='utf-8-sig'))
        project = config['project_info']['project_id']
        app = next(entry for entry in config['client']
                   if entry['client_info'].get('android_client_info', {}).get('package_name') == 'com.example.myapp')
        key = app['api_key'][0]['current_key']
        if not re.fullmatch(r'[a-z][a-z0-9-]{1,62}', project) or not isinstance(key, str) or not key.strip():
            raise ValueError('Invalid Firebase app configuration')
    except (OSError, ValueError, KeyError, IndexError, TypeError, AttributeError, StopIteration) as exc:
        raise FirebaseAccessError(503, 'F03 서버의 Firebase 앱 설정 파일을 확인해주세요.') from exc
    return project, key


def document_url(project):
    return f'https://firestore.googleapis.com/v1/projects/{project}/databases/(default)/documents'


def firebase_request(method, url, **kwargs):
    try:
        response = _client.request(method, url, **kwargs)
        return response.status_code, response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise FirebaseAccessError(503, 'F03 서버에서 Firebase에 연결하지 못했습니다.') from exc


def token_headers(token):
    return {'Authorization': f'Bearer {token}'}


def matches_account(token, project, uid, account):
    # Firebase has already validated the token in accounts:lookup. Use its
    # accepted claims to enforce this project's audience and revocation time.
    try:
        payload = token.split('.')[1]
        claims = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
        issued_at = claims.get('iat')
        return (claims.get('aud') == project and claims.get('iss') == f'https://securetoken.google.com/{project}'
                and claims.get('sub') == uid and type(issued_at) is int
                and issued_at >= int(account.get('validSince') or 0))
    except (ValueError, IndexError, TypeError, AttributeError):
        return False


def lookup_profile(token):
    project, key = firebase_config()
    status, response = firebase_request('POST', _AUTH_URL, headers={'X-Goog-Api-Key': key},
                                        json={'idToken': token})
    if status != 200 or not isinstance(response, dict):
        error = response.get('error') if isinstance(response, dict) else None
        reason = str(error.get('message', '')).partition(':')[0].strip() if isinstance(error, dict) else ''
        if status == 401 or reason in _INVALID_TOKEN:
            raise FirebaseAccessError(401, 'Firebase 로그인이 필요합니다.')
        raise FirebaseAccessError(503, 'Firebase 인증 서버가 요청을 처리하지 못했습니다.')
    accounts = response.get('users')
    account = accounts[0] if isinstance(accounts, list) and len(accounts) == 1 else None
    uid = account.get('localId') if isinstance(account, dict) else None
    if (not isinstance(uid, str) or not uid.strip() or len(uid) > 128 or uid in {'.', '..'} or '/' in uid
            or account.get('disabled') is True or not matches_account(token, project, uid, account)):
        raise FirebaseAccessError(401, 'Firebase 로그인이 필요합니다.')
    status, profile = firebase_request('GET', f'{document_url(project)}/users/{quote(uid, safe="")}',
                                       headers=token_headers(token), params={'mask.fieldPaths': 'companyCode'})
    if status == 401:
        raise FirebaseAccessError(401, 'Firebase 로그인이 필요합니다.')
    if status in {403, 404}:
        raise FirebaseAccessError(403, 'Firebase 사용자 프로필의 회사 정보를 확인할 수 없습니다.')
    if status != 200 or not isinstance(profile, dict):
        raise FirebaseAccessError(503, 'Firebase 사용자 프로필을 읽지 못했습니다.')
    fields = profile.get('fields')
    company_field = fields.get('companyCode') if isinstance(fields, dict) else None
    company = company_field.get('stringValue') if isinstance(company_field, dict) else None
    if not isinstance(company, str) or not company.strip() or company.strip().upper() == 'DEFAULT':
        raise FirebaseAccessError(403, 'Firebase 사용자 프로필의 회사 정보를 확인할 수 없습니다.')
    return uid, company.strip()


def read_inventory(token, company, warehouse, limit):
    project, _ = firebase_config()
    filters = [{'fieldFilter': {'field': {'fieldPath': 'companyCode'}, 'op': 'EQUAL',
                                'value': {'stringValue': company}}}]
    if warehouse is not None:
        filters.append({'fieldFilter': {'field': {'fieldPath': 'warehouse'}, 'op': 'EQUAL',
                                       'value': {'stringValue': warehouse}}})
    status, response = firebase_request('POST', document_url(project) + ':runQuery',
                                       headers=token_headers(token), timeout=25., json={
        'structuredQuery': {
            'from': [{'collectionId': 'inventory'}],
            'select': {'fields': [{'fieldPath': field} for field in _INVENTORY_FIELDS]},
            'where': filters[0] if len(filters) == 1 else {'compositeFilter': {'op': 'AND', 'filters': filters}},
            'limit': limit,
        },
    })
    if status != 200 or not isinstance(response, list):
        raise FirebaseAccessError(status if status != 200 else 503, 'Firebase에서 회사 재고를 읽지 못했습니다.')
    documents = []
    for row in response:
        if 'error' in row:
            raise FirebaseAccessError(503, 'Firebase가 재고 조회를 완료하지 못했습니다.')
        if 'document' not in row:
            continue
        document = row['document']
        fields = {}
        for key, value in document['fields'].items():
            if 'integerValue' in value:
                fields[key] = int(value['integerValue'])
            elif 'booleanValue' in value:
                fields[key] = value['booleanValue']
            else:
                fields[key] = value.get('stringValue', value.get('doubleValue'))
        if fields.get('companyCode') != company or (warehouse is not None and fields.get('warehouse') != warehouse):
            raise FirebaseAccessError(503, '조회 범위를 벗어난 재고 데이터가 반환됐습니다.')
        documents.append((document['name'].rsplit('/', 1)[1], fields))
    return documents
