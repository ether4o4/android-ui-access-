#!/usr/bin/env python3
"""Build-time downloads only; APK contains every runtime voice file. Hashes pin upstream assets."""
import hashlib
import shutil
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.voice-build'
CACHE.mkdir(exist_ok=True)

def download(name, url, digest):
    target = CACHE / name
    if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != digest:
        temporary = target.with_suffix('.partial')
        print('Downloading', name, flush=True)
        urllib.request.urlretrieve(url, temporary)
        assert hashlib.sha256(temporary.read_bytes()).hexdigest() == digest, name + ' checksum mismatch'
        temporary.replace(target)
    return target

aar = download('sherpa-onnx-1.13.3.aar',
    'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.3/sherpa-onnx-1.13.3.aar',
    '243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6')
model = download('kokoro-multi-lang-v1_0.tar.bz2',
    'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2',
    '60703707bc05ccd48d60e1eb723f38b07d6e474795a0b6a588d7220400e0f7a4')
(ROOT / 'app/libs').mkdir(exist_ok=True)
shutil.copy2(aar, ROOT / 'app/libs' / aar.name)
assets = ROOT / 'app/src/main/assets/kokoro'
assets.mkdir(parents=True, exist_ok=True)
keep = {'model.onnx', 'voices.bin', 'tokens.txt', 'lexicon-us-en.txt', 'LICENSE', 'README.md'}
with tarfile.open(model) as archive:
    for member in archive:
        parts = Path(member.name).parts
        if len(parts) < 2 or parts[0] != 'kokoro-multi-lang-v1_0':
            continue
        relative = Path(*parts[1:])
        if '..' in relative.parts or not member.isfile():
            continue
        if relative.as_posix() not in keep and relative.parts[0] != 'espeak-ng-data':
            continue
        destination = assets / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        with archive.extractfile(member) as source, destination.open('wb') as output:
            shutil.copyfileobj(source, output)
for name in keep - {'README.md'}:
    assert (assets / name).stat().st_size > 0, name
assert (assets / 'espeak-ng-data/phondata').is_file()
licenses = ROOT / 'app/src/main/assets/voice-licenses'
licenses.mkdir(exist_ok=True)
for name in ('SHERPA-APACHE-2.0.txt', 'ESPEAK-GPL-3.0.txt', 'ONNXRUNTIME-LICENSE.txt', 'VOICE-NOTICES.md'):
    shutil.copy2(ROOT / 'third-party/voice' / name, licenses / name)
shutil.copy2(assets / 'LICENSE', licenses / 'KOKORO-LICENSE.txt')
print('Bundled voice assets ready:', sum(p.stat().st_size for p in assets.rglob('*') if p.is_file()), 'bytes')
