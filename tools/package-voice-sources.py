#!/usr/bin/env python3
"""Package ODCM and the pinned speech runtime source alongside the APK."""
import json, re, subprocess, tarfile, urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1]
work = root / '.voice-build/sources'
work.mkdir(parents=True, exist_ok=True)
subprocess.run(['git', 'archive', '--format=tar.gz', '-o', str(work / 'odcm-source.tar.gz'), 'HEAD'], cwd=root, check=True)
urls = {
    'sherpa-onnx-source.tar.gz': 'https://github.com/k2-fsa/sherpa-onnx/archive/330609dab49be6ee8b30702918ca7abbbad1286a.tar.gz',
    'espeak-ng-source.zip': 'https://github.com/csukuangfj/espeak-ng/archive/f6fed6c58b5e0998b8e68c6610125e2d07d595a7.zip',
}
for name, url in urls.items():
    urllib.request.urlretrieve(url, work / name)
# Preserve source dependency archives pinned by the runtime build configuration.
with tarfile.open(work / 'sherpa-onnx-source.tar.gz') as archive:
    for member in archive:
        if '/cmake/' not in member.name or not member.name.endswith('.cmake'):
            continue
        source = archive.extractfile(member).read().decode()
        for url in re.findall(r'https://github\.com/[^"\s;]+/archive/[^"\s;]+', source):
            if '$' in url or not url.endswith(('.zip', '.tar.gz')):
                continue
            name = url.split('/')[4] + '-' + url.split('/')[-1]
            if name not in urls and url not in urls.values():
                urls[name] = url
                urllib.request.urlretrieve(url, work / name)
(work / 'SOURCE-URLS.json').write_text(json.dumps(urls, indent=2) + '\n')
with tarfile.open(root / 'ODCM-0.3.6-voice-source.tar.gz', 'w:gz') as out:
    out.add(work, arcname='ODCM-0.3.6-voice-source')
