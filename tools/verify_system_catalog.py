"""Check generated system catalog assets and write the exact installation list."""
from pathlib import Path
import argparse
import collections
import gzip
import hashlib
import json
import math


def read_json(path):
    raw = Path(path).read_bytes()
    return json.loads(gzip.decompress(raw) if str(path).endswith('.gz') else raw)


def leaves(tree):
    result = []
    for node in tree:
        if node['type'] == 'layer':
            result.append(node)
        elif node['type'] in ['group', 'shapeGroup']:
            result.extend(leaves(node['children']))
        else:
            raise ValueError(f'Unknown logical layer type: {node["type"]}')
    return result


def validate_asset(data, index, fingerprint):
    if data['systemGenerationFingerprint'] != fingerprint:
        raise ValueError('Asset generation fingerprint differs from the catalog index')
    if data['sourceKind'] != data['metadata']['sourceKind'] or not data['sourceKind'].startswith('macOS-system-'):
        raise ValueError('Runtime metadata does not identify the original system source')
    if set(data['scales']) != {'small', 'medium', 'large'}:
        raise ValueError('Missing named system scale')
    expected_weights = sorted(index['weights'].values())
    logical = leaves(data['layerTree'])
    statistics = collections.Counter(variants=1, logicalLeaves=len(logical))
    if len(data['sourceSystemRenditions']) != 3 or {row['key'][14] for row in data['sourceSystemRenditions']} != {1, 4, 9}:
        raise ValueError('The original source master triple was not retained')
    for scale in data['scales'].values():
        masters = scale['masters']
        if [master['weight'] for master in masters] != expected_weights:
            raise ValueError('Named weight positions differ from the source index')
        for master in masters:
            statistics['namedSamples'] += 1
            contours = master['contours']
            compiled = master['compiledLayers']
            if len(compiled) != len(logical):
                raise ValueError('Logical leaves and compiled layer counts differ')
            for contour in contours:
                expected = sum({'M': 2, 'L': 2, 'Q': 4, 'C': 6, 'Z': 0}[verb] for verb in contour['verbs'])
                if len(contour['points']) != expected:
                    raise ValueError('Invalid original contour coordinate count')
                statistics['contours'] += 1
            for node in logical:
                layer = compiled[node['sourceLayerIndex']]
                if data.get('systemLayerSchema') == 'native-selectors-v1':
                    mode = node['systemRenderingMode']
                    style_modes = set(node['sourceModeStyles']) - {'defaults', 'shapeGroup'}
                    if style_modes != {mode}:
                        raise ValueError('A native named layer includes another selector mode style')
                selected = [contours[index] for index in node['components']]
                if selected != layer['contours']:
                    raise ValueError('A logical layer does not reference its exact source contours')
                if layer['sourceModeStyles'] != node['sourceModeStyles']:
                    raise ValueError('Compiled layer styles differ from logical semantic styles')
                sets = layer['clipStrokeKeyframeSets']
                if layer['clipStrokeKeyframes'] != (sets[0] if sets else []):
                    raise ValueError('Default compiled keyframe set differs from retained sets')
                for frames in sets:
                    statistics['clipKeyframeSets'] += 1
                    group_header = None
                    for frame in frames:
                        statistics['clipKeyframes'] += 1
                        if group_header is None or frame['flags'] & 8:
                            group_header = frame
                        for field in ['first', 'second']:
                            value = frame[field]
                            if 0 <= math.floor(value) < len(selected):
                                continue
                            # The original CoreUI float32 result can round the last contour's
                            # near-one distance to its integer end boundary (native oracle retained).
                            last_contour_seam = (value == len(selected) and group_header['flags'] & 8
                                                 and all(math.floor(group_header[key]) == len(selected) - 1
                                                         for key in ['first', 'second']))
                            if not last_contour_seam:
                                raise ValueError('Compiled keyframe references an unavailable local contour')
                            statistics['nativeIntegerSeams'] += 1
                if node.get('systemContourSubset'):
                    statistics['semanticLeafSamples'] += 1
                    source = master['systemSourcePathLayers']
                    expected = [source[path]['contours'][local] for path, local in node['sourcePathContourIndices']]
                    if expected != selected:
                        raise ValueError('Semantic leaf subset lost original path/contour provenance')
            if 'systemSourcePathLayers' in master:
                statistics['semanticSamples'] += 1
                original_count = sum(len(frames) for layer in master['systemSourcePathLayers'] for frames in layer['clipStrokeKeyframeSets'])
                logical_count = sum(len(frames) for layer in compiled for frames in layer['clipStrokeKeyframeSets'])
                if original_count != logical_count:
                    raise ValueError('Semantic splitting duplicated or discarded original keyframes')
            statistics['rotationAnchors'] += int('rotation' in master['systemAnchors'])
            statistics['attachmentAnchorFrames'] += len(master['systemAnchors'].get('dynamicFrames', []))
    return statistics


def verify(directory):
    directory = Path(directory).resolve()
    index = read_json(directory / 'index.json')
    report = read_json(directory / 'system-import-report.json')
    fingerprint = index['source']['generationFingerprint']
    if report['failures'] or report['generationFingerprint'] != fingerprint:
        raise ValueError('Import report contains failures or describes another generation')
    pending = set()
    for name in index['symbols']:
        pending.add(name + '.json.gz')
    checked = set()
    statistics = collections.Counter()
    errors = []
    entries = []
    while pending:
        relative = min(pending)
        pending.remove(relative)
        path = (directory / relative).resolve()
        if not path.is_relative_to(directory):
            raise ValueError('Variant asset path is outside the generated catalog')
        try:
            data = read_json(path)
            statistics.update(validate_asset(data, index, fingerprint))
            for variant in data['systemVariants']:
                if variant['asset'] is None:
                    continue
                if not variant['asset'].startswith('symbols/'):
                    raise ValueError('Variant path is not relative to the symbol asset directory')
                target = variant['asset'].removeprefix('symbols/')
                if target not in checked and target != relative:
                    pending.add(target)
            raw = path.read_bytes()
            entries.append({'relativePath': relative, 'assetPath': 'symbols/' + relative,
                            'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
                            'symbol': data['name'], 'variantId': data['systemVariantId']})
        except Exception as error:
            errors.append({'file': relative, 'error': str(error), 'errorType': type(error).__name__})
        checked.add(relative)
        if len(checked) % 500 == 0:
            print(f'Checked {len(checked)} assets; {len(errors)} failures', flush=True)
    if len(checked) != report['variants']:
        errors.append({'error': 'Manifest variant count differs from reachable generated assets'})
    index_raw = (directory / 'index.json').read_bytes()
    entries.append({'relativePath': 'index.json', 'assetPath': 'symbols/index.json', 'bytes': len(index_raw),
                    'sha256': hashlib.sha256(index_raw).hexdigest()})
    manifest = {'sourceKind': report['sourceKind'], 'generationFingerprint': fingerprint,
                'symbols': len(index['symbols']), 'variants': report['variants'], 'statistics': statistics,
                'bytes': sum(entry['bytes'] for entry in entries), 'errors': errors,
                'files': sorted(entries, key=lambda entry: entry['relativePath'])}
    (directory / 'asset-install-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    print(json.dumps({key: value for key, value in manifest.items() if key != 'files'}, ensure_ascii=False))
    if errors:
        raise ValueError(f'{len(errors)} catalog validation failures')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    verify(parser.parse_args().directory)
