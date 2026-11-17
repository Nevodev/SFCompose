"""Import original SF Symbols outlines and annotations for the Android renderer.

Requires fontTools. When --decoded-dir is omitted, cryptography is also needed
to decode the font's private tables from the caller-supplied source framework.
The importer never downloads artifacts or installs packages.
"""
from pathlib import Path
import argparse
from collections import Counter
import csv
import hashlib
import io
import json
import re
import sys

from fontTools.pens.basePen import BasePen
from fontTools.ttLib import TTFont


class OutlinePen(BasePen):
    def __init__(self, glyph_set):
        super().__init__(glyph_set)
        self.contours = []
        self.current = None

    def _append(self, verb, points=()):
        if self.current is None:
            raise ValueError('Path command before moveTo')
        self.current['verbs'] += verb
        for point in points:
            self.current['points'].extend(float(coordinate) for coordinate in point)

    def _moveTo(self, point):
        if self.current is not None:
            raise ValueError('Unclosed font contour')
        self.current = {'verbs': '', 'points': []}
        self._append('M', [point])

    def _lineTo(self, point):
        self._append('L', [point])

    def _curveToOne(self, first, second, end):
        self._append('C', [first, second, end])

    def _qCurveToOne(self, control, end):
        self._append('Q', [control, end])

    def _closePath(self):
        self._append('Z')
        self.contours.append(self.current)
        self.current = None

    def _endPath(self):
        raise ValueError('Open contour in source font')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False), encoding='utf-8')


def read_metadata(directory):
    csv_path = directory / 'symp.csv'
    if csv_path.is_file():
        return list(csv.DictReader(io.StringIO(csv_path.read_text(encoding='utf-8'))))
    return json.loads((directory / 'symp-rows.json').read_text(encoding='utf-8'))


def layer_component_indices(node):
    if isinstance(node, list):
        for child in node:
            yield from layer_component_indices(child)
    elif isinstance(node, dict):
        yield from node.get('components', [])
        yield from layer_component_indices(node.get('children', []))


def drawing_identifier(row):
    composite = row.get('Composite Symbol PUA', '').strip()
    if row.get('Composite Symbol Data') == 'TRUE' and re.fullmatch(r'[0-9A-Fa-f]+', composite):
        return composite
    tokens = [token for token in re.split(r'[,;\s]+', row['PUAs']) if token]
    if len(tokens) != 1:
        raise ValueError(f'Expected one source PUA, found {len(tokens)}')
    return tokens[0]


def contours_from_serialized_path(path):
    points = [float(value) for value in path['points'].split()]
    sizes = {'M': 2, 'L': 2, 'Q': 4, 'C': 6, 'Z': 0}
    contours = []
    current = None
    cursor = 0
    for verb in path['elements']:
        if verb == 'M':
            if current is not None:
                raise ValueError('Unclosed serialized contour')
            current = {'verbs': '', 'points': []}
        if current is None or verb not in sizes:
            raise ValueError('Invalid serialized path command')
        size = sizes[verb]
        current['verbs'] += verb
        current['points'].extend(points[cursor:cursor + size])
        cursor += size
        if verb == 'Z':
            contours.append(current)
            current = None
    if current is not None or cursor != len(points):
        raise ValueError('Serialized path coordinate count does not match commands')
    return contours


def synthetic_scales(symbol, weights, tables, upm, cap_height):
    originals = {name: contours_from_serialized_path(symbol[name]['path']) for name in ['ultralight', 'regular', 'black']}
    topology = [[contour['verbs'] for contour in originals[name]] for name in ['ultralight', 'regular', 'black']]
    if topology[0] != topology[1] or topology[1] != topology[2]:
        raise ValueError('Synthetic source master topology differs')
    by_scale = {table['scale']: table for table in tables}
    named_weights = sorted(weights.values())
    result = {}
    for scale_name, scale in [('small', 1.0), ('medium', 1.276), ('large', 1.647)]:
        table = by_scale[scale]
        values = sorted(table['values'])
        if len(values) != len(named_weights):
            raise ValueError('Synthetic source weights do not match the original font instances')
        masters = []
        for weight, (native_weight, light_coefficient, bold_coefficient) in zip(named_weights, values):
            contours = []
            shift_y = (1.0 - scale) * cap_height / 2.0
            for index, regular in enumerate(originals['regular']):
                light = originals['ultralight'][index]['points']
                bold = originals['black'][index]['points']
                coordinates = [(value + (light[point] - value) * light_coefficient + (bold[point] - value) * bold_coefficient) * scale + (shift_y if point % 2 else 0.0) for point, value in enumerate(regular['points'])]
                contours.append({'verbs': regular['verbs'], 'points': coordinates})
            advance = (symbol['regular']['advance'] + (symbol['ultralight']['advance'] - symbol['regular']['advance']) * light_coefficient + (symbol['black']['advance'] - symbol['regular']['advance']) * bold_coefficient) * scale
            masters.append({'weight': weight, 'advance': advance, 'contours': contours, 'nativeWeight': native_weight})
        result[scale_name] = {'sourceIdentifier': symbol['id'], 'masters': masters}
    return result


def import_symbols(font_path, decoded_dir, output, selected_names=None):
    output.mkdir(parents=True, exist_ok=True)
    if (output / 'index.json').exists():
        raise ValueError('Output already has an index; choose a new directory to avoid stale assets')
    metadata = read_metadata(decoded_dir)
    original_annotations = json.loads((decoded_dir / 'syls.json').read_text(encoding='utf-8'))
    annotations = {int(key, 16): value for key, value in original_annotations.items() if re.fullmatch(r'[0-9A-Fa-f]+', key)}
    auxiliary = json.loads((decoded_dir / 'syot.json').read_text(encoding='utf-8'))
    synthetic_sources = {record['id']: record for record in auxiliary['symbols']}
    synthetic_table_path = decoded_dir / 'synthetic-scale-interpolation.json'
    synthetic_tables = json.loads(synthetic_table_path.read_text(encoding='utf-8')) if synthetic_table_path.is_file() else None
    source_inventory = json.loads((decoded_dir / 'inventory.json').read_text(encoding='utf-8'))
    font = TTFont(font_path, lazy=True)
    cmap = font.getBestCmap()
    glyph_order = set(font.getGlyphOrder())
    upm = font['head'].unitsPerEm
    cap_height = font['OS/2'].sCapHeight
    if auxiliary['upm'] != upm or auxiliary['capHeight'] != cap_height:
        raise ValueError('Font and decoded outline metadata metrics do not match')
    axis = next(axis for axis in font['fvar'].axes if axis.axisTag == 'wght')
    master_positions = [axis.minValue, axis.defaultValue, axis.maxValue]
    glyph_sets = [(weight, font.getGlyphSet(location={'wght': weight})) for weight in master_positions]
    weights = {font['name'].getDebugName(instance.subfamilyNameID): instance.coordinates['wght'] for instance in font['fvar'].instances}
    available = []
    unsupported = []
    source_diagnostics = []
    record_sizes = []
    source_composite_count = 0
    seen = set()
    for row in metadata:
        name = row['Name']
        if selected_names is not None and name not in selected_names:
            continue
        if name in seen:
            raise ValueError(f'Duplicate canonical name: {name}')
        seen.add(name)
        try:
            if not re.fullmatch(r'[A-Za-z0-9_.-]+', name):
                raise ValueError('Canonical name cannot safely form an asset filename')
            scalar_tokens = [token for token in re.split(r'[,;\s]+', row['PUAs']) if token]
            if len(scalar_tokens) != 1:
                raise ValueError(f'Expected one source PUA, found {len(scalar_tokens)}')
            canonical_identifier = scalar_tokens[0]
            identifier = drawing_identifier(row)
            if identifier in synthetic_sources:
                if synthetic_tables is None:
                    raise ValueError('Decode native synthetic interpolation tables from the original framework')
                annotation = original_annotations.get(identifier)
                if annotation is None:
                    raise ValueError('Synthetic source has no original layer annotations')
                record = dict(annotation)
                scalar = int(identifier.split('|', 1)[0].removeprefix('U+'), 16)
                record.update({'name': name, 'pua': scalar, 'rtl': row['Mirror for RTL'] == 'TRUE', 'defaultMode': row['Default Rendering'].lower(), 'upm': upm, 'capHeight': cap_height, 'scales': synthetic_scales(synthetic_sources[identifier], weights, synthetic_tables, upm, cap_height), 'sourceMetadata': row, 'sourceIdentifier': identifier})
                path = output / (name + '.json')
                write_json(path, record)
                available.append(name)
                record_sizes.append(path.stat().st_size)
                continue
            if not re.fullmatch(r'[0-9A-Fa-f]+', identifier):
                raise ValueError('No original synthetic outlines for the composite source identifier')
            scalar = int(identifier, 16)
            base_glyph = cmap.get(scalar)
            if base_glyph is None:
                raise ValueError('Source PUA has no cmap glyph')
            if not base_glyph.endswith('.medium'):
                raise ValueError(f'Unexpected source scale glyph: {base_glyph}')
            annotation = annotations.get(scalar)
            if annotation is None:
                raise ValueError('Source PUA has no layer annotations')
            indices = list(layer_component_indices(annotation['layerTree']))
            record = dict(annotation)
            canonical_scalar = int(canonical_identifier, 16)
            record.update({'name': name, 'pua': canonical_scalar, 'rtl': row['Mirror for RTL'] == 'TRUE', 'defaultMode': row['Default Rendering'].lower(), 'upm': upm, 'capHeight': cap_height, 'scales': {}, 'sourceMetadata': row})
            if identifier != canonical_identifier:
                record['sourceGlyphPUA'] = scalar
                source_composite_count += 1
            skipped_components = {}
            for scale in ['small', 'medium', 'large']:
                glyph_name = base_glyph.removesuffix('.medium') + '.' + scale
                if glyph_name not in glyph_order:
                    raise ValueError(f'Missing original {scale} glyph')
                masters = []
                topology = None
                for weight, glyph_set in glyph_sets:
                    glyph = glyph_set[glyph_name]
                    pen = OutlinePen(glyph_set)
                    glyph.draw(pen)
                    if pen.current is not None:
                        raise ValueError('Source glyph ended without closing its contour')
                    current_topology = [contour['verbs'] for contour in pen.contours]
                    if topology is not None and topology != current_topology:
                        raise ValueError(f'Outline command topology changes at {scale} weight {weight}')
                    topology = current_topology
                    missing = sorted(set(index for index in indices if index < 0 or index >= len(pen.contours)))
                    if missing:
                        skipped_components[scale] = missing
                    masters.append({'weight': weight, 'advance': float(glyph.width), 'contours': pen.contours})
                record['scales'][scale] = {'glyphName': glyph_name, 'masters': masters}
            if skipped_components:
                # Glyph.contours(at:) skips invalid indices before collecting the
                # remaining source contours. Keep the original tree unchanged.
                record['nativeSkippedComponents'] = skipped_components
                source_diagnostics.append({'name': name, 'nativeSkippedComponents': skipped_components})
            path = output / (name + '.json')
            write_json(path, record)
            available.append(name)
            record_sizes.append(path.stat().st_size)
        except (ValueError, KeyError, IndexError, NotImplementedError) as error:
            unsupported.append({'name': name, 'reason': str(error), 'pua': row.get('PUAs'), 'composite': row.get('Composite Symbol Data') == 'TRUE'})
        if len(seen) % 250 == 0:
            print(f'Imported {len(available)}/{len(seen)} records', flush=True)
    if selected_names is not None:
        for name in sorted(selected_names - seen):
            unsupported.append({'name': name, 'reason': 'No canonical metadata entry'})
    index = {'formatVersion': 1, 'weights': weights, 'symbols': sorted(available), 'source': {'fontSha256': digest(font_path), 'frameworkSha256': source_inventory.get('framework_sha256'), 'privateTables': source_inventory.get('tables'), 'syntheticScaleTablesSha256': source_inventory.get('synthetic_scale_tables_sha256'), 'upm': upm, 'capHeight': cap_height, 'masterWeights': master_positions}, 'unsupported': unsupported, 'sourceDiagnostics': source_diagnostics, 'sourceCompositeGlyphCount': source_composite_count}
    if synthetic_tables is not None:
        by_scale = {table['scale']: table for table in synthetic_tables}
        index['drawWeightCoefficients'] = {
            name: [entry[1:] for entry in sorted(by_scale[scale]['values'])]
            for name, scale in [('small', 1.0), ('medium', 1.276), ('large', 1.647)]
        }
    write_json(output / 'index.json', index)
    report = {'requested': len(seen), 'imported': len(available), 'unsupported': unsupported, 'unsupportedReasons': dict(Counter(item['reason'] for item in unsupported)), 'nativeSkippedComponentSymbols': len(source_diagnostics), 'recordBytes': sum(record_sizes), 'largestRecordBytes': max(record_sizes, default=0), 'sourceCompositeGlyphCount': source_composite_count, 'source': index['source']}
    write_json(output / 'import-report.json', report)
    font.close()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font', type=Path, required=True)
    parser.add_argument('--framework', type=Path)
    parser.add_argument('--decoded-dir', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--names', nargs='+', help='Optional bounded import for inspection')
    args = parser.parse_args()
    decoded_dir = args.decoded_dir
    if decoded_dir is None:
        if args.framework is None:
            parser.error('Provide --decoded-dir or --framework')
        from decode_symbol_tables import decode_tables
        decoded_dir = args.output.parent / (args.output.name + '-decoded-source')
        decode_tables(args.framework, args.font, decoded_dir)
    report = import_symbols(args.font, decoded_dir, args.output, set(args.names) if args.names else None)
    print(json.dumps({key: value for key, value in report.items() if key != 'unsupported'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
