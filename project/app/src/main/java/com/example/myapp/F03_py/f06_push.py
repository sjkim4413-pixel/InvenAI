"""Authenticated device registration and idempotent FCM delivery from the existing PC server.

No client service credentials, public topics, or Firestore rule changes. FCM contains
only routing identifiers; the receiving app must read the current authorized alert.
"""
import hashlib
import logging
import os
import re
import sqlite3
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import quote

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

from f03_auth import AuditUser, require_user
from f03_firebase_rest import document_url, firebase_config, firebase_request, token_headers

router = APIRouter(prefix='/f06/push')
log = logging.getLogger(__name__)
BASE = Path(__file__).resolve().parent
TOKEN = re.compile(r'[A-Za-z0-9_:.-]{20,4096}')
DEVICE = re.compile(r'[a-f0-9]{32}')
SCOPE = 'https://www.googleapis.com/auth/firebase.messaging'


def problem(status, code, message):
    return HTTPException(status, detail=dict(code=code, message=message))


def fields_for(user, collection, document):
    project, _ = firebase_config()
    status, body = firebase_request('GET', f'{document_url(project)}/{collection}/{quote(document, safe="")}',
                                    headers=token_headers(user.firebase_id_token))
    if status == 404:
        return None
    if status in (401, 403):
        raise problem(status, 'PUSH_ACCESS_DENIED', '알림 권한을 다시 확인해 주세요.')
    if status != 200 or not isinstance(body, dict):
        raise problem(503, 'PUSH_SOURCE_UNAVAILABLE', '알림 원본을 확인하지 못했습니다.')
    result = {}
    for key, value in body.get('fields', {}).items():
        for kind in ('stringValue', 'timestampValue', 'booleanValue', 'integerValue'):
            if kind in value:
                result[key] = value[kind]
                break
    return result


def require_role(user, role):
    member = fields_for(user, 'warehouseMemberships', user.uid)
    if not member or member.get('companyCode') != user.company or member.get('role') != role:
        raise problem(403, 'PUSH_ROLE_DENIED', '알림을 처리할 계정 권한이 없습니다.')


class PushStore:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript('''
              CREATE TABLE IF NOT EXISTS devices(device TEXT PRIMARY KEY, uid TEXT NOT NULL,
                company TEXT NOT NULL, token TEXT UNIQUE NOT NULL, updated INTEGER NOT NULL);
              CREATE TABLE IF NOT EXISTS deliveries(event TEXT NOT NULL, token_hash TEXT NOT NULL,
                sent INTEGER NOT NULL DEFAULT 0, lease INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(event,token_hash));
            ''')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10, isolation_level='IMMEDIATE')
        try:
            with db:
                yield db
        finally:
            db.close()

    def register(self, device, user, token):
        now = int(time.time())
        with self.connect() as db:
            db.execute('DELETE FROM devices WHERE updated < ?', (now-60*86400,))
            old = db.execute('SELECT uid,token FROM devices WHERE device=?', (device,)).fetchone()
            if old and old[0] != user.uid and old[1] != token:
                raise problem(409, 'PUSH_DEVICE_CHANGED', '기기 알림 등록을 다시 시도해 주세요.')
            db.execute('DELETE FROM devices WHERE token=? AND device<>?', (token, device))
            db.execute('INSERT OR REPLACE INTO devices VALUES(?,?,?,?,?)', (device, user.uid, user.company, token, now))
            db.execute('DELETE FROM devices WHERE uid=? AND device NOT IN '
                       '(SELECT device FROM devices WHERE uid=? ORDER BY updated DESC LIMIT 10)', (user.uid, user.uid))

    def unregister(self, device, uid):
        with self.connect() as db:
            db.execute('DELETE FROM devices WHERE device=? AND uid=?', (device, uid))

    def recipients(self, company):
        with self.connect() as db:
            return db.execute('SELECT uid,token FROM devices WHERE company=? AND updated>=?',
                              (company, int(time.time())-60*86400)).fetchall()

    def claim(self, event, token_hash):
        now = int(time.time())
        with self.connect() as db:
            db.execute('INSERT OR IGNORE INTO deliveries(event,token_hash) VALUES(?,?)', (event, token_hash))
            sent, lease = db.execute('SELECT sent,lease FROM deliveries WHERE event=? AND token_hash=?', (event, token_hash)).fetchone()
            if sent:
                return False
            if lease > now:
                raise problem(503, 'PUSH_RETRY', '기기 알림을 전송하고 있습니다.')
            db.execute('UPDATE deliveries SET lease=? WHERE event=? AND token_hash=?', (now+60, event, token_hash))
            return True

    def finish(self, event, token_hash, sent):
        with self.connect() as db:
            db.execute('UPDATE deliveries SET sent=?,lease=0 WHERE event=? AND token_hash=?', (int(sent), event, token_hash))

    def remove_token(self, token):
        with self.connect() as db:
            db.execute('DELETE FROM devices WHERE token=?', (token,))


class InvalidDeviceToken(Exception):
    pass


class FcmSender:
    def __init__(self):
        self.credentials = None
        self.project, _ = firebase_config()
        self.lock = threading.Lock()
        root = next((p for p in BASE.parents if (p/'firebase.json').is_file()), BASE)
        path = Path(os.environ.get('IVENAI_FCM_CREDENTIALS') or os.environ.get('GOOGLE_APPLICATION_CREDENTIALS')
                    or root/'.secrets/fcm-sender.json')
        if path.is_file():
            from google.oauth2 import service_account
            self.credentials = service_account.Credentials.from_service_account_file(str(path), scopes=[SCOPE])

    @property
    def ready(self):
        return self.credentials is not None

    def send(self, token, data):
        if not self.ready:
            raise RuntimeError('FCM sender credentials are not configured')
        from google.auth.transport.requests import Request as AuthRequest
        with self.lock:
            if not self.credentials.valid:
                self.credentials.refresh(AuthRequest())
            access = self.credentials.token
        response = httpx.post(f'https://fcm.googleapis.com/v1/projects/{self.project}/messages:send',
            headers={'Authorization': 'Bearer '+access}, timeout=10,
            json={'message': {'token': token, 'data': data,
                             'android': {'priority': 'HIGH', 'ttl': '3600s'}}})
        if response.is_success:
            return
        try:
            errors = response.json().get('error', {}).get('details', [])
        except ValueError:
            errors = []
        if any(e.get('errorCode') in ('UNREGISTERED', 'SENDER_ID_MISMATCH') or
               (e.get('@type', '').endswith('google.firebase.fcm.v1.FcmError') and e.get('errorCode') == 'INVALID_ARGUMENT')
               for e in errors):
            raise InvalidDeviceToken()
        # Do not include the response body, access token or device token in logs/errors.
        raise RuntimeError(f'FCM returned HTTP {response.status_code}')


_services_lock = threading.Lock()


def services(request):
    with _services_lock:
        if not hasattr(request.app.state, 'f06_store'):
            path = os.environ.get('IVENAI_PUSH_DB', str(BASE/'audit_private/push.sqlite3'))
            request.app.state.f06_store = PushStore(path)
        if not hasattr(request.app.state, 'f06_sender'):
            request.app.state.f06_sender = FcmSender()
    return request.app.state.f06_store, request.app.state.f06_sender


class DeviceRegistration(BaseModel):
    model_config = ConfigDict(extra='forbid')
    device_id: str = Field(pattern=r'^[a-f0-9]{32}$')
    token: str = Field(min_length=20, max_length=4096, pattern=r'^[A-Za-z0-9_:.-]+$')


class DeviceRemoval(BaseModel):
    model_config = ConfigDict(extra='forbid')
    device_id: str = Field(pattern=r'^[a-f0-9]{32}$')


@router.post('/devices')
def register_device(body: DeviceRegistration, request: Request, user: AuditUser = Depends(require_user)):
    require_role(user, 'LOADER')
    store, sender = services(request)
    store.register(body.device_id, user, body.token)
    return dict(registered=True, sender_ready=sender.ready)


@router.post('/devices/remove')
def unregister_device(body: DeviceRemoval, request: Request, user: AuditUser = Depends(require_user)):
    store, _ = services(request)
    store.unregister(body.device_id, user.uid)
    return dict(removed=True)


@router.post('/alerts/{alert_id}')
def dispatch_alert(alert_id: str, request: Request, kind: str = 'audit', user: AuditUser = Depends(require_user)):
    require_role(user, 'QA')
    if kind not in ('audit', 'inventory') or len(alert_id) > 240 or '/' in alert_id:
        raise problem(422, 'PUSH_BAD_ALERT', '알림 대상을 확인해 주세요.')
    if kind == 'audit' and not re.fullmatch(r'AI_[a-f0-9]{64}', alert_id):
        raise problem(422, 'PUSH_BAD_ALERT', '알림 대상을 확인해 주세요.')
    source = fields_for(user, 'auditAlerts' if kind == 'audit' else 'inventory', alert_id)
    if source is None:
        return dict(status='ignored', delivered=0)
    if source.get('companyCode') != user.company:
        raise problem(403, 'PUSH_COMPANY_DENIED', '같은 회사의 알림만 보낼 수 있습니다.')
    if source.get('status') != ('OPEN' if kind == 'audit' else 'ANOMALY'):
        return dict(status='ignored', delivered=0)
    if not source.get('updatedAt'):
        raise problem(422, 'PUSH_SOURCE_INVALID', '알림의 변경 시각을 확인할 수 없습니다.')
    store, sender = services(request)
    if not sender.ready:
        raise problem(503, 'PUSH_SENDER_NOT_READY', '기기 알림 연결을 확인해 주세요.')
    event = hashlib.sha256(f'{user.company}:{kind}:{alert_id}:{source["updatedAt"]}'.encode()).hexdigest()
    sent = 0
    retry = False
    for uid, token in store.recipients(user.company):
        key = hashlib.sha256(token.encode()).hexdigest()
        if not store.claim(event, key):
            continue
        try:
            sender.send(token, dict(type='invenai_anomaly', kind=kind, alert_id=alert_id,
                                   recipient_uid=uid, company_code=user.company, event_id=event))
        except InvalidDeviceToken:
            store.remove_token(token)
            store.finish(event, key, True)
        except Exception:
            store.finish(event, key, False)
            retry = True
        else:
            store.finish(event, key, True)
            sent += 1
    if retry:
        raise problem(503, 'PUSH_RETRY', '기기 알림 전송을 다시 시도합니다.')
    return dict(status='sent', delivered=sent)
