"""Compare the three original BVG masters with the deployed named samples."""

import argparse
from collections import Counter
from hashlib import sha256
import json
from pathlib import Path
import sys

from generate_weight_packs import ENTRIES, ROOT, read_json

sys.path.insert(0, str(ROOT / 'tools'))
from import_system_catalog import NativeFactory, sample_variant


def verify(source, clip_port, samples_path):
    index = read_json('symbols/index.json')
    samples = json.loads(samples_path.read_text(encoding='utf-8'))['namedSamples']
    native = NativeFactory(clip_port)
    counts = Counter()
    errors = []
    selection = read_json(index['resourceSelectionAsset'])
    for _, name, weight in ENTRIES:
        names = {name, selection['rightToLeftNames'].get(name)} - {None}
        for variant_name in sorted(names):
            default = read_json(f'symbols/{variant_name}.json')
            for variant in default['systemVariants']:
                old = read_json(variant['asset']) if variant['asset'] else default
                selected = [sample for sample in samples if sample['weight'].casefold() == weight.casefold()]
                records = old['sourceSystemRenditions']
                for record in records:
                    path = source / record['file']
                    if sha256(path.read_bytes()).hexdigest() != record['sha256']:
                        raise ValueError(f'Original BVG digest mismatch: {path}')
                new = sample_variant(variant_name, records, source, selected,
                                     {key.lower(): value for key, value in index['weights'].items()},
                                     native, units_per_em=old['upm'])
                new = json.loads(json.dumps(new))
                if old['layerTree'] != new['layerTree']:
                    errors.append(f'{variant_name} {variant["id"]}: layerTree')
                for scale, group in new['scales'].items():
                    actual, = group['masters']
                    expected = next(m for m in old['scales'][scale]['masters'] if m['weight'] == index['weights'][weight])
                    mismatch = sorted(key for key in actual.keys() | expected.keys() if actual.get(key) != expected.get(key))
                    if mismatch:
                        errors.append(f'{variant_name} {variant["id"]} {weight} {scale}: {mismatch}')
                    counts['samples'] += 1
                    counts['rotationAnchors'] += 'rotation' in expected['systemAnchors']
                    counts['dynamicAnchorFrames'] += len(expected['systemAnchors'].get('dynamicFrames', []))
                    for layer in expected['compiledLayers']:
                        counts['drawKeyframes'] += sum(map(len, layer['clipStrokeKeyframeSets']))
                        counts['attachmentDeclarations'] += len(layer['systemAttachmentSource'])
    return {'counts': counts, 'different': errors}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bvg', type=Path)
    parser.add_argument('clip_port', type=Path)
    parser.add_argument('samples', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    result = verify(args.bvg, args.clip_port, args.samples)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
    if result['different']:
        raise SystemExit(1)
