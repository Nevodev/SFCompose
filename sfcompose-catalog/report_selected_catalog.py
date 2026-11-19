"""Verify independently that an APK contains only complete selected symbol closures."""

import gzip
import hashlib
import json
from pathlib import Path
import zipfile


def report(apk_dir, output, expected=None, build_data=None):
    apk, = Path(apk_dir).glob('*.apk')
    retained = {}
    wanted = {}
    source_index = None
    if build_data is not None:
        source_index = {'symbols': {}}
        catalogs = build_data if isinstance(build_data, (list, tuple, set)) else [build_data]
        for catalog in catalogs:
            with zipfile.ZipFile(catalog) as archive:
                index = json.loads(gzip.decompress(archive.read('index.json.gz')))
            duplicates = set(source_index['symbols']) & set(index['symbols'])
            if duplicates:
                raise ValueError(f'Duplicate symbols across build catalogs: {sorted(duplicates)}')
            source_index['symbols'].update(index['symbols'])
    with zipfile.ZipFile(apk) as archive:
        manifests = [item for item in archive.namelist() if item.startswith('assets/sfcompose/entries/') and item.endswith('.json')]
        for item in manifests:
            manifest = json.loads(archive.read(item))
            entry = manifest['entry']
            name, weight, *motion = entry.split(':')
            if source_index is not None and set(manifest['files']) != set(source_index['symbols'][name]):
                raise ValueError(f'Missing locale/direction or auxiliary resource in {entry}')
            if entry in retained:
                raise ValueError(f'Duplicate symbol entry {entry}')
            for record in manifest['records']:
                digest = record['sha256']
                physical = f'assets/sfcompose/data/{digest}.sfgz'
                content = archive.read(physical)
                if hashlib.sha256(content).hexdigest() != digest or len(content) != record['bytes']:
                    raise ValueError(f'Invalid symbol payload {physical}')
                if manifest['files'][record['asset']] != digest:
                    raise ValueError(f'Invalid symbol manifest {item}')
                if 'weights' in record:
                    document = json.loads(gzip.decompress(content))
                    indices = {'Ultralight': 1, 'Thin': 2, 'Light': 3, 'Regular': 4, 'Medium': 5,
                               'Semibold': 6, 'Bold': 7, 'Heavy': 8, 'Black': 9}
                    requested_weights = list(indices) if weight == 'DynamicWeight' else [weight]
                    if record['weights'] != requested_weights or record['animated'] != bool(motion):
                        raise ValueError(f'Unexpected weight/animation closure in {entry}')
                    expected_weights = [indices[weight] for weight in record['weights']]
                    if set(document['scales']) != {'small', 'medium', 'large'}:
                        raise ValueError(f'Missing optical scale in {physical}')
                    for group in document['scales'].values():
                        if [master['nativeWeight'] for master in group['masters']] != expected_weights:
                            raise ValueError(f'Unexpected weight in {physical}')
                    if not record['animated']:
                        def check(value):
                            if isinstance(value, dict):
                                if set(value) & {'systemAnchors', 'systemAttachmentSource', 'clipStrokeKeyframes', 'clipStrokeKeyframeSets'}:
                                    raise ValueError(f'Static entry retained Draw data in {physical}')
                                for child in value.values():
                                    check(child)
                            elif isinstance(value, list):
                                for child in value:
                                    check(child)
                        check(document)
                    elif name == 'wifi':
                        for group in document['scales'].values():
                            for master in group['masters']:
                                if not any(layer.get('clipStrokeKeyframes') or any(layer.get('clipStrokeKeyframeSets', []))
                                           for layer in master['compiledLayers']):
                                    raise ValueError(f'Animated Wi-Fi is missing compiled Draw data in {physical}')
                wanted[physical] = len(content)
            retained[entry] = manifest['records']
        physical = {name for name in archive.namelist() if name.startswith('assets/sfcompose/data/') and not name.endswith('/')}
        if physical != set(wanted):
            raise ValueError(f'Orphan symbol data: {sorted(physical - set(wanted))}')
        if any(name.endswith('payloads.bin') or name.endswith('build-data.zip') for name in archive.namelist()):
            raise ValueError('The full build-time catalog leaked into the APK')
    if expected is not None and set(retained) != set(expected):
        raise ValueError(f'Entry mismatch: {sorted(retained)}; expected {sorted(expected)}')
    result = {'format': 'sfcompose-selected-v1', 'apk': str(apk), 'apkBytes': apk.stat().st_size,
              'symbolBytes': sum(wanted.values()), 'entries': sorted(retained), 'symbols': retained,
              'payloadFiles': sorted(wanted)}
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(f'{apk.name}: {result["apkBytes"]} APK bytes; {len(retained)} entries; {result["symbolBytes"]} symbol bytes', flush=True)
    return result
