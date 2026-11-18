"""Retain CSI flippability without rewriting the decoded vector catalog."""
from pathlib import Path
import argparse
import gzip
import hashlib
import json
import struct


def build_selection(assets: Path, car: Path, expected_sha256: str) -> tuple[dict, dict]:
    with car.open('rb') as stream:
        actual_sha256 = hashlib.file_digest(stream, 'sha256').hexdigest()
    if actual_sha256 != expected_sha256:
        raise ValueError('CSI source CAR does not match the installed vector catalog')
    records = {}
    audits = {}
    names = set()
    paths = sorted(assets.glob('*.json.sfgz')) + sorted((assets / 'variants').glob('*.json.sfgz'))
    with car.open('rb') as stream:
        for path in paths:
            data = json.loads(gzip.decompress(path.read_bytes()))
            variant_id = data.get('systemVariantId')
            if variant_id is None:
                continue
            names.add(data['name'])
            headers = []
            values = set()
            for rendition in data['sourceSystemRenditions']:
                stream.seek(rendition['csiOffset'])
                header = stream.read(12)
                if len(header) != 12 or header[:4] != b'ISTC':
                    raise ValueError(f'Invalid original CSI header for {path}')
                flags = struct.unpack_from('<I', header, 8)[0]
                values.add(bool(flags & (1 << 6)))
                headers.append({'offset': rendition['csiOffset'], 'flags': flags,
                                'sha256': hashlib.sha256(header).hexdigest()})
            if len(values) != 1:
                raise ValueError(f'Inconsistent original CSI flippability for {path}')
            records[variant_id] = values.pop()
            audits[variant_id] = headers
    # The counterpart must be an actual retained source resource, not a synthesized name.
    counterparts = {name: name + '.rtl' for name in sorted(names) if name + '.rtl' in names}
    audit_bytes = json.dumps(audits, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
    manifest = {
        'formatVersion': 1,
        'source': {'carSha256': actual_sha256, 'csiFlagOffset': 8, 'flippableMask': 64,
                   'headerAuditSha256': hashlib.sha256(audit_bytes).hexdigest(),
                   'verifiedVariantCount': len(records)},
        'flippableVariants': sorted(key for key, value in records.items() if value),
        'rightToLeftNames': counterparts,
    }
    return manifest, {'source': manifest['source'], 'headers': audits}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--car', type=Path, required=True)
    parser.add_argument('--audit', type=Path, required=True)
    args = parser.parse_args()
    index_path = args.assets / 'index.json'
    index = json.loads(index_path.read_text(encoding='utf-8'))
    manifest, audit = build_selection(args.assets, args.car, index['source']['sourceCarSha256'])
    output = args.assets / 'resource-selection.json.sfgz'
    output.write_bytes(gzip.compress(json.dumps(manifest, ensure_ascii=False, sort_keys=True,
                                               separators=(',', ':')).encode('utf-8'), mtime=0))
    args.audit.parent.mkdir(parents=True, exist_ok=True)
    args.audit.write_text(json.dumps(audit, ensure_ascii=False, sort_keys=True, indent=2), encoding='utf-8')
    index['resourceSelectionAsset'] = 'symbols/' + output.name
    index_path.write_text(json.dumps(index, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    print(json.dumps({'variants': manifest['source']['verifiedVariantCount'],
                      'flippableVariants': len(manifest['flippableVariants']),
                      'rightToLeftNames': len(manifest['rightToLeftNames']),
                      'bytes': output.stat().st_size}, ensure_ascii=False))


if __name__ == '__main__':
    main()
