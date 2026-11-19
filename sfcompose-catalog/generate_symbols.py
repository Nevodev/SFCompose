"""Generate independently referenced Android resource closures and Kotlin symbol entries."""

import argparse
from dataclasses import dataclass, field
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'shrink-prototype'))
from generate_weight_packs import ASSETS, SCALES, pack, read_json, resource_closure, static_document

DEFAULT_SYMBOLS = (
    'wifi', 'person', 'person.fill', 'arrow.forward', 'arrow.clockwise', 'airplay.audio',
    'cellularbars', 'homepod.mini.arrow.forward', '0.circle.ar', 'airtag.radiowaves.forward.fill',
)


@dataclass
class Entry:
    name: str | None = None
    resources: dict = field(default_factory=dict)
    children: dict = field(default_factory=dict)


ANIMATION_KEYS = {
    'systemAnchors', 'systemDynamicAnchorSource', 'systemAttachmentSource',
    'clipStrokeKeyframes', 'clipStrokeKeyframeSets', 'data-clipstroke-keyframes',
    '-sfsymbols-clipstroke-attachments',
}


def without_animation(data):
    if isinstance(data, dict):
        return {k: without_animation(v) for k, v in data.items() if k not in ANIMATION_KEYS}
    if isinstance(data, list):
        return [without_animation(v) for v in data]
    return data


def identifier(segment, reserved):
    digits = ('Zero', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine')
    word = ''.join(part[:1].upper() + part[1:] for part in re.split(r'[^a-zA-Z0-9]+', segment) if part)
    if not word:
        raise ValueError(f'Cannot generate a symbol identifier for {segment!r}')
    if word[0].isdigit():
        word = digits[int(word[0])] + word[1:]
    return 'Symbol' + word if word in reserved else word


def entry_for(tree, name, reserved):
    for part in name.split('.'):
        tree = tree.children.setdefault(identifier(part, reserved), Entry())
    if tree.name is not None:
        raise ValueError(f'Kotlin symbol name collision: {tree.name!r} and {name!r}')
    tree.name = name
    return tree


def render_tree(lines, entry, identifier, indent=''):
    superclass = ''
    if entry.name:
        superclass = f' : StaticSymbol("{entry.name}", SymbolWeight.Regular, R.array.{entry.resources["Regular", False]})'
    lines.append(f'{indent}object {identifier}{superclass} {{')
    if entry.name:
        lines.append(f'{indent}    object Animated : AnimatedStaticSymbol("{entry.name}", SymbolWeight.Regular, '
                     f'R.array.{entry.resources["Regular", True]})')
        for (weight, animated), resource in entry.resources.items():
            if animated:
                continue
            animated_resource = entry.resources[weight, True]
            if weight == 'DynamicWeight':
                line = f'object DynamicWeight : DynamicSymbol("{entry.name}", R.array.{resource})'
                motion = f'AnimatedDynamicSymbol("{entry.name}", R.array.{animated_resource})'
            else:
                line = f'object {weight} : StaticSymbol("{entry.name}", SymbolWeight.{weight}, R.array.{resource})'
                motion = f'AnimatedStaticSymbol("{entry.name}", SymbolWeight.{weight}, R.array.{animated_resource})'
            lines.extend((f'{indent}    {line} {{', f'{indent}        object Animated : {motion}', f'{indent}    }}'))
    for child, value in sorted(entry.children.items()):
        render_tree(lines, value, child, indent + '    ')
    lines.append(f'{indent}}}')


def documents_for_weights(source, weights, selected):
    def slices(compact):
        return [static_document(source, weight, weights, compact) for weight in selected]

    try:
        documents = slices(True)
    except ValueError:
        documents = slices(False)
    merged = documents[0]
    for scale in SCALES:
        # SymbolAssetStore searches in ascending source weight order.
        frames = [doc['scales'][scale]['masters'][0] for doc in documents]
        merged['scales'][scale] = {'masters': frames}
    return merged


def generate(output, names, assets=ASSETS, private=False):
    source_index = read_json('symbols/index.json', assets)
    selection = read_json(source_index['resourceSelectionAsset'], assets)
    weights = source_index['weights']
    missing = set(names) - set(source_index['symbols'])
    if missing:
        raise ValueError(f'Unknown SF Symbol names: {sorted(missing)}')

    root = Entry()
    xml = ET.Element('resources')
    inventory = {}
    raw = output / 'res/raw'
    raw.mkdir(parents=True, exist_ok=True)
    previous = set(raw.glob('sf_*'))
    generated = set()
    for name in names:
        entry = entry_for(root, name, set(weights) | {'DynamicWeight', 'Animated'})
        key = hashlib.sha256(name.encode()).hexdigest()[:16]
        index, documents, variants, extras = resource_closure(name, name, source_index, selection, assets)
        for label in [*weights, 'DynamicWeight']:
            runtime = label == 'DynamicWeight'
            selected = sorted(weights, key=weights.__getitem__) if runtime else [label]
            index_with_weight = {**index, 'packedFor': label}
            if not runtime:
                index_with_weight['packedWeight'] = label
            slices = {path: documents_for_weights(doc, weights, selected) for path, doc in documents.items()}
            for animated in (False, True):
                prefix = 'sf_private' if private else 'sf'
                stem = f'{prefix}_{key}_{label.lower()}' + ('_animated' if animated else '')
                entry.resources[label, animated] = stem
                array = ET.SubElement(xml, 'integer-array', name=stem)
                entry_key = f'{name}:{label}' + (':Animated' if animated else '')
                default_path = f'symbols/{name}.json'
                # Share identical private resources so the full catalog fits Android's raw resource ID table.
                payloads = {'symbols/index.json': pack(index_with_weight if private else
                                                       {**index_with_weight, 'animated': animated})}
                for path, document in slices.items():
                    payload = document if animated else without_animation(document)
                    if private and path == default_path:
                        payload = {**payload, 'catalogEntry': entry_key}
                    payloads[path] = pack(payload)
                payloads.update(extras)
                records = []
                for i, (path, payload) in enumerate(payloads.items()):
                    digest = hashlib.sha256(payload).hexdigest()
                    resource = f'sf_private_blob_{digest[:32]}' if private else f'{stem}_{i}'
                    suffix = '.sfgz' if payload.startswith(b'\x1f\x8b') else Path(path).suffix
                    file = raw / f'{resource}{suffix}'
                    if file not in generated:
                        file.write_bytes(payload)
                    generated.add(file)
                    ET.SubElement(array, 'item').text = f'@raw/{resource}'
                    record = {'file': file.name, 'asset': path, 'symbol': name, 'bytes': len(payload),
                              'sha256': digest}
                    if private:
                        record['entryMarker'] = path == default_path
                    if path in variants:
                        record.update(variants[path], weights=selected, scales=list(SCALES), animated=animated)
                    records.append(record)
                inventory[entry_key] = records

    for stale in previous - generated:
        stale.unlink()
    namespace = 'com.nevoit.sfcompose.catalog.privateglyphs' if private else 'com.nevoit.sfcompose.catalog'
    class_name = 'PrivateSFSymbols' if private else 'SFSymbols'
    lines = ['package com.nevoit.sfcompose.symbols', '', f'import {namespace}.R', '']
    render_tree(lines, root, class_name)
    kotlin_dir = output / 'kotlin/com/nevoit/sfcompose/symbols'
    kotlin_dir.mkdir(parents=True, exist_ok=True)
    (kotlin_dir / f'{class_name}.kt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    values = output / 'res/values'
    values.mkdir(parents=True, exist_ok=True)
    ET.indent(xml)
    ET.ElementTree(xml).write(values / 'sf_symbols.xml', encoding='utf-8', xml_declaration=True)
    (output / 'inventory.json').write_text(json.dumps(inventory, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('names')
    parser.add_argument('--assets', type=Path, default=ASSETS)
    parser.add_argument('--private', action='store_true')
    args = parser.parse_args()
    names = set(args.names.replace(',', ' ').split())
    if args.private:
        if not names:
            names.update(read_json('symbols/index.json', args.assets)['symbols'])
    else:
        names.update(DEFAULT_SYMBOLS)
    generate(args.output, sorted(names), args.assets, args.private)
