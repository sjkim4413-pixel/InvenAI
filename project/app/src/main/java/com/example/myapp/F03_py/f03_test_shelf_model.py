"""Optional reference-scene detector, isolated from the operational model."""
import hashlib
import os
from pathlib import Path
import threading
from f03_shelf_detection import SHELF_MODEL_PATH

DEFAULT_PATH=Path(__file__).resolve().parent/'runs/detect/shelf_test_v43/weights/best.pt'
_lock=threading.Lock()
_key=None
_model=None
_digest=None


def model_path():
    override=os.environ.get('IVENAI_TEST_SHELF_MODEL_PATH')
    return Path(override) if override else DEFAULT_PATH if DEFAULT_PATH.is_file() else SHELF_MODEL_PATH


def model_key():
    path=model_path()
    if not path.is_file(): return None
    stat=path.stat()
    return str(path),stat.st_size,stat.st_mtime_ns


def reset_if_changed():
    global _key,_model,_digest
    key=model_key()
    if key!=_key: _key,_model,_digest=key,None,None
    return key


def model_digest():
    global _digest
    with _lock:
        if reset_if_changed() is None: return None
        if _digest is None: _digest=hashlib.sha256(model_path().read_bytes()).hexdigest()
        return _digest


def get_model():
    global _model
    with _lock:
        if reset_if_changed() is None: return None
        if _model is None:
            from ultralytics import YOLO
            _model=YOLO(str(model_path()))
        return _model


def training_scope():
    if model_path()==SHELF_MODEL_PATH: return 'independent_aisle_training'
    return 'reference_scenes_included_not_independent_field_accuracy'
