"""Require a verified Firebase identity for audit photos and inventory."""
from dataclasses import dataclass, field

from fastapi import Header, HTTPException
from fastapi.concurrency import run_in_threadpool
from f03_firebase_rest import FirebaseAccessError, lookup_profile


@dataclass(frozen=True)
class AuditUser:
    uid: str
    company: str
    firebase_id_token: str | None = field(default=None, repr=False, compare=False)


def authentication_required():
    return HTTPException(401, detail='Firebase 로그인이 필요합니다.',
                         headers={'WWW-Authenticate': 'Bearer'})


def verify_token(token):
    if token in {'MOCK_TOKEN', 'development'}:
        raise authentication_required()

    try:
        uid, company = lookup_profile(token)
    except FirebaseAccessError as exc:
        if exc.status == 401:
            raise authentication_required() from exc
        raise HTTPException(exc.status, detail=str(exc)) from exc
    return AuditUser(uid=uid, company=company, firebase_id_token=token)


async def require_user(authorization: str | None = Header(default=None)):
    scheme, _, token = (authorization or '').partition(' ')
    if scheme.lower() != 'bearer' or not token.strip():
        raise authentication_required()
    return await run_in_threadpool(verify_token, token.strip())
