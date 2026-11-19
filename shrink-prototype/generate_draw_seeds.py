"""Extract the original three-master Draw inputs for the benchmark fixtures."""

import argparse
import hashlib
from pathlib import Path
import sys

from generate_weight_packs import ENTRIES, ROOT, pack, read_json

sys.path.insert(0, str(ROOT / 'tools'))
from import_system_catalog import canvas_contours, native_selector_document, read_bvg


def generate(source):
    index = read_json('symbols/index.json')
    selection = read_json(index['resourceSelectionAsset'])
    seeds = {}
    for _, name, _ in ENTRIES:
        for variant_name in sorted({name, selection['rightToLeftNames'].get(name)} - {None}):
            default = read_json(f'symbols/{variant_name}.json')
            for variant in default['systemVariants']:
                if variant['id'] in seeds:
                    continue
                old = read_json(variant['asset']) if variant['asset'] else default
                masters = {}
                for record in old['sourceSystemRenditions']:
                    path = source / record['file']
                    if hashlib.sha256(path.read_bytes()).hexdigest() != record['sha256']:
                        raise ValueError(f'Original BVG digest mismatch: {path}')
                    masters[record['key'][14]] = native_selector_document(read_bvg(path))
                ordered = [masters[i] for i in (4, 1, 9)]
                layers = {}
                for i, rows in enumerate(zip(*(m['layers'] for m in ordered), strict=True)):
                    if not rows[0]['clipStrokeKeyframeSets']:
                        continue
                    layers[str(i)] = [
                        {'paths': canvas_contours(layer['contours'], master['outer']),
                         'keyframes': layer['clipStrokeKeyframeSets']}
                        for master, layer in zip(ordered, rows)
                    ]
                nodes = [{n['id']: n['attributes'] for n in m['metadataNodes']} for m in ordered]
                seed = {'layers': layers}
                if 'ra' in nodes[0]:
                    seed['rotation'] = [[float(n['ra'][axis]) for axis in ('cx', 'cy')] for n in nodes]
                if 'da' in nodes[0]:
                    seed['dynamic'] = [[float(v) for v in n['da']['data-d'].split()][1:] for n in nodes]
                seeds[variant['id']] = seed
    return seeds


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bvg', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.write_bytes(pack(generate(args.bvg)))
