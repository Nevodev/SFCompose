"""Check the encoded payload strings that survived R8 in the finished APK."""

import hashlib
import json
from pathlib import Path
import struct
import zipfile


def dex_strings(archive):
    strings = {}
    for file in archive.namelist():
        if not file.endswith('.dex'):
            continue
        data = archive.read(file)
        count, offset = struct.unpack_from('<II', data, 56)
        for index in range(count):
            cursor = struct.unpack_from('<I', data, offset + index * 4)[0]
            start = cursor
            while data[cursor] & 0x80:
                cursor += 1
            cursor += 1
            end = data.index(0, cursor)
            value = data[cursor:end]
            digest = hashlib.sha256(value).hexdigest()
            strings.setdefault(digest, []).append((file, end + 1 - start))
    return strings


def report(apk_dir, inventory, output, expected=None):
    apk, = Path(apk_dir).glob('*.apk')
    with zipfile.ZipFile(apk) as archive:
        strings = dex_strings(archive)
        raw = [file for file in archive.namelist() if file.endswith('.sfgz') or file.startswith('assets/symbols/')]
    retained = {}
    payloads = {}
    chunks = set()
    observed_chunks = set()
    partial = set()
    with Path(inventory).open(encoding='utf-8') as source:
        assert json.loads(next(source))['format'] == 'jvm-symbols-v1'
        for line in source:
            record = json.loads(line)
            files = record['files']
            observed_chunks.update(chunk for file in files for chunk in file['chunks'] if chunk in strings)
            marker, = [file for file in files if file['entryMarker']]
            matches = [chunk in strings for chunk in marker['chunks']]
            if not all(matches):
                if any(matches):
                    partial.add(record['entry'])
                continue
            for file in files:
                if not all(chunk in strings for chunk in file['chunks']):
                    raise ValueError(f'Incomplete payload closure: {record["entry"]} {file["asset"]}')
                payloads[file['sha256']] = file['bytes']
                chunks.update(file['chunks'])
            retained[record['entry']] = files
    if expected is not None and set(retained) != set(expected):
        raise ValueError(f'Entry mismatch. Kept {sorted(retained)}, expected {sorted(expected)}')
    # A chunk of an unused marker can be shared with a retained payload. Only
    # chunks outside the complete retained closures establish leaked data.
    if observed_chunks != chunks:
        raise ValueError(f'Unexpected payload chunks outside retained closures: {len(observed_chunks - chunks)}')
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    result = {'format': 'jvm-symbols-v1', 'apk': str(apk), 'apkBytes': apk.stat().st_size,
              'symbolBytes': sum(payloads.values()),
              'symbolDexStringBytes': sum(size for chunk in chunks for _, size in strings[chunk]),
              'entries': sorted(retained), 'symbols': retained, 'partialEntries': sorted(partial), 'rawFiles': raw}
    Path(output).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(f'{apk.name}: {result["apkBytes"]} APK bytes, {len(retained)} entries, '
          f'{result["symbolBytes"]} compressed symbol bytes, {result["symbolDexStringBytes"]} DEX string bytes')
    return result
