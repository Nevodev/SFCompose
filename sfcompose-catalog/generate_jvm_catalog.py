"""Generate the full catalog as independently removable JVM data closures."""

import argparse
import base64
from concurrent.futures import ProcessPoolExecutor
import hashlib
import json
from pathlib import Path

from generate_symbols import Entry, documents_for_weights, entry_for, without_animation
from generate_weight_packs import pack, read_json, resource_closure

WEIGHTS = ('Ultralight', 'Thin', 'Light', 'Regular', 'Medium', 'Semibold', 'Bold', 'Heavy', 'Black')


def cache_fingerprint(index):
    digest = hashlib.sha256(pack(index))
    for file in (Path(__file__), Path(__file__).with_name('generate_symbols.py'),
                 Path(__file__).resolve().parents[1] / 'shrink-prototype/generate_weight_packs.py'):
        digest.update(file.read_bytes())
    return digest.hexdigest()


def initialize_worker(folder, index, selection):
    global OUTPUT, INDEX, SELECTION, CACHE
    OUTPUT, INDEX, SELECTION = Path(folder), index, selection
    CACHE = OUTPUT / 'symbol-cache' / cache_fingerprint(index)
    CACHE.mkdir(parents=True, exist_ok=True)


def generate_symbol(name):
    folder, index, selection = OUTPUT, INDEX, SELECTION
    key = hashlib.sha256(name.encode()).hexdigest()[:16]
    checkpoint = CACHE / f'{key}.json'
    if checkpoint.exists():
        retained = json.loads(checkpoint.read_text(encoding='utf-8'))
        if all((folder / 'blobs' / record['sha256']).exists()
               for data in retained.values() for record in data['files']):
            return retained
    weights = index['weights']
    scoped, documents, variants, extras = resource_closure(name, name, index, selection)
    sliced = {path: {weight: documents_for_weights(document, weights, [weight]) for weight in weights}
              for path, document in documents.items()}
    result = {}
    for weight in [*weights, 'DynamicWeight']:
        selected = sorted(weights, key=weights.__getitem__) if weight == 'DynamicWeight' else [weight]
        header = {**scoped, 'packedFor': weight}
        if weight != 'DynamicWeight':
            header['packedWeight'] = weight
        for animated in (False, True):
            entry = f'{name}:{weight}' + (':Animated' if animated else '')
            payloads = {'symbols/index.json': pack(header)}
            for path, samples in sliced.items():
                document = samples[selected[0]]
                if len(selected) > 1:
                    document = {**document, 'scales': {
                        scale: {'masters': [samples[label]['scales'][scale]['masters'][0] for label in selected]}
                        for scale in document['scales']}}
                document = document if animated else without_animation(document)
                if path == f'symbols/{name}.json':
                    document = {**document, 'catalogEntry': entry}
                payloads[path] = pack(document)
            payloads.update(extras)
            records = []
            for path, content in payloads.items():
                digest = hashlib.sha256(content).hexdigest()
                blob = folder / 'blobs' / digest
                if not blob.exists():
                    blob.write_bytes(content)
                record = {'asset': path, 'symbol': name, 'sha256': digest, 'bytes': len(content),
                          'entryMarker': path == f'symbols/{name}.json',
                          'chunks': [hashlib.sha256(base64.b64encode(content[offset:offset + 12000])).hexdigest()
                                     for offset in range(0, len(content), 12000)]}
                if path in variants:
                    record.update(variants[path], weights=selected, scales=['small', 'medium', 'large'], animated=animated)
                records.append(record)
            result[entry] = {'source': f'S{key}_{weight}_' + ('animated' if animated else 'static'), 'files': records}
    checkpoint.write_text(json.dumps(result, separators=(',', ':')), encoding='utf-8')
    return result


def api_rows(tree, name):
    rows = []
    children = list(tree.children)
    if tree.name:
        key = hashlib.sha256(tree.name.encode()).hexdigest()[:16]
        labels = [*WEIGHTS, 'DynamicWeight']
        rows.append((name, 'StaticSymbol', tree.name, 'Regular', f'S{key}_Regular_static',
                     ','.join(['Animated', *labels, *children])))
        rows.append((name + '$Animated', 'AnimatedStaticSymbol', tree.name, 'Regular', f'S{key}_Regular_animated', ''))
        for weight in labels:
            dynamic = weight == 'DynamicWeight'
            child = name + '$' + weight
            rows.append((child, 'DynamicSymbol' if dynamic else 'StaticSymbol', tree.name,
                         '' if dynamic else weight, f'S{key}_{weight}_static', 'Animated'))
            rows.append((child + '$Animated', 'AnimatedDynamicSymbol' if dynamic else 'AnimatedStaticSymbol',
                         tree.name, '' if dynamic else weight, f'S{key}_{weight}_animated', ''))
    else:
        rows.append((name, '', '', '', '', ','.join(children)))
    for child, value in tree.children.items():
        rows.extend(api_rows(value, name + '$' + child))
    return rows


def generate(output, names, workers):
    output.mkdir(parents=True, exist_ok=True)
    (output / 'blobs').mkdir(exist_ok=True)
    index = read_json('symbols/index.json')
    selection = read_json(index['resourceSelectionAsset'])
    tree = Entry()
    for name in names:
        entry_for(tree, name, set(WEIGHTS) | {'DynamicWeight', 'Animated'})
    (output / 'api.tsv').write_text('\n'.join('\t'.join(row) for row in api_rows(tree, 'SFSymbols')) + '\n', encoding='utf-8')
    payloads = set()
    with (
        (output / 'sources.tsv').open('w', encoding='utf-8') as sources,
        (output / 'inventory.json').open('w', encoding='utf-8') as inventory,
        ProcessPoolExecutor(max_workers=workers, initializer=initialize_worker,
                            initargs=(str(output), index, selection)) as pool,
    ):
        inventory.write(json.dumps({'format': 'jvm-symbols-v1', 'symbols': len(names)}) + '\n')
        for count, result in enumerate(pool.map(generate_symbol, names, chunksize=1), 1):
            for entry, data in result.items():
                records = data['files']
                inventory.write(json.dumps({'entry': entry, 'files': records}, separators=(',', ':')) + '\n')
                paths = [data['source']]
                for record in records:
                    paths.extend((record['asset'], record['sha256']))
                    payloads.add(record['sha256'])
                sources.write('\t'.join(paths) + '\n')
            if count % 100 == 0:
                print(f'Generated {count}/{len(names)} symbols', flush=True)
    (output / 'payloads.tsv').write_text(
        '\n'.join(f'{digest}\t{digest}' for digest in sorted(payloads)) + '\n', encoding='utf-8')
    print(f'Generated {len(names)} symbols, {len(names) * 20} entries, {len(payloads)} data classes', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--names', default='')
    parser.add_argument('--workers', type=int, default=4)
    args = parser.parse_args()
    names = args.names.replace(',', ' ').split() or read_json('symbols/index.json')['symbols']
    generate(args.output, sorted(names), args.workers)
