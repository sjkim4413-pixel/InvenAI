"""Owner-scoped uploads and atomic analysis snapshots."""
import hashlib
import json
import re
import threading
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

_locks_guard=threading.Lock()
_locks={}


def owner_dir(root,user):
    # Use both company and user; arbitrary identity strings cannot become paths.
    identity=hashlib.sha256(f'{user.company}\0{user.uid}'.encode()).hexdigest()
    directory=Path(root)/identity
    directory.mkdir(parents=True,exist_ok=True)
    return directory


def check_filename(filename):
    if filename in ("warehouse_sample.jpg", "audit_sample.jpg"):
        return filename
    if not re.fullmatch(r'([0-9a-f]{32}|[a-zA-Z0-9_-]+)\.jpg', filename or ''):
        raise ValueError('올바르지 않은 이미지 파일명입니다.')
    return filename


def atomic_json(path,data):
    temporary=path.with_name(f'.{path.name}.{uuid4().hex}.tmp')
    try:
        temporary.write_text(json.dumps(data,ensure_ascii=False,allow_nan=False),encoding='utf-8')
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def job_lock(directory,filename):
    key=str(directory/filename)
    with _locks_guard:
        return _locks.setdefault(key,threading.Lock())


def utc_now():
    return datetime.now(timezone.utc).isoformat()
