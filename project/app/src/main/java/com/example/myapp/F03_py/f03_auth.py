"""Require a verified Firebase identity for audit photos and inventory."""
import logging
import os
import sys
import threading
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path

from fastapi import Header, HTTPException
from fastapi.concurrency import run_in_threadpool
from f03_firebase_rest import FirebaseAccessError, lookup_profile

_initialization_lock = threading.Lock()
_logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class AuditUser:
    uid: str
    company: str
    firebase_id_token: str | None = field(default=None, repr=False, compare=False)


def authentication_required():
    return HTTPException(401, detail='Firebase 로그인이 필요합니다.',
                         headers={'WWW-Authenticate': 'Bearer'})


def external_f04_directory():
    default_dir = Path.home() / 'AndroidStudioProjects/InvenAI/IvenAI_F04'
    directory = Path(os.environ.get('IVENAI_F04_DIR') or default_dir)
    return directory if all((directory / name).is_file()
                            for name in ('firebase_auth_guard.py', 'firebase_inventory.py')) else None


def verify_token(token):
    if token in {'MOCK_TOKEN', 'development'}:
        raise authentication_required()

    directory = external_f04_directory()
    if directory is None:
        try:
            uid, company = lookup_profile(token)
        except FirebaseAccessError as exc:
            if exc.status == 401:
                raise authentication_required() from exc
            raise HTTPException(exc.status, detail=str(exc)) from exc
        return AuditUser(uid=uid, company=company, firebase_id_token=token)

    if str(directory) not in sys.path:
        sys.path.insert(0, str(directory))
    try:
        import firebase_admin
        from firebase_admin import auth
        from firebase_inventory import get_firestore_client
        from firebase_auth_guard import verify_firebase_user

        with _initialization_lock:
            try:
                firebase_admin.get_app()
            except ValueError:
                get_firestore_client()
    except Exception as exc:
        _logger.exception('Firebase verifier initialization failed')
        raise HTTPException(503, detail='서버의 Firebase 인증 설정을 확인해주세요.') from exc

    try:
        profile = verify_firebase_user(token)
    except HTTPException:
        raise
    except (auth.InvalidIdTokenError, auth.UserDisabledError) as exc:
        raise authentication_required() from exc
    except Exception as exc:
        _logger.exception('Firebase verifier failed')
        raise HTTPException(503, detail='Firebase 인증을 확인할 수 없습니다.') from exc

    if not isinstance(profile, Mapping):
        raise HTTPException(403, detail='사용자의 회사 정보를 확인할 수 없습니다.')
    uid, company = profile.get('uid'), profile.get('companyCode')
    if not isinstance(uid, str) or not uid.strip() or not isinstance(company, str) or not company.strip():
        raise HTTPException(403, detail='사용자의 회사 정보를 확인할 수 없습니다.')
    return AuditUser(uid=uid, company=company)


async def require_user(authorization: str | None = Header(default=None)):
    scheme, _, token = (authorization or '').partition(' ')
    if scheme.lower() != 'bearer' or not token.strip():
        raise authentication_required()
    return await run_in_threadpool(verify_token, token.strip())
