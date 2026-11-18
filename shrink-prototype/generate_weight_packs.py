"""Generate Android resource closures for the static-weight comparison APKs."""

import argparse
import gzip
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'app/src/main/assets'
SCALES = ('small', 'medium', 'large')
ENTRIES = (
    ('WifiRegular', 'wifi', 'Regular'),
    ('WifiBold', 'wifi', 'Bold'),
    ('ArabicZeroRegular', '0.circle.ar', 'Regular'),
    ('AirtagForwardRegular', 'airtag.radiowaves.forward.fill', 'Regular'),
    ('ArrowForwardRegular', 'arrow.forward', 'Regular'),
    ('AirplayAudioRegular', 'airplay.audio', 'Regular'),
    ('CellularBarsRegular', 'cellularbars', 'Regular'),
    ('HomepodMiniArrowForwardRegular', 'homepod.mini.arrow.forward', 'Regular'),
    ('ArrowClockwiseRegular', 'arrow.clockwise', 'Regular'),
    ('PersonFillBold', 'person.fill', 'Bold'),
)
USED = set(entry[0] for entry in ENTRIES) - {'WifiBold', 'PersonFillBold'}
DOCUMENT_KEYS = ('name', 'upm', 'capHeight', 'layerTree', 'metadata', 'systemVariantId', 'systemVariants')


def read_asset(path):
    keys = (path.removesuffix('.gz') + '.sfgz', path, path + '.gz')
    for key in dict.fromkeys(keys):
        file = ASSETS / key
        if file.is_file():
            return file.read_bytes()
    raise FileNotFoundError(path)


def read_json(path):
    data = read_asset(path)
    return json.loads(gzip.decompress(data) if data.startswith(b'\x1f\x8b') else data)


def pack(data):
    return gzip.compress(json.dumps(data, ensure_ascii=False, separators=(',', ':')).encode(), mtime=0)


def interpolate_contours(anchors, sample):
    ul, reg, black = (a['contours'] for a in anchors)
    u, b = sample['systemSampling']['coefficients']
    s = sample['systemSampling']['sizeScale']
    a, xy, yx, d, tx, ty = sample['systemTransform']
    determinant = a * d - xy * yx
    dx, dy = (d * tx - yx * ty) / determinant, (xy * tx - a * ty) / determinant
    output = []
    for left, center, right in zip(ul, reg, black, strict=True):
        if not left['verbs'] == center['verbs'] == right['verbs']:
            raise ValueError('Incompatible master topology')
        points = [(r + u * (l - r) + b * (h - r)) * s + (s - 1) * (dy if i % 2 else dx)
                  for i, (l, r, h) in enumerate(zip(left['points'], center['points'], right['points'], strict=True))]
        output.append({'verbs': center['verbs'], 'points': points})
    return output


def static_document(source, weight, weights, compressed_geometry):
    result = {key: source[key] for key in DOCUMENT_KEYS}
    anchors = [next(m for m in source['scales']['small']['masters'] if m['weight'] == weights[name])
               for name in ('Ultralight', 'Regular', 'Black')]
    result['scales'] = {}
    if compressed_geometry:
        result['outlineMasters'] = [m['contours'] for m in anchors]
    for scale in SCALES:
        sample = next(m for m in source['scales'][scale]['masters'] if m['weight'] == weights[weight]).copy()
        # The renderer gets geometry from the top-level contours. Compiled layers supply Draw data.
        sample['compiledLayers'] = [{k: v for k, v in layer.items() if k != 'contours'} for layer in sample['compiledLayers']]
        if compressed_geometry:
            actual = interpolate_contours(anchors, sample)
            for expected, reconstructed in zip(sample.pop('contours'), actual, strict=True):
                if expected['verbs'] != reconstructed['verbs'] or any(abs(x - y) > 1e-8
                        for x, y in zip(expected['points'], reconstructed['points'], strict=True)):
                    raise ValueError(f'{source["name"]} {weight} {scale}: cannot reconstruct outline')
        result['scales'][scale] = {'masters': [sample]}
    return result


def generate(output):
    source_index = read_json('symbols/index.json')
    selection_path = source_index['resourceSelectionAsset']
    selection = read_json(selection_path)
    kotlin = ['package com.nevoit.shrinkprototype.catalog', '',
              'object ExpandedSymbols {']
    compact_accessors = []
    xml = ET.Element('resources')
    inventory = {}
    raw_dir = output / 'res/raw'
    raw_dir.mkdir(parents=True, exist_ok=True)

    for accessor, name, weight in ENTRIES:
        rtl = selection['rightToLeftNames'].get(name)
        names = [name, rtl] if rtl and rtl != name else [name]
        documents = {}
        variants = {}
        for resource_name in names:
            default_path = f'symbols/{resource_name}.json'
            default = read_json(default_path)
            for variant in default['systemVariants']:
                path = variant['asset'] or default_path
                documents[path] = read_json(path) if variant['asset'] else default
                variants[path] = {'resourceName': resource_name, 'locale': variant['localeTag'],
                                  'direction': variant['direction'], 'appearance': variant['appearance'],
                                  'variantId': variant['id']}
        ids = {v['variantId'] for v in variants.values()}
        scoped_selection = {**selection, 'packedFor': accessor,
                            'flippableVariants': sorted(ids.intersection(selection['flippableVariants'])),
                            'rightToLeftNames': {name: rtl} if rtl else {}}
        fill_names = set()
        for data in documents.values():
            defaults = data['metadata'].get('systemModeDefaults', {})
            if defaults.get('-sfsymbols-has-fill-images') == 'true':
                fill_names.update(defaults['-sfsymbols-fill-image-names'].split())
        extra_assets = {selection_path: pack(scoped_selection)}
        if fill_names:
            fill_path = 'symbols/fill-images/index.json'
            fills = read_json(fill_path)
            fills['images'] = [v for v in fills['images'] if v['name'] in fill_names]
            extra_assets[fill_path] = pack(fills)
            for image in fills['images']:
                extra_assets[image['asset']] = read_asset(image['asset'])
        paths = list(documents) + list(extra_assets)
        index = {k: source_index[k] for k in ('formatVersion', 'weights', 'drawWeightCoefficients')}
        index.update(symbols=names, packedFor=accessor, packedWeight=weight, resourceSelectionAsset=selection_path, packedFiles=paths,
                     source={k: source_index['source'][k] for k in ('sourceCarSha256', 'localizations', 'appearances')})
        # Both encodings share this index and the same locale/direction manifest.
        for compact, prefix, getters in [(False, 'expanded', kotlin), (True, 'compact', compact_accessors)]:
            stem = f'{prefix}_{accessor.lower()}'
            array = ET.SubElement(xml, 'integer-array', name=stem)
            inventory[stem] = []
            payloads = {'symbols/index.json': pack(index)}
            payloads.update({path: pack(static_document(doc, weight, source_index['weights'], compact))
                             for path, doc in documents.items()})
            payloads.update(extra_assets)
            for i, (path, content) in enumerate(payloads.items()):
                resource = f'{stem}_{i}'
                suffix = '.sfgz' if path not in extra_assets or content.startswith(b'\x1f\x8b') else Path(path).suffix
                file = f'{resource}{suffix}'
                (raw_dir / file).write_bytes(content)
                ET.SubElement(array, 'item').text = f'@raw/{resource}'
                record = {'file': file, 'symbol': name, 'asset': path, 'bytes': len(content)}
                if path in variants:
                    record.update(variants[path], weight=weight, scales=list(SCALES))
                inventory[stem].append(record)
            getters.append(f'    val {accessor} get() = PackedSymbolRef("{name}", "{weight}", R.array.{stem})')
    kotlin += ['}', '', 'object CompactSymbols {', *compact_accessors, '}']
    src = output / 'java/com/nevoit/shrinkprototype/catalog'
    src.mkdir(parents=True, exist_ok=True)
    (src / 'PackedSymbols.kt').write_text('\n'.join(kotlin) + '\n', encoding='utf-8')
    values = output / 'res/values'
    values.mkdir(parents=True, exist_ok=True)
    ET.indent(xml)
    ET.ElementTree(xml).write(values / 'weight_packs.xml', encoding='utf-8', xml_declaration=True)
    (output / 'inventory.json').write_text(json.dumps(inventory, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    generate(parser.parse_args().output)
