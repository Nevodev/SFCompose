"""Verify the exact resource closure in a release APK and emit its inventory."""

from collections import defaultdict, deque
import hashlib
import gzip
import json
from pathlib import Path
import sys
import zipfile

from generate_weight_packs import USED


def verify_animation_payload(data, record, apk_path):
    from generate_symbols import without_animation

    if not record['animated']:
        if data != without_animation(data):
            raise ValueError(f'Static resource contains animation data: {apk_path}')
    elif record['symbol'] == 'wifi':
        for scale in data['scales'].values():
            for master in scale['masters']:
                if not any(layer.get('clipStrokeKeyframes') or any(layer.get('clipStrokeKeyframeSets', []))
                           for layer in master['compiledLayers']):
                    raise ValueError(f'Animated Wi-Fi resource is missing Draw keyframes: {apk_path}')


def sha(data):
    return hashlib.sha256(data).digest()


def report(encoding, apk_dir, packs, output, entries=()):
    if encoding == 'api':
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'sfcompose-catalog'))
        if (packs / 'build-data.zip').exists():
            from report_selected_catalog import report as report_selected
            return report_selected(apk_dir, output, entries, packs / 'build-data.zip')
        inventory_file = packs / 'inventory.json'
        with inventory_file.open(encoding='utf-8') as source:
            if 'jvm-symbols-v1' in source.readline():
                from report_jvm_catalog import report as report_jvm
                return report_jvm(apk_dir, inventory_file, output, entries)
    apks = list(apk_dir.glob('*.apk'))
    if len(apks) != 1:
        raise ValueError(f'Expected one APK in {apk_dir}, found {apks}')
    apk, = apks
    inventory = json.loads((packs / 'inventory.json').read_text(encoding='utf-8'))
    wanted = defaultdict(deque)
    for entry in entries or sorted(f'{encoding}_{accessor.lower()}' for accessor in USED):
        for record in inventory[entry]:
            wanted[sha((packs / 'res/raw' / record['file']).read_bytes())].append(record)
    symbols = defaultdict(list)
    with zipfile.ZipFile(apk) as archive:
        for item in archive.infolist():
            if item.filename.startswith('assets/symbols/'):
                raise ValueError(f'Unexpected untracked symbol asset {item.filename}')
            if not item.filename.startswith('res/') or item.is_dir():
                continue
            content = archive.read(item)
            digest = sha(content)
            if digest in wanted and wanted[digest]:
                record = wanted[digest].popleft()
                if encoding == 'live' and 'variantId' in record:
                    data = json.loads(gzip.decompress(content))
                    if 'drawMasters' not in data or any(
                            'systemAnchors' in master or any('clipStrokeKeyframeSets' in layer for layer in master['compiledLayers'])
                            for group in data['scales'].values() for master in group['masters']):
                        raise ValueError(f'Live Draw resource contains precomputed Draw data: {item.filename}')
                if encoding == 'api' and 'variantId' in record:
                    data = json.loads(gzip.decompress(content))
                    actual = {scale: [m['nativeWeight'] for m in group['masters']] for scale, group in data['scales'].items()}
                    expected = [WEIGHTS.index(weight) + 1 for weight in record['weights']]
                    if set(actual) != set(record['scales']) or any(values != expected for values in actual.values()):
                        raise ValueError(f'Invalid static or dynamic weight closure: {item.filename} {actual}')
                    verify_animation_payload(data, record, item.filename)
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


WEIGHTS = ('Ultralight', 'Thin', 'Light', 'Regular', 'Medium', 'Semibold', 'Bold', 'Heavy', 'Black')


if __name__ == '__main__':
    encoding, apk_dir, packs, output, *entries = sys.argv[1:]
    report(encoding, Path(apk_dir), Path(packs), Path(output), entries)
