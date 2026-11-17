"""Import compiled macOS CoreGlyphs paths, styles and stroke keyframes.

The input is a selectively extracted system catalog. Compiled keyframes stay in
their source order; they are already path-distance coordinates, not authoring ties.
"""
from pathlib import Path
import argparse
import hashlib
import copy
import importlib.util
import json
import math
import re
import struct
import sys

from bvg import read_bvg


IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
COMMAND_WIDTH = {'M': 2, 'L': 2, 'Q': 4, 'C': 6, 'Z': 0}


def float32(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False), encoding='utf-8')


def matrix(value):
    if not value:
        return IDENTITY
    match = re.fullmatch(r'matrix\(([^)]+)\)', value.strip())
    if not match:
        raise ValueError(f'Unimplemented system SVG transform: {value}')
    values = tuple(float(v) for v in re.split(r'[,\s]+', match[1].strip()))
    if len(values) != 6 or not all(math.isfinite(v) for v in values):
        raise ValueError('Invalid system SVG matrix')
    return values


def multiply(left, right):
    a, b, c, d, e, f = left
    g, h, i, j, k, l = right
    return (a * g + c * h, b * g + d * h, a * i + c * j, b * i + d * j,
            a * k + c * l + e, b * k + d * l + f)


def inverse(value):
    a, b, c, d, e, f = value
    determinant = a * d - b * c
    if determinant == 0:
        raise ValueError('Singular system SVG matrix')
    return (d / determinant, -b / determinant, -c / determinant, a / determinant,
            (c * f - d * e) / determinant, (b * e - a * f) / determinant)


def transform_point(value, x, y):
    a, b, c, d, e, f = value
    return a * x + c * y + e, b * x + d * y + f


def absolute_commands(commands):
    result = []
    current = (0.0, 0.0)
    start = current
    previous_control = None
    previous_kind = None
    widths = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2}
    for command in commands:
        verb, values = command[0], command[1:]
        upper = verb.upper()
        if upper == 'Z':
            if values:
                raise ValueError('Coordinates on closePath')
            result.append(['Z'])
            current = start
            previous_control = previous_kind = None
            continue
        if upper not in widths or not values or len(values) % widths[upper]:
            raise ValueError(f'Unsupported or invalid system path command: {verb}')
        for offset in range(0, len(values), widths[upper]):
            chunk = [float(v) for v in values[offset:offset + widths[upper]]]
            relative = verb.islower()
            if upper == 'H':
                points = [chunk[0] + (current[0] if relative else 0.0), current[1]]
                output_kind = 'L'
            elif upper == 'V':
                points = [current[0], chunk[0] + (current[1] if relative else 0.0)]
                output_kind = 'L'
            else:
                points = [v + (current[i % 2] if relative else 0.0) for i, v in enumerate(chunk)]
                output_kind = upper
            if upper in ['S', 'T']:
                same_curve = previous_kind in (['C', 'S'] if upper == 'S' else ['Q', 'T'])
                control = tuple(2 * a - b for a, b in zip(current, previous_control)) if same_curve else current
                points = [*control, *points]
                output_kind = 'C' if upper == 'S' else 'Q'
            if upper == 'M' and offset:
                output_kind = 'L'
            result.append([output_kind, *points])
            current = tuple(points[-2:])
            if output_kind == 'M':
                start = current
            previous_control = tuple(points[-4:-2]) if output_kind in ['C', 'Q'] else None
            previous_kind = upper
    return result


def contours_from_commands(commands, transform=IDENTITY):
    result = []
    current = None
    for command in absolute_commands(commands):
        verb, values = command[0], command[1:]
        if verb == 'M':
            if current is not None:
                raise ValueError('Open system contour before moveTo')
            current = {'verbs': '', 'points': []}
        if current is None:
            raise ValueError('System path command before moveTo')
        current['verbs'] += verb
        for x, y in zip(values[::2], values[1::2]):
            px, py = transform_point(transform, x, y)
            current['points'].extend([px, -py])
        if verb == 'Z':
            result.append(current)
            current = None
    if current is not None:
        raise ValueError('Open contour in compiled system glyph')
    return result


def compiled_keyframe_sets(value):
    if value is None:
        return []
    if not value:
        raise ValueError('Empty compiled stroke keyframes')
    version = int(value[0])
    if version == 0:
        cursor = 1
        offsets = [2]
    elif version == 1:
        count = int(value[1])
        cursor = count + 2
        offsets = [int(v) for v in value[2:cursor]]
    else:
        raise ValueError(f'Unsupported compiled keyframe format: {version}')
    if (len(value) - cursor) % 4:
        raise ValueError('Incomplete compiled stroke keyframe')
    frames = []
    for index in range(cursor, len(value), 4):
        flags, progress, first, second = value[index:index + 4]
        frames.append({'flags': int(flags), 'progress': float32(progress),
                       'first': float32(first), 'second': float32(second)})
    header_words = len(offsets) + 1
    starts = []
    for offset in offsets:
        if offset < header_words or (offset - header_words) % 4:
            raise ValueError(f'Invalid compound keyframe set offset: {offset}')
        starts.append((offset - header_words) // 4)
    if starts != sorted(starts) or any(start > len(frames) for start in starts):
        raise ValueError('Invalid compound keyframe set boundaries')
    return [frames[start:end] for start, end in zip(starts, starts[1:] + [len(frames)])]


def compiled_keyframes(value):
    sets = compiled_keyframe_sets(value)
    return sets[0] if sets else []


def declarations(style):
    return {item['key']: item['value'] if 'value' in item else {'rawHex': item['rawHex']}
            for item in style['declarations']}


def resolved_styles(document, node):
    modes = {'defaults': {}}
    for style in document['styles']:
        if style['selector'] == '.defaults':
            modes['defaults'].update(declarations(style))
    for index in node['classIndices']:
        style = document['styles'][index]
        selector_type = style['selectorType'] & 0x7f
        mode = {0: 'monochrome', 1: 'multicolor', 2: 'hierarchical', 3: 'shapeGroup'}[selector_type]
        modes.setdefault(mode, {})
        modes[mode].update({'selector': style['selector'], **declarations(style)})
    return modes


def system_layers(document):
    root = document['tree']
    children = root['children']
    if len(children) == 1 and children[0]['tag'] == 'g':
        container = children[0]
        outer = matrix(container['attributes'].get('transform'))
    else:
        container, outer = root, IDENTITY
    output = []
    def require_plain_group(node):
        if node['tag'] == 'g' and (node['classIndices'] or any(key != 'transform' for key in node['attributes'])):
            raise ValueError('System semantic groups require explicit runtime hierarchy support')
    require_plain_group(container)
    def walk(node, inherited):
        require_plain_group(node)
        local = multiply(inherited, matrix(node['attributes'].get('transform')))
        if node['tag'] == 'path':
            modes = resolved_styles(document, node)
            keyframe_sets = compiled_keyframe_sets(node['attributes'].get('data-clipstroke-keyframes'))
            output.append({'contours': contours_from_commands(node['attributes']['d'], local),
                           'clipStrokeKeyframes': keyframe_sets[0] if keyframe_sets else [],
                           'clipStrokeKeyframeSets': keyframe_sets,
                           'sourceModeStyles': modes,
                           'sourceAttributes': {k: v for k, v in node['attributes'].items() if k != 'd'}})
        elif node['tag'] not in ['svg', 'g']:
            raise ValueError(f'Unsupported compiled system SVG node: {node["tag"]}')
        for child in node['children']:
            walk(child, local)
    for child in container['children']:
        walk(child, IDENTITY)
    return output, outer


def layer_tree(layers):
    result = []
    component = 0
    for index, layer in enumerate(layers):
        modes = layer['sourceModeStyles']
        common = modes.get('monochrome', {})
        node = {'type': 'layer', 'sourceLayerIndex': index,
                'components': list(range(component, component + len(layer['contours']))),
                'sourceModeStyles': modes, 'monochrome': 'draw', 'shapeGroupBehavior': 'draw',
                'hierarchical': {'primary': 0, 'secondary': 1, 'tertiary': 2}.get(modes.get('hierarchical', {}).get('selector', '').split(':')[-1], 0),
                'multicolor': {'color': modes.get('multicolor', {}).get('selector', ':tintColor').split(':')[-1]},
                'tags': common.get('-sfsymbols-layer-tags', '').split()}
        component += len(layer['contours'])
        if '-sfsymbols-motion-group' in common:
            node['motionGroup'] = int(common['-sfsymbols-motion-group'])
        if '-sfsymbols-variable-threshold' in common:
            node['autovariable'] = True
            node['variableThreshold'] = float(common['-sfsymbols-variable-threshold'])
        if modes.get('defaults', {}).get('-sfsymbols-variable-value-mode') == 'draw':
            node['enableVariableDraw'] = True
        if '-sfsymbols-variable-draw' in common:
            interval = [float(value) for value in common['-sfsymbols-variable-draw'].split()]
            if len(interval) != 2:
                raise ValueError('Invalid system variable-draw interval')
            node['enableVariableDraw'] = True
            node['variableDraw'] = {'offset': interval[0], 'length': interval[1]}
        result.append(node)
    return result


def import_decoded(source_dir, metadata_path, base_assets, output, selected_names=None, regular_only=False):
    metadata = json.loads(Path(metadata_path).read_text(encoding='utf-8'))['renditions']
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    records = []
    for row in metadata:
        name = row['name'].removesuffix('.svg')
        if selected_names and name not in selected_names:
            continue
        if regular_only and row['glyphWeight'] != 4:
            continue
        source = Path(source_dir) / f'{row["name"]}.weight{row["glyphWeight"]}.bvg'
        document = read_bvg(source)
        layers, outer = system_layers(document)
        records.append((name, row, document, layers, outer))
    names = sorted({record[0] for record in records})
    for name in names:
        base = json.loads((Path(base_assets) / (name + '.json')).read_text(encoding='utf-8'))
        symbol_records = [record for record in records if record[0] == name]
        base['scales'] = {}
        base.pop('nativeSkippedComponents', None)
        base['sourceKind'] = 'macOS-system-CoreGlyphs'
        base['sourceSystemRenditions'] = []
        for _, row, document, layers, outer in symbol_records:
            if row['glyphSize'] != 1:
                raise ValueError('Only original system small vector scale is currently decoded')
            metric = row['resource1018']
            viewbox = document['tree']['attributes']['viewBox']
            invert = inverse(outer)
            # CSI metrics are in the SVG canvas; the stored contours use source units with Y up.
            insets = metric['alignmentRectInsets']
            center_x = viewbox[0] + (viewbox[2] + insets['right'] - insets['left']) * 0.5
            center_y = viewbox[1] + viewbox[3] - (metric['capline'] * 0.5 + metric['baseline'])
            mx, my = transform_point(invert, center_x, center_y)
            weight = {1: 1.0, 4: 400.0, 9: 1000.0}[row['glyphWeight']]
            master = {'weight': weight, 'nativeWeight': row['glyphWeight'],
                      'advance': (viewbox[2] + insets['left'] + insets['right']) / abs(outer[0]),
                      'metricCenterX': mx, 'metricCenterY': -my,
                      'contours': [contour for layer in layers for contour in layer['contours']],
                      'compiledLayers': layers, 'systemMetrics': metric,
                      'systemViewBox': viewbox, 'systemTransform': outer}
            base['scales'].setdefault('small', {'masters': []})['masters'].append(master)
            base['layerTree'] = layer_tree(layers)
            base['sourceSystemRenditions'].append({'source': row['source'], 'bvgSha256': document['sha256'],
                                                   'key': row['key'], 'glyphWeight': row['glyphWeight']})
        base['scales']['small']['masters'].sort(key=lambda item: item['weight'])
        write_json(output / (name + '.json'), base)
    write_json(output / 'system-import-report.json', {'symbols': names, 'sourceKind': 'macOS-system-CoreGlyphs',
                                                     'metadataSha256': digest(metadata_path),
                                                     'limitations': ['Only extracted vector renditions are imported; resampling requires original CoreUI interpolation coefficients.']})
    return names


def blend(regular, ultralight, black, coefficients):
    u, b = coefficients
    return regular + u * (ultralight - regular) + b * (black - regular)


def canvas_contours(contours, outer):
    result = copy.deepcopy(contours)
    for contour in result:
        coordinates = contour['points']
        contour['points'] = [value for x, y in zip(coordinates[::2], coordinates[1::2])
                             for value in transform_point(outer, x, -y)]
    return result


def interpolate_contours(regular, ultralight, black, coefficients, scale):
    result = []
    if len({len(regular), len(ultralight), len(black)}) != 1:
        raise ValueError('System master contour counts differ')
    for r, u, b in zip(regular, ultralight, black):
        if len({r['verbs'], u['verbs'], b['verbs']}) != 1 or len({len(r['points']), len(u['points']), len(b['points'])}) != 1:
            raise ValueError('System master contour topology differs')
        result.append({'verbs': r['verbs'], 'points': [blend(rv, uv, bv, coefficients) * scale
                                                      for rv, uv, bv in zip(r['points'], u['points'], b['points'])]})
    return result


def import_samples(source_dir, metadata_path, base_assets, output, samples_path, native_module_path, selected_names=None):
    specification = importlib.util.spec_from_file_location('sfcompose_native_clips', native_module_path)
    native_module = importlib.util.module_from_spec(specification)
    sys.path.insert(0, str(Path(native_module_path).resolve().parent))
    try:
        specification.loader.exec_module(native_module)
    finally:
        sys.path.pop(0)
    native = native_module.NativeClipInterpolator()
    rows = json.loads(Path(metadata_path).read_text(encoding='utf-8'))['renditions']
    samples = json.loads(Path(samples_path).read_text(encoding='utf-8'))['namedSamples']
    weights = json.loads((Path(base_assets) / 'index.json').read_text(encoding='utf-8'))['weights']
    weight_keys = {name.lower(): value for name, value in weights.items()}
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    names = sorted({row['name'].removesuffix('.svg') for row in rows
                    if not selected_names or row['name'].removesuffix('.svg') in selected_names})
    for name in names:
        records = {}
        for row in rows:
            if row['name'].removesuffix('.svg') != name:
                continue
            if row['glyphSize'] != 1 or row['glyphWeight'] not in [1, 4, 9]:
                raise ValueError('Named sampling requires the original small UL/Regular/Black system masters')
            document = read_bvg(Path(source_dir) / f'{row["name"]}.weight{row["glyphWeight"]}.bvg')
            layers, outer = system_layers(document)
            records[row['glyphWeight']] = {'row': row, 'document': document, 'layers': layers, 'outer': outer}
        if set(records) != {1, 4, 9}:
            raise ValueError(f'Incomplete original system master set: {name}')
        ultralight, regular, black = [records[key] for key in [1, 4, 9]]
        tree = layer_tree(regular['layers'])
        if any(layer_tree(record['layers']) != tree for record in [ultralight, black]):
            raise ValueError(f'Master-specific system layer structure requires runtime support: {name}')
        for record in records.values():
            for layer in record['layers']:
                layer['canvasContours'] = canvas_contours(layer['contours'], record['outer'])
                layer['lengthData'] = native.length_data(layer['canvasContours'])
        base = json.loads((Path(base_assets) / (name + '.json')).read_text(encoding='utf-8'))
        base['scales'] = {}
        base.pop('nativeSkippedComponents', None)
        base['sourceKind'] = 'macOS-system-CoreGlyphs'
        base['layerTree'] = tree
        defaults = regular['layers'][0]['sourceModeStyles']['defaults']
        base['systemDrawReversesMotionGroups'] = defaults.get('-sfsymbols-draw-reverses-motion-groups') == 'true'
        base['sourceSystemRenditions'] = [{'source': record['row']['source'], 'bvgSha256': record['document']['sha256'],
                                          'key': record['row']['key'], 'glyphWeight': record['row']['glyphWeight'],
                                          'resource1018': record['row']['resource1018'],
                                          'resource1019': record['row'].get('resource1019')}
                                         for record in records.values()]
        inverse_regular = inverse(regular['outer'])
        for sample in samples:
            coefficients = sample['coefficients']
            scale = sample['sizeTransform'][0]
            layers = []
            for r, u, b in zip(regular['layers'], ultralight['layers'], black['layers']):
                canvas = interpolate_contours(r['canvasContours'], u['canvasContours'], b['canvasContours'], coefficients, scale)
                sets = []
                if len({len(r['clipStrokeKeyframeSets']), len(u['clipStrokeKeyframeSets']), len(b['clipStrokeKeyframeSets'])}) != 1:
                    raise ValueError('System master clipstroke set counts differ')
                if r['clipStrokeKeyframeSets']:
                    length_data = native.length_data(canvas)
                    for rset, uset, bset in zip(r['clipStrokeKeyframeSets'], u['clipStrokeKeyframeSets'], b['clipStrokeKeyframeSets']):
                        sets.append(native.interpolate({'lengthData': r['lengthData'], 'keyframes': rset},
                                                       {'lengthData': u['lengthData'], 'keyframes': uset},
                                                       {'lengthData': b['lengthData'], 'keyframes': bset}, length_data, coefficients))
                stored = copy.deepcopy(canvas)
                for contour in stored:
                    contour['points'] = [value for x, y in zip(contour['points'][::2], contour['points'][1::2])
                                         for value in (lambda point: (point[0], -point[1]))(transform_point(inverse_regular, x, y))]
                layers.append({'contours': stored, 'clipStrokeKeyframes': sets[0] if sets else [],
                               'clipStrokeKeyframeSets': sets, 'sourceModeStyles': r['sourceModeStyles'],
                               'sourceAttributes': r['sourceAttributes']})
            def mixed(getter):
                return blend(getter(regular), getter(ultralight), getter(black), coefficients) * scale
            canvas_width = mixed(lambda record: record['document']['tree']['attributes']['viewBox'][2])
            canvas_height = mixed(lambda record: record['document']['tree']['attributes']['viewBox'][3])
            center_x = mixed(lambda record: (record['document']['tree']['attributes']['viewBox'][2]
                                             + record['row']['resource1018']['alignmentRectInsets']['right']
                                             - record['row']['resource1018']['alignmentRectInsets']['left']) * .5)
            center_y_up = mixed(lambda record: record['row']['resource1018']['capline'] * .5 + record['row']['resource1018']['baseline'])
            mx, my = transform_point(inverse_regular, center_x, canvas_height - center_y_up)
            left = mixed(lambda record: record['row']['resource1018']['alignmentRectInsets']['left'])
            right = mixed(lambda record: record['row']['resource1018']['alignmentRectInsets']['right'])
            master = {'weight': weight_keys[sample['weight']], 'nativeWeight': sample['weightEnum'],
                      'advance': (canvas_width + left + right) / abs(regular['outer'][0]),
                      'metricCenterX': mx, 'metricCenterY': -my,
                      'contours': [contour for layer in layers for contour in layer['contours']], 'compiledLayers': layers,
                      'systemSampling': {'coefficients': coefficients, 'sizeScale': scale, 'sizeTranslation': 0,
                                         'nativeCanvasSize': [canvas_width, canvas_height],
                                         'nativeMetricCenter': [center_x, center_y_up]},
                      'systemMetrics': {'standardPointSize': regular['row']['resource1018']['standardPointSize'],
                                        'baseline': mixed(lambda record: record['row']['resource1018']['baseline']),
                                        'capline': mixed(lambda record: record['row']['resource1018']['capline']),
                                        'alignmentRectInsets': {key: mixed(lambda record: record['row']['resource1018']['alignmentRectInsets'][key])
                                                                for key in ['top', 'left', 'bottom', 'right']}},
                      'systemTransform': regular['outer']}
            base['scales'].setdefault(sample['scale'], {'masters': []})['masters'].append(master)
        write_json(output / (name + '.json'), base)
    write_json(output / 'system-import-report.json', {'symbols': names, 'sourceKind': 'macOS-system-CoreGlyphs',
                                                     'metadataSha256': digest(metadata_path), 'samplesSha256': digest(samples_path),
                                                     'nativeClipModuleSha256': digest(native_module_path),
                                                     'samplesPerSymbol': len(samples), 'limitations': []})
    return names


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--metadata', type=Path, required=True)
    parser.add_argument('--base-assets', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--name', action='append')
    parser.add_argument('--regular-only', action='store_true')
    parser.add_argument('--samples', type=Path)
    parser.add_argument('--native-clip-module', type=Path)
    args = parser.parse_args()
    if args.samples:
        if not args.native_clip_module or args.regular_only:
            parser.error('--samples requires --native-clip-module and cannot use --regular-only')
        names = import_samples(args.source_dir, args.metadata, args.base_assets, args.output, args.samples, args.native_clip_module, args.name)
    else:
        names = import_decoded(args.source_dir, args.metadata, args.base_assets, args.output, args.name, args.regular_only)
    print(f'Imported {len(names)} original system symbols into {args.output}')


if __name__ == '__main__':
    main()
