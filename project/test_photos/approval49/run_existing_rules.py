"""Re-run the original rules suite with isolated document IDs on the local emulator."""
from pathlib import Path
import uuid
source=Path(__file__).resolve().parent.parent/'ui45'/'test_rules.py'
text=source.read_text(encoding='utf-8').replace('hashlib.sha256(name.encode())',
    f'hashlib.sha256((name+"-approval49-{uuid.uuid4().hex}").encode())')
exec(compile(text,str(source),'exec'),{'__file__':str(source),'__name__':'__main__'})
