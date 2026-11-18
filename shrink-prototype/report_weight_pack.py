"""Verify the exact resource closure in a release APK and emit its inventory."""

from collections import defaultdict, deque
import hashlib
import json
from pathlib import Path
import sys
import zipfile

from generate_weight_packs import USED


def sha(data):
    return hashlib.sha256(data).digest()


def report(encoding, apk_dir, packs, output):
    apks = list(apk_dir.glob('*.apk'))
    if len(apks) != 1:
        raise ValueError(f'Expected one APK in {apk_dir}, found {apks}')
    apk, = apks
    inventory = json.loads((packs / 'inventory.json').read_text(encoding='utf-8'))
    wanted = defaultdict(deque)
    for accessor in sorted(USED):
        for record in inventory[f'{encoding}_{accessor.lower()}']:
            wanted[sha((packs / 'res/raw' / record['file']).read_bytes())].append(record)
    symbols = defaultdict(list)
    with zipfile.ZipFile(apk) as archive:
        for item in archive.infolist():
            if item.filename.startswith('assets/symbols/'):
                raise ValueError(f'Unexpected untracked symbol asset {item.filename}')
            if not item.filename.startswith('res/') or item.is_dir():
                continue
            digest = sha(archive.read(item))
            if digest in wanted and wanted[digest]:
                record = wanted[digest].popleft()
                symbols[record['symbol']].append({**record, 'apkPath': item.filename,
                                                   'zipBytes': item.compress_size})
            elif item.filename.endswith('.sfgz') or digest in wanted:
                raise ValueError(f'Unexpected symbol resource {item.filename}')
    missing = [record['file'] for records in wanted.values() for record in records]
    if missing:
        raise ValueError(f'Missing packed files: {missing}')
    summary = {name: {'bytes': sum(r['bytes'] for r in records),
                      'zipBytes': sum(r['zipBytes'] for r in records),
                      'files': records} for name, records in sorted(symbols.items())}
    result = {'encoding': encoding, 'apk': str(apk), 'apkBytes': apk.stat().st_size,
              'symbolBytes': sum(v['bytes'] for v in summary.values()), 'symbols': summary}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(f'{apk.name}: {result["apkBytes"]} APK bytes, {result["symbolBytes"]} symbol bytes ({output})')


if __name__ == '__main__':
    encoding, apk_dir, packs, output = sys.argv[1:]
    report(encoding, Path(apk_dir), Path(packs), Path(output))
