"""Build Android assets from all CoreGlyphs system BVG renditions.

The catalog keeps complete rendition keys. Named-weight path sampling happens in
the system canvas, where CoreUI's curve-length tolerance has its original units.
"""
from pathlib import Path
import argparse
import collections
import copy
import gzip
import hashlib
import importlib.util
import json
import math
import struct
import sys
import time

from bvg import read_bvg
from read_coreglyphs_car import CoreGlyphsCatalog
from import_system_symbols import (
    IDENTITY, blend, canvas_contours, compiled_keyframe_sets, contours_from_commands,
    digest, interpolate_contours, inverse, layer_tree, matrix, multiply,
    resolved_styles, transform_point, write_json, float32, declarations,
)


def decode_metadata(record):
    resources = {item['id']: bytes.fromhex(item['dataHex']) for item in record['metadata']}
    body = resources[1018]
    flags, point_size = struct.unpack_from('<II', body)
    baseline, capline, version, left, bottom, right, top = struct.unpack_from('<7f', body, 8)
    count = struct.unpack_from('<I', body, 36)[0]
    if len(body) != 40 + count * 8:
        raise ValueError('Unexpected system glyph metric resource length')
    sizes = [dict(zip(['rawIdentifier', 'pointSize'], struct.unpack_from('<II', body, 40 + index * 8)))
             for index in range(count)]
    render_version, render_mode, render_flags = struct.unpack('<III', resources[1019])
    return {
        'resource1018': {'flags': flags, 'standardPointSize': point_size, 'baseline': baseline,
                         'capline': capline, 'caplineMinusBaseline': capline - baseline, 'templateVersion': version,
                         'alignmentRectInsets': {'top': top, 'left': left, 'bottom': bottom, 'right': right},
                         'isAlignmentRect': not bool(flags & 1), 'isInterpolatable': version >= 3 and bool(flags & 4),
                         'availableSizeRecords': sizes, 'availablePointSizes': [item['pointSize'] for item in sizes]},
        'resource1019': {'version': render_version, 'renderingMode': render_mode,
                         'containsMulticolorLayers': bool(render_flags & 1),
                         'containsHierarchicalLayers': bool(render_flags & 2)},
    }


def variant_key(record):
    key = list(record['key'])
    key[14] = 0
    return tuple(key)


def variant_id(name, key):
    return hashlib.sha256(json.dumps([name, key], separators=(',', ':')).encode('utf-8')).hexdigest()[:24]


def default_variant_key(key):
    return (key[0] != 0, key[1] != 0, {0: 0, 5: 1, 4: 2}.get(key[4], 3), key)


def write_gzip(path, data):
    encoded = json.dumps(data, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_bytes(gzip.compress(encoded, compresslevel=6, mtime=0))


def style_node(modes, attributes=None):
    node = layer_tree([{'contours': [], 'sourceModeStyles': modes}])[0]
    node.pop('sourceLayerIndex')
    node.pop('components')
    attributes = attributes or {}
    common = modes.get('monochrome', {})
    for source, target in [('-sfsymbols-always-pulses', 'canPulse'), ('-sfsymbols-always-rotates', 'canRotate'),
                           ('-sfsymbols-vc-continuous', 'variableContinuous')]:
        if source in common:
            node[target] = common[source] == 'true'
    rendered_modes = [value for key, value in modes.items() if key in ['monochrome', 'hierarchical', 'multicolor']]
    if rendered_modes and all(value.get('-sfsymbols-clear-behind') == 'true' for value in rendered_modes):
        node['clearBehind'] = True
    if 'opacity' in common and common.get('-sfsymbols-clear-behind') != 'true':
        node['opacity'] = float(common['opacity']) * 100
    if float(common.get('opacity', 1)) == 0 and common.get('-sfsymbols-clear-behind') == 'true':
        node['monochrome'] = 'erase'
    multicolor = modes.get('multicolor', {})
    if 'opacity' in multicolor:
        node['multicolor']['opacity'] = float(multicolor['opacity']) * 100
    if 'fill' in multicolor:
        node['multicolor']['color'] = multicolor['fill']
    for mode, field in [('monochrome', 'monochrome'), ('hierarchical', 'hierarchical'), ('multicolor', 'multicolor')]:
        if mode not in modes:
            node.pop(field, None)
    if 'shapeGroup' in modes:
        node['shapeGroupBehavior'] = modes['shapeGroup'].get('-sfsymbols-fill-action', 'draw')
    node['sourceAttributes'] = attributes
    return node


def circle_commands(attributes):
    cx, cy, radius = [float(attributes.get(key, 0)) for key in ['cx', 'cy', 'r']]
    if radius <= 0:
        return []
    # Original CoreGraphics append_ellipse uses this double and starts at +X.
    control = struct.unpack('<d', struct.pack('<Q', 0x3fe1ac51114f0eaf))[0] * radius
    return [['M', cx + radius, cy],
            ['C', cx + radius, cy + control, cx + control, cy + radius, cx, cy + radius],
            ['C', cx - control, cy + radius, cx - radius, cy + control, cx - radius, cy],
            ['C', cx - radius, cy - control, cx - control, cy - radius, cx, cy - radius],
            ['C', cx + control, cy - radius, cx + radius, cy - control, cx + radius, cy], ['Z']]


def attachment_parameter(regular, ultralight, black, coefficients):
    r, u, b = [float32(value) for value in [regular, ultralight, black]]
    uc, bc = [float32(value) for value in coefficients]
    delta_u, delta_b = float32(u - r), float32(b - r)
    deltas = float32(float32(delta_u * uc) + float32(delta_b * bc))
    return float32(deltas + r)


def system_document(document, circle_builder=None):
    root = document['tree']
    shape_container = next((child for child in root['children'] if child.get('id') == 's'), None)
    if shape_container is None:
        raise ValueError('Missing original system shape container')
    outer = matrix(shape_container['attributes'].get('transform'))
    layers = []
    geometry_nodes = []

    def geometry(node, inherited):
        local = multiply(inherited, matrix(node['attributes'].get('transform')))
        if node['tag'] in ['path', 'circle']:
            if node['tag'] == 'path':
                contours = contours_from_commands(node['attributes']['d'], local)
            else:
                contours = contours_from_commands((circle_builder or circle_commands)(node['attributes']), local)
            sets = compiled_keyframe_sets(node['attributes'].get('data-clipstroke-keyframes'))
            layers.append({'contours': contours, 'clipStrokeKeyframes': sets[0] if sets else [],
                           'clipStrokeKeyframeSets': sets, 'sourceModeStyles': resolved_styles(document, node),
                           'sourceClassIndices': node['classIndices'],
                           'sourceAttributes': {key: value for key, value in node['attributes'].items() if key != 'd'}})
            geometry_nodes.append(node)
        elif node['tag'] != 'g':
            raise ValueError(f'Unsupported original system shape: {node["tag"]}')
        elif node['classIndices'] or any(key != 'transform' for key in node['attributes']):
            raise ValueError('Unexpected semantic group inside original shape container')
        for child in node['children']:
            geometry(child, local)
    for child in shape_container['children']:
        geometry(child, IDENTITY)
    components = []
    for path_index, layer in enumerate(layers):
        components.extend((path_index, local_index) for local_index in range(len(layer['contours'])))
    semantic = next((child for child in root['children'] if child.get('id') == 'LayerTree'), None)
    if semantic is None:
        tree = []
        cursor = 0
        for index, (layer, source_node) in enumerate(zip(layers, geometry_nodes)):
            node = style_node(layer['sourceModeStyles'], layer['sourceAttributes'])
            node.update({'type': 'layer', 'sourceLayerIndex': index,
                         'components': list(range(cursor, cursor + len(layer['contours'])))})
            cursor += len(layer['contours'])
            tree.append(node)
    else:
        def semantic_node(source_node, inherited_modes=None):
            modes = resolved_styles(document, source_node)
            if inherited_modes:
                modes = {**inherited_modes, **modes}
            node = style_node(modes, source_node['attributes'])
            index_styles = modes.get('shapeGroup', modes.get('monochrome', {}))
            indices = [int(value) for value in index_styles.get('-sfsymbols-indices', '').split()]
            if any(index < 0 or index >= len(components) for index in indices):
                raise ValueError('Original layer tree contains an invalid contour index')
            children = source_node['children']
            if children:
                node.update({'type': 'shapeGroup', 'children': [semantic_node(child, modes) for child in children]})
            else:
                if not indices:
                    raise ValueError('Original semantic leaf has no contour indices')
                path_indices = sorted({components[index][0] for index in indices})
                node.update({'type': 'layer', 'components': indices, 'sourcePathIndices': path_indices,
                             'sourcePathContourIndices': [[components[index][0], components[index][1]] for index in indices]})
                if len(path_indices) == 1:
                    node['sourceLayerIndex'] = path_indices[0]
                node['systemContourSubset'] = True
            return node
        tree = [semantic_node(child) for child in semantic['children']]
    metadata_nodes = [{key: value for key, value in child.items() if key != 'children'}
                      for child in root['children'] if child.get('id') not in ['s', 'LayerTree']]
    return {'layers': layers, 'outer': outer, 'tree': tree, 'metadataNodes': metadata_nodes}


def merge_selected_paths(source_layers, pairs, modes, attributes):
    remap = {tuple(pair): index for index, pair in enumerate(pairs)}
    if len(remap) != len(pairs):
        raise ValueError('Native logical layer repeats a source contour')
    merged_sets = []
    selections = []
    for path_index in dict.fromkeys(path for path, _ in pairs):
        selected = {local for path, local in pairs if path == path_index}
        for set_index, frames in enumerate(source_layers[path_index]['clipStrokeKeyframeSets']):
            used = {math.floor(frame[field]) for frame in frames for field in ['first', 'second']}
            if not used.intersection(selected):
                continue
            if not used.issubset(selected):
                raise ValueError('Compiled stroke keyframe set crosses native logical layer boundaries')
            while len(merged_sets) <= set_index:
                merged_sets.append([])
            for frame in frames:
                converted = dict(frame)
                for field in ['first', 'second']:
                    value = frame[field]
                    local = math.floor(value)
                    converted[field] = float32(remap[(path_index, local)] + value - local)
                merged_sets[set_index].append(converted)
            selections.append({'sourcePathIndex': path_index, 'sourceSetIndex': set_index})
    return {'contours': [source_layers[path]['contours'][local] for path, local in pairs],
            'clipStrokeKeyframes': merged_sets[0] if merged_sets else [], 'clipStrokeKeyframeSets': merged_sets,
            'sourceModeStyles': modes, 'sourceAttributes': attributes,
            'sourcePathContourIndices': pairs, 'systemClipStrokeSourceSelections': selections}


def native_selector_document(document):
    decoded = system_document(document)
    source_layers = decoded['layers']
    components = [(path, local) for path, layer in enumerate(source_layers) for local in range(len(layer['contours']))]
    component_indices = {pair: index for index, pair in enumerate(components)}
    defaults = resolved_styles(document, {'classIndices': []})['defaults']
    semantic_root = next((child for child in document['tree']['children'] if child.get('id') == 'LayerTree'), None)
    layers = []
    tree = []

    def program(modes, pairs, attributes, mode, native_index, type_name='layer', children=None):
        node = style_node(modes, attributes)
        node.update({'type': type_name, 'systemRenderingMode': mode, 'systemNativeLayerIndex': native_index})
        if children is not None:
            node['children'] = children
            return node
        node.update({'sourceLayerIndex': len(layers), 'components': [component_indices[tuple(pair)] for pair in pairs],
                     'sourcePathIndices': list(dict.fromkeys(path for path, _ in pairs)),
                     'sourcePathContourIndices': pairs})
        layers.append(merge_selected_paths(source_layers, pairs, modes, attributes))
        return node

    for mode_index, mode in enumerate(['monochrome', 'multicolor', 'hierarchical']):
        if semantic_root is None:
            named = {}
            for layer in source_layers:
                for index in layer['sourceClassIndices']:
                    style = document['styles'][index]
                    if style['selectorType'] == 128 + mode_index:
                        named.setdefault(style['selector'], (index, style))
            matched = []
            for style_index, style in named.values():
                paths = [index for index, layer in enumerate(source_layers) if style_index in layer['sourceClassIndices']]
                if paths:
                    matched.append((style, paths))
            # Original CoreUI switches to suffix integers when lexical ordering would place 10 before 2.
            matched.sort(key=(lambda item: item[0]['identifier']) if len(matched) > 9 else (lambda item: item[0]['selector']))
            for native_index, (style, paths) in enumerate(matched):
                modes = {'defaults': defaults, mode: {'selector': style['selector'], **declarations(style)}}
                pairs = [[path, local] for path in paths for local in range(len(source_layers[path]['contours']))]
                tree.append(program(modes, pairs, {}, mode, native_index))
        else:
            def semantic_node(source_node, native_index, inherited=None):
                candidates = [document['styles'][index] for index in source_node['classIndices']]
                own = [style for style in candidates if style['selectorType'] == 128 + mode_index]
                shape = [style for style in candidates if style['selectorType'] == 131]
                if len(own) > 1 or len(shape) > 1:
                    raise ValueError('Semantic tree has multiple matching native layer styles')
                if own:
                    style = own[0]
                    modes = {'defaults': defaults, mode: {'selector': style['selector'], **declarations(style)}}
                elif inherited:
                    modes = copy.deepcopy(inherited)
                else:
                    return None
                if shape:
                    modes['shapeGroup'] = {'selector': shape[0]['selector'], **declarations(shape[0])}
                if source_node['children']:
                    children = [semantic_node(child, native_index, modes) for child in source_node['children']]
                    if any(child is None for child in children):
                        raise ValueError('A semantic shape child has no native rendering style')
                    return program(modes, [], source_node['attributes'], mode, native_index, 'shapeGroup', children)
                selected = modes.get('shapeGroup', modes[mode])
                indices = [int(value) for value in selected.get('-sfsymbols-indices', '').split()]
                if not indices or any(index < 0 or index >= len(components) for index in indices):
                    raise ValueError('Native semantic leaf has invalid source contour indices')
                return program(modes, [list(components[index]) for index in indices], source_node['attributes'], mode, native_index)
            for native_index, child in enumerate(semantic_root['children']):
                node = semantic_node(child, native_index)
                if node is not None:
                    tree.append(node)
    return {**decoded, 'layers': layers, 'tree': tree, 'rawLayers': source_layers,
            'sourceStyleRecords': document['styles'], 'selectorLayers': True}


def logical_compiled_layers(source_layers, tree):
    leaves = []
    def visit(nodes):
        for node in nodes:
            if node['type'] == 'layer':
                leaves.append(node)
            else:
                visit(node['children'])
    visit(tree)
    if not any(node.get('systemContourSubset') for node in leaves):
        return source_layers, False
    logical = []
    for node in leaves:
        mapping = [tuple(pair) for pair in node['sourcePathContourIndices']]
        if len(set(mapping)) != len(mapping):
            raise ValueError('Original semantic leaf repeats a source contour')
        remap = {pair: index for index, pair in enumerate(mapping)}
        sets = []
        selections = []
        for path_index in node['sourcePathIndices']:
            selected = {local for path, local in mapping if path == path_index}
            source = source_layers[path_index]
            for set_index, frames in enumerate(source['clipStrokeKeyframeSets']):
                used = {math.floor(frame[field]) for frame in frames for field in ['first', 'second']}
                if not used.intersection(selected):
                    continue
                if not used.issubset(selected):
                    raise ValueError('Compiled stroke keyframe set crosses semantic leaf boundaries')
                remapped = []
                for frame in frames:
                    converted = dict(frame)
                    for field in ['first', 'second']:
                        value = frame[field]
                        local = math.floor(value)
                        converted[field] = float32(remap[(path_index, local)] + (value - local))
                    remapped.append(converted)
                sets.append(remapped)
                selections.append({'sourcePathIndex': path_index, 'sourceSetIndex': set_index})
        logical.append({'contours': [source_layers[path]['contours'][local] for path, local in mapping],
                        'clipStrokeKeyframes': sets[0] if sets else [], 'clipStrokeKeyframeSets': sets,
                        'sourceModeStyles': node['sourceModeStyles'], 'sourceAttributes': node['sourceAttributes'],
                        'sourcePathContourIndices': node['sourcePathContourIndices'],
                        'systemClipStrokeSourceSelections': selections,
                        'systemAttachmentSource': {mode: style['-sfsymbols-clipstroke-attachments']
                                                   for mode, style in node['sourceModeStyles'].items()
                                                   if '-sfsymbols-clipstroke-attachments' in style}})
        node['sourceLayerIndex'] = len(logical) - 1
    return logical, True


class NativeFactory:
    def __init__(self, module_path):
        self.module_path = Path(module_path)
        spec = importlib.util.spec_from_file_location('sfcompose_catalog_native', self.module_path)
        module = importlib.util.module_from_spec(spec)
        sys.path.insert(0, str(self.module_path.resolve().parent))
        try:
            spec.loader.exec_module(module)
        finally:
            sys.path.pop(0)
        self.module = module
        self.instance = None
        self.groups = 0

    def get(self):
        if self.instance is None or self.groups % 32 == 0:
            self.instance = self.module.NativeClipInterpolator()
        self.groups += 1
        return self.instance


def sample_variant(name, records, source_dir, samples, weights, native_factory, circle_builder=None, units_per_em=None, selector_layers=True):
    masters = {}
    for record in records:
        metadata = decode_metadata(record)
        document = read_bvg(Path(source_dir) / record['file'])
        decoded = native_selector_document(document) if selector_layers else system_document(document, circle_builder)
        masters[record['key'][14]] = {'record': record, 'metadata': metadata, 'document': document, **decoded}
    if set(masters) != {1, 4, 9}:
        raise ValueError('Original system rendition variant does not have UL/Regular/Black masters')
    ultralight, regular, black = [masters[index] for index in [1, 4, 9]]
    if any(record['tree'] != regular['tree'] for record in [ultralight, black]):
        # Geometry-independent source attributes may contain the source keyframe list.
        def tree_structure(tree):
            return [{key: tree_structure(value) if key == 'children' else value
                     for key, value in node.items() if key != 'sourceAttributes'} for node in tree]
        if any(tree_structure(record['tree']) != tree_structure(regular['tree']) for record in [ultralight, black]):
            raise ValueError('Original system layer structure/styles differ across masters')
    if any(len(record['layers']) != len(regular['layers']) for record in masters.values()):
        raise ValueError('Original system source path counts differ across masters')
    native = native_factory.get() if any(layer['clipStrokeKeyframeSets'] for layer in regular['layers']) else None
    for master in masters.values():
        for layer in master['layers']:
            layer['canvasContours'] = canvas_contours(layer['contours'], master['outer'])
            if layer['clipStrokeKeyframeSets']:
                layer['lengthData'] = native.length_data(layer['canvasContours'])
    defaults = resolved_styles(regular['document'], {'classIndices': []})['defaults']
    if units_per_em is None:
        raise ValueError('Catalog units per em must come from the source index')
    regular_metrics = regular['metadata']['resource1018']
    base = {'name': name, 'upm': units_per_em,
            'capHeight': round(regular_metrics['capline'] * units_per_em / regular_metrics['standardPointSize']),
            'sourceKind': 'macOS-system-CoreGlyphs', 'layerTree': regular['tree'], 'scales': {},
            'sourceSystemRenditions': [{**master['record'], **master['metadata']} for master in masters.values()],
            'metadata': {'sourceKind': 'macOS-system-CoreGlyphs', 'systemModeDefaults': defaults,
                         'systemDrawReversesMotionGroups': defaults.get('-sfsymbols-draw-reverses-motion-groups') == 'true',
                         'systemMetadataNodes': regular['metadataNodes']},
            'systemDrawReversesMotionGroups': defaults.get('-sfsymbols-draw-reverses-motion-groups') == 'true'}
    base['systemDynamicAnchorMasters'] = {
        str(weight): [float(value) for value in next(node for node in master['metadataNodes'] if node['id'] == 'da')['attributes']['data-d'].split()]
        for weight, master in masters.items() if any(node['id'] == 'da' for node in master['metadataNodes'])}
    if selector_layers:
        base['systemLayerSchema'] = 'native-selectors-v1'
        base['systemSourceStyleRecords'] = regular['sourceStyleRecords']
        base['systemSourcePathMasters'] = {str(weight): master['rawLayers'] for weight, master in masters.items()}
    if '-sfsymbols-wiggle-style' in defaults:
        wiggle_style = defaults['-sfsymbols-wiggle-style']
        if wiggle_style in ['clockwise', 'counterClockwise']:
            base['metadata']['preferredWiggleDirection'] = 'counterclockwise' if wiggle_style == 'counterClockwise' else wiggle_style
        elif wiggle_style == 'linear' and '-sfsymbols-wiggle-angle' in defaults:
            base['metadata']['preferredWiggleDirection'] = 'custom'
            base['metadata']['customWiggleAngle'] = float(defaults['-sfsymbols-wiggle-angle'])
    inverse_regular = inverse(regular['outer'])
    for sample in samples:
        coefficients = sample['coefficients']
        scale = sample['sizeTransform'][0]
        layers = []
        for r, u, b in zip(regular['layers'], ultralight['layers'], black['layers']):
            canvas = interpolate_contours(r['canvasContours'], u['canvasContours'], b['canvasContours'], coefficients, scale)
            sets = []
            if len({len(r['clipStrokeKeyframeSets']), len(u['clipStrokeKeyframeSets']), len(b['clipStrokeKeyframeSets'])}) != 1:
                raise ValueError('Original system keyframe set counts differ across masters')
            if r['clipStrokeKeyframeSets']:
                length_data = native.length_data(canvas)
                for rset, uset, bset in zip(r['clipStrokeKeyframeSets'], u['clipStrokeKeyframeSets'], b['clipStrokeKeyframeSets']):
                    sets.append(native.interpolate({'lengthData': r['lengthData'], 'keyframes': rset},
                                                   {'lengthData': u['lengthData'], 'keyframes': uset},
                                                   {'lengthData': b['lengthData'], 'keyframes': bset}, length_data, coefficients))
            stored = []
            for contour in canvas:
                points = []
                for x, y in zip(contour['points'][::2], contour['points'][1::2]):
                    px, py = transform_point(inverse_regular, x, y)
                    points.extend([px, -py])
                stored.append({'verbs': contour['verbs'], 'points': points})
            layers.append({'contours': stored, 'clipStrokeKeyframes': sets[0] if sets else [], 'clipStrokeKeyframeSets': sets,
                           'sourceModeStyles': r['sourceModeStyles'], 'sourceAttributes': r['sourceAttributes'],
                           'systemAttachmentSource': {mode: style['-sfsymbols-clipstroke-attachments']
                                                      for mode, style in r['sourceModeStyles'].items()
                                                      if '-sfsymbols-clipstroke-attachments' in style}})
        def mixed(getter):
            return blend(getter(regular), getter(ultralight), getter(black), coefficients) * scale
        metric = lambda master: master['metadata']['resource1018']
        canvas_width = mixed(lambda master: master['document']['tree']['attributes']['viewBox'][2])
        canvas_height = mixed(lambda master: master['document']['tree']['attributes']['viewBox'][3])
        insets = {key: mixed(lambda master: metric(master)['alignmentRectInsets'][key]) for key in ['top', 'left', 'bottom', 'right']}
        center_x = (canvas_width + insets['right'] - insets['left']) * .5
        center_y = mixed(lambda master: metric(master)['capline'] * .5 + metric(master)['baseline'])
        mx, my = transform_point(inverse_regular, center_x, canvas_height - center_y)
        if selector_layers:
            compiled, has_semantic_tree = layers, False
            source_contours = []
            for r, u, b in zip(regular['rawLayers'], ultralight['rawLayers'], black['rawLayers']):
                canvas = interpolate_contours(canvas_contours(r['contours'], regular['outer']),
                                              canvas_contours(u['contours'], ultralight['outer']),
                                              canvas_contours(b['contours'], black['outer']), coefficients, scale)
                for contour in canvas:
                    points = []
                    for x, y in zip(contour['points'][::2], contour['points'][1::2]):
                        px, py = transform_point(inverse_regular, x, y)
                        points.extend([px, -py])
                    source_contours.append({'verbs': contour['verbs'], 'points': points})
        else:
            compiled, has_semantic_tree = logical_compiled_layers(layers, base['layerTree'])
            source_contours = [contour for layer in layers for contour in layer['contours']]
        master = {'weight': weights[sample['weight']], 'nativeWeight': sample['weightEnum'],
                  'advance': (canvas_width + insets['left'] + insets['right']) / abs(regular['outer'][0]),
                  'metricCenterX': mx, 'metricCenterY': -my,
                  'contours': source_contours, 'compiledLayers': compiled,
                  'systemTransform': regular['outer'], 'systemSampling': {'coefficients': coefficients, 'sizeScale': scale,
                      'sizeTranslation': 0, 'nativeCanvasSize': [canvas_width, canvas_height], 'nativeMetricCenter': [center_x, center_y]},
                  'systemMetrics': {'standardPointSize': metric(regular)['standardPointSize'],
                                    'baseline': mixed(lambda master: metric(master)['baseline']),
                                    'capline': mixed(lambda master: metric(master)['capline']), 'alignmentRectInsets': insets}}
        if has_semantic_tree:
            master['systemSourcePathLayers'] = layers
        master['systemAnchors'] = {}
        anchor_nodes = {key: {node['id']: node for node in source['metadataNodes']}
                        for key, source in masters.items()}
        if any('ra' in nodes for nodes in anchor_nodes.values()):
            if not all('ra' in nodes for nodes in anchor_nodes.values()):
                raise ValueError('Rotation anchor presence differs across original masters')
            points = []
            for axis in ['cx', 'cy']:
                points.append(blend(float(anchor_nodes[4]['ra']['attributes'][axis]),
                                    float(anchor_nodes[1]['ra']['attributes'][axis]),
                                    float(anchor_nodes[9]['ra']['attributes'][axis]), coefficients) * scale)
            px, py = transform_point(inverse_regular, *points)
            master['systemAnchors']['rotation'] = [px, -py]
        if any('da' in nodes for nodes in anchor_nodes.values()):
            if not all('da' in nodes for nodes in anchor_nodes.values()):
                raise ValueError('Dynamic anchor presence differs across original masters')
            anchors = {key: [float(value) for value in nodes['da']['attributes']['data-d'].split()]
                       for key, nodes in anchor_nodes.items()}
            if any(not values or values[0] != 0 or (len(values) - 1) % 4 for values in anchors.values()):
                raise ValueError('Unsupported original dynamic anchor encoding')
            if len({len(values) for values in anchors.values()}) != 1:
                raise ValueError('Dynamic anchor record counts differ across original masters')
            frames = []
            native_frames = []
            for cursor in range(1, len(anchors[4]), 4):
                values = [attachment_parameter(anchors[4][index], anchors[1][index], anchors[9][index], coefficients)
                          for index in range(cursor, cursor + 2)]
                values.extend(blend(anchors[4][index], anchors[1][index], anchors[9][index], coefficients)
                              for index in range(cursor + 2, cursor + 4))
                px, py = transform_point(inverse_regular, values[2] * scale, values[3] * scale)
                frames.append({'sourceParameters': values[:2], 'point': [px, -py]})
                native_frames.append([*values[:2], values[2] * scale, values[3] * scale])
            master['systemAnchors']['dynamicFrames'] = frames
            master['systemDynamicAnchorSource'] = native_frames
            if frames:
                master['systemAnchors']['dynamic'] = frames[0]['point']
        base['scales'].setdefault(sample['scale'], {'masters': []})['masters'].append(master)
    return base


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--samples', type=Path, required=True)
    parser.add_argument('--base-index', type=Path, required=True)
    parser.add_argument('--native-clip-module', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--name', action='append')
    parser.add_argument('--limit', type=int)
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--circle-module', type=Path)
    parser.add_argument('--catalog', type=Path)
    parser.add_argument('--selector-layers', action='store_true', default=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
    catalog_metadata = manifest.get('catalogMetadata', {})
    if args.catalog:
        catalog = CoreGlyphsCatalog(args.catalog)
        if hashlib.sha256(catalog.data).hexdigest() != manifest['sourceSha256']:
            raise ValueError('Named metadata catalog does not match the extracted source catalog')
        catalog_metadata = catalog.metadata()
        del catalog
    sample_document = json.loads(args.samples.read_text(encoding='utf-8'))
    base_index = json.loads(args.base_index.read_text(encoding='utf-8'))
    units_per_em = base_index['source']['upm']
    weights = {name.lower(): value for name, value in base_index['weights'].items()}
    groups = collections.defaultdict(lambda: collections.defaultdict(list))
    for record in manifest['records']:
        if not args.name or record['name'] in args.name:
            groups[record['name']][variant_key(record)].append(record)
    args.output.mkdir(parents=True, exist_ok=True)
    native_factory = NativeFactory(args.native_clip_module)
    circle_builder = None
    if args.circle_module:
        circle_spec = importlib.util.spec_from_file_location('sfcompose_system_circle', args.circle_module)
        circle_module = importlib.util.module_from_spec(circle_spec)
        circle_spec.loader.exec_module(circle_module)
        circle_builder = circle_module.circle_commands
    generation_inputs = {'manifestSha256': digest(args.manifest), 'samplesSha256': digest(args.samples),
                         'nativeClipModuleSha256': digest(args.native_clip_module),
                         'sourceSha256': {name: digest(Path(__file__).parent / name)
                                          for name in ['import_system_catalog.py', 'import_system_symbols.py', 'bvg.py', 'bvg_numbers.py']},
                         'weights': base_index['weights'], 'unitsPerEm': units_per_em}
    generation_inputs['catalogMetadata'] = catalog_metadata
    generation_inputs['selectorLayers'] = args.selector_layers
    fingerprint = hashlib.sha256(json.dumps(generation_inputs, sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest()
    report = {'sourceKind': 'macOS-system-CoreGlyphs', 'sourceManifestSha256': digest(args.manifest),
              'sourceCarSha256': manifest['sourceSha256'], 'samplesSha256': digest(args.samples),
              'nativeClipModuleSha256': digest(args.native_clip_module), 'generationInputs': generation_inputs,
              'generationFingerprint': fingerprint, 'samplesPerVariant': len(sample_document['namedSamples']),
              'symbols': [], 'failures': [], 'variants': 0}
    start = time.monotonic()
    for name_index, name in enumerate(sorted(groups)[:args.limit]):
        variants = sorted(groups[name], key=default_variant_key)
        descriptions = [{'id': variant_id(name, key), 'renditionKey': key, 'appearance': key[0],
                         'localization': key[1], 'direction': key[4],
                         'localeTag': next((tag for tag, value in catalog_metadata.get('localizations', {}).items() if value == key[1]), None),
                         'appearanceName': next((tag for tag, value in catalog_metadata.get('appearances', {}).items() if value == key[0]), None),
                         'asset': f'symbols/variants/{variant_id(name, key)}.json.gz' if index else None}
                        for index, key in enumerate(variants)]
        successful = True
        for index, (key, description) in enumerate(zip(variants, descriptions)):
            destination = args.output / ('variants/' + description['id'] + '.json.gz' if index else name + '.json.gz')
            if args.resume and destination.exists():
                retained = json.loads(gzip.decompress(destination.read_bytes()))
                if retained.get('systemGenerationFingerprint') == fingerprint:
                    report['variants'] += 1
                    continue
            try:
                data = sample_variant(name, groups[name][key], args.manifest.parent, sample_document['namedSamples'], weights, native_factory, circle_builder, units_per_em, args.selector_layers)
                data['systemVariantId'] = description['id']
                data['systemRenditionKey'] = key
                data['systemVariants'] = descriptions
                data['systemDefaultSelection'] = 'appearance0-localization0-direction0-then5-then4-lexicographic'
                data['systemGenerationFingerprint'] = fingerprint
                write_gzip(destination, data)
                report['variants'] += 1
            except Exception as error:
                report['failures'].append({'name': name, 'key': key, 'id': description['id'],
                                           'error': str(error), 'errorType': type(error).__name__})
                successful = False
        if successful:
            report['symbols'].append(name)
        if (name_index + 1) % 25 == 0:
            report['elapsedSeconds'] = time.monotonic() - start
            write_json(args.output / 'system-import-report.json', report)
            print(f"{len(report['symbols'])} symbols, {report['variants']} variants, {len(report['failures'])} failures, {report['elapsedSeconds']:.1f}s", flush=True)
    report['elapsedSeconds'] = time.monotonic() - start
    write_json(args.output / 'system-import-report.json', report)
    index = {'formatVersion': base_index['formatVersion'], 'weights': base_index['weights'], 'symbols': report['symbols'],
             'source': {'kind': report['sourceKind'], 'sourceCarSha256': manifest['sourceSha256'], 'upm': units_per_em,
                        'generationFingerprint': fingerprint, **catalog_metadata},
             'drawWeightCoefficients': base_index['drawWeightCoefficients'], 'unsupported': report['failures']}
    write_json(args.output / 'index.json', index)
    print(f"Finished {len(report['symbols'])} symbols/{report['variants']} variants in {report['elapsedSeconds']:.1f}s; {len(report['failures'])} failures")


if __name__ == '__main__':
    main()
