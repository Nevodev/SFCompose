"""Produce the standalone SFSymbols build data archive and Java API namespace table."""

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import zipfile

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from generate_symbols import Entry, documents_for_weights, entry_for  # noqa: E402
from generate_weight_packs import ASSETS, pack, read_json, resource_closure  # noqa: E402

FORMAT = 'sfcompose-build-data-v1'
INDEX_ASSET = 'symbols/index.json'
FILL_INDEX_ASSET = 'symbols/fill-images/index.json'
CACHE_DIRECTORY = '.build-catalog-cache'
NAMESPACE_PREFIX = 'com/nevoit/sfcompose/symbols/'
SYMBOL_ROOT = 'SFSymbols'
DYNAMIC_ENTRY_SUFFIX = ':DynamicWeight:Animated'
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def ordered_weights(weights):
    return sorted(weights, key=weights.__getitem__)


def parse_names(value):
    return sorted(set(value.replace(',', ' ').split()))


def script_fingerprint():
    digest = hashlib.sha256()
    for file in (Path(__file__), SCRIPT_DIR / 'generate_symbols.py',
                 SCRIPT_DIR.parent / 'shrink-prototype/generate_weight_packs.py'):
        digest.update(file.read_bytes())
    return digest.hexdigest()


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=path.name + '.tmp-')
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(data)
        os.replace(temporary, path)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise


def symbol_cache_path(cache, name):
    return cache / 'symbols' / (sha256(name.encode('utf-8')) + '.json')


def source_fingerprint(name, header, documents, extras, scripts):
    digest = hashlib.sha256()
    digest.update(scripts.encode('utf-8'))
    digest.update(name.encode('utf-8'))
    digest.update(pack(header))
    for path in sorted(documents):
        digest.update(path.encode('utf-8'))
        digest.update(pack(documents[path]))
    for path in sorted(extras):
        digest.update(path.encode('utf-8'))
        digest.update(extras[path])
    return digest.hexdigest()


def load_cached_symbol(cache, name, fingerprint):
    checkpoint = symbol_cache_path(cache, name)
    if not checkpoint.is_file():
        return None
    retained = json.loads(checkpoint.read_text(encoding='utf-8'))
    if retained.get('name') != name or retained.get('fingerprint') != fingerprint:
        return None
    files = []
    for record in retained['files']:
        blob = cache / 'blobs' / record['sha256']
        if not blob.is_file():
            return None
        content = blob.read_bytes()
        if len(content) != record['bytes']:
            raise ValueError(f'Cached blob length mismatch: {record["sha256"]}')
        if sha256(content) != record['sha256']:
            raise ValueError(f'Cached blob hash mismatch: {record["sha256"]}')
        files.append((record['asset'], record['sha256'], len(content), blob))
    return files


def store_blob(cache, content):
    digest = sha256(content)
    blob = cache / 'blobs' / digest
    if not blob.is_file():
        atomic_write(blob, content)
    return digest, blob


def cache_symbol(cache, name, fingerprint, payloads):
    files = []
    for path in sorted(payloads):
        digest, blob = store_blob(cache, payloads[path])
        files.append({'asset': path, 'sha256': digest, 'bytes': len(payloads[path])})
    checkpoint = {'name': name, 'fingerprint': fingerprint, 'files': files}
    atomic_write(symbol_cache_path(cache, name),
                 json.dumps(checkpoint, ensure_ascii=False, separators=(',', ':')).encode('utf-8'))
    return [(record['asset'], record['sha256'], record['bytes'], cache / 'blobs' / record['sha256'])
            for record in files]


def direct_symbol(name, source_index, selection, cache, scripts, assets=ASSETS):
    header, documents, _variants, extras = resource_closure(name, name, source_index, selection, assets)
    fingerprint = source_fingerprint(name, header, documents, extras, scripts)
    cached = load_cached_symbol(cache, name, fingerprint)
    if cached is not None:
        return cached

    weights = source_index['weights']
    selected = ordered_weights(weights)
    payloads = {INDEX_ASSET: pack(header)}
    for path in sorted(documents):
        payloads[path] = pack(documents_for_weights(documents[path], weights, selected))
    for path, content in extras.items():
        payloads[path] = content
    return cache_symbol(cache, name, fingerprint, payloads)


def load_inventory(path, wanted):
    records = {}
    with path.open(encoding='utf-8') as source:
        header_line = next(source, None)
        if header_line is None:
            raise ValueError(f'Empty inventory: {path}')
        header = json.loads(header_line)
        if header.get('format') != 'jvm-symbols-v1':
            raise ValueError(f'Unsupported inventory format: {header.get("format")!r}')
        for line in source:
            if not line.strip():
                continue
            record = json.loads(line)
            entry = record.get('entry')
            if not isinstance(entry, str) or not entry.endswith(DYNAMIC_ENTRY_SUFFIX):
                continue
            name = entry[:-len(DYNAMIC_ENTRY_SUFFIX)]
            if wanted is not None and name not in wanted:
                continue
            if name in records:
                raise ValueError(f'Duplicate dynamic inventory entry: {name!r}')
            try:
                records[name] = [{'asset': file['asset'], 'sha256': file['sha256'], 'bytes': file['bytes']}
                                 for file in record['files']]
            except KeyError as error:
                raise ValueError(f'Incomplete inventory record for {name!r}') from error
    if wanted is not None:
        missing = wanted - set(records)
        if missing:
            raise ValueError(f'No DynamicWeight:Animated inventory records for: {sorted(missing)}')
    return records


def verified_blob(blob_dir, digest, expected_bytes, verified):
    known = verified.get(digest)
    if known is not None:
        if known != expected_bytes:
            raise ValueError(f'Inconsistent inventory blob length: {digest}')
    else:
        blob = blob_dir / digest
        if not blob.is_file():
            raise FileNotFoundError(blob)
        content = blob.read_bytes()
        if len(content) != expected_bytes:
            raise ValueError(f'Inventory blob length mismatch: {digest}')
        if sha256(content) != digest:
            raise ValueError(f'Inventory blob hash mismatch: {digest}')
        verified[digest] = expected_bytes
    blob = blob_dir / digest
    if not blob.is_file():
        raise FileNotFoundError(blob)
    return blob.read_bytes()


def glyph_document(asset, selection_asset):
    return (asset.startswith('symbols/') and asset.endswith('.json')
            and asset not in (INDEX_ASSET, FILL_INDEX_ASSET, selection_asset))


def without_catalog_entry(content, asset):
    if content.startswith(b'\x1f\x8b'):
        decoded = gzip.decompress(content)
    else:
        decoded = content
    try:
        data = json.loads(decoded.decode('utf-8'))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f'Invalid canonical glyph JSON: {asset}') from error
    if not isinstance(data, dict) or 'catalogEntry' not in data:
        return content
    data.pop('catalogEntry')
    return pack(data)


def bootstrap_symbol(files, blob_dir, selection_asset, store, verified):
    produced = []
    for record in files:
        asset = record['asset']
        digest = record['sha256']
        content = verified_blob(blob_dir, digest, record['bytes'], verified)
        blob = blob_dir / digest
        if glyph_document(asset, selection_asset):
            canonical = without_catalog_entry(content, asset)
            if canonical is not content:
                digest, blob = store_blob(store, canonical)
                content = canonical
        produced.append((asset, digest, len(content), blob))
    return produced


def namespace_rows(root, symbol_root=SYMBOL_ROOT):
    rows = [(symbol_root, '', sorted(root.children))]

    def visit(entry, path):
        for identifier, child in sorted(entry.children.items()):
            simple = '$'.join((symbol_root, *path, identifier))
            rows.append((simple, child.name or '', sorted(child.children)))
            visit(child, (*path, identifier))

    visit(root, ())
    return rows


def write_namespaces_tsv(path, rows):
    lines = [f'{simple}\t{symbol}\t{",".join(children)}' for simple, symbol, children in rows]
    path.write_text('\n'.join(lines) + ('\n' if lines else ''), encoding='utf-8')


def zip_entry(name):
    entry = zipfile.ZipInfo(name, date_time=ZIP_TIMESTAMP)
    entry.compress_type = zipfile.ZIP_STORED
    entry.create_system = 3
    entry.external_attr = 0o644 << 16
    return entry


def assemble(output, symbols, blob_paths, blob_lengths, rows, namespaces, weights):
    temporary = Path(tempfile.mkdtemp(prefix='.build-catalog-', dir=output))
    try:
        payload_file = temporary / 'payloads.bin'
        blobs = {}
        offset = 0
        with payload_file.open('wb') as payloads:
            for digest in sorted(blob_paths):
                blob = blob_paths[digest]
                length = blob_lengths[digest]
                if not blob.is_file():
                    raise FileNotFoundError(blob)
                if blob.stat().st_size != length:
                    raise ValueError(f'Blob length changed while assembling: {digest}')
                with blob.open('rb') as source:
                    shutil.copyfileobj(source, payloads, 1024 * 1024)
                blobs[digest] = [offset, length]
                offset += length

        index = {
            'format': FORMAT,
            'symbols': symbols,
            'blobs': blobs,
            'namespaces': namespaces,
            'weights': weights,
        }
        index_bytes = json.dumps(index, ensure_ascii=False, separators=(',', ':'), sort_keys=True).encode('utf-8')
        index_gzip = gzip.compress(index_bytes, mtime=0)

        archive = temporary / 'build-data.zip'
        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as output_zip:
            with output_zip.open(zip_entry('payloads.bin'), 'w', force_zip64=True) as target, \
                    payload_file.open('rb') as source:
                shutil.copyfileobj(source, target, 1024 * 1024)
            output_zip.writestr(zip_entry('index.json.gz'), index_gzip)

        namespaces_file = temporary / 'namespaces.tsv'
        write_namespaces_tsv(namespaces_file, rows)

        os.replace(archive, output / 'build-data.zip')
        os.replace(namespaces_file, output / 'namespaces.tsv')
        return offset, len(index_gzip)
    finally:
        if not temporary.resolve().is_relative_to(output.resolve()):
            raise ValueError(f'Temporary build data escaped its output directory: {temporary}')
        shutil.rmtree(temporary, ignore_errors=True)


def generate(output, names, inventory=None, assets=ASSETS, symbol_root=SYMBOL_ROOT):
    output.mkdir(parents=True, exist_ok=True)
    source_index = read_json(INDEX_ASSET, assets)
    weights = ordered_weights(source_index['weights'])
    records = None

    if inventory is not None:
        inventory = Path(inventory)
        if not inventory.is_file():
            raise FileNotFoundError(inventory)
        records = load_inventory(inventory, set(names) if names else None)
        names = sorted(records) if not names else names
        if not names:
            raise ValueError('Inventory contains no DynamicWeight:Animated records')
    else:
        names = names or sorted(source_index['symbols'])
        unknown = set(names) - set(source_index['symbols'])
        if unknown:
            raise ValueError(f'Unknown SF Symbol names: {sorted(unknown)}')

    tree = Entry()
    reserved = set(weights) | {'DynamicWeight', 'Animated'}
    for name in names:
        entry_for(tree, name, reserved)
    rows = namespace_rows(tree, symbol_root)
    namespaces = {NAMESPACE_PREFIX + simple: (symbol or None) for simple, symbol, _children in rows}
    symbols = {}
    blob_paths = {}
    blob_lengths = {}

    def register(name, files):
        mapping = {}
        for asset, digest, length, blob in files:
            previous = mapping.get(asset)
            if previous is not None and previous != digest:
                raise ValueError(f'Conflicting canonical assets for {name!r}: {asset}')
            mapping[asset] = digest
            known = blob_lengths.get(digest)
            if known is not None and known != length:
                raise ValueError(f'Conflicting blob length for {digest}')
            blob_lengths[digest] = length
            blob_paths.setdefault(digest, blob)
        symbols[name] = dict(sorted(mapping.items()))

    if records is not None:
        blob_dir = inventory.parent / 'blobs'
        selection_asset = source_index['resourceSelectionAsset']
        store = output / CACHE_DIRECTORY
        verified = {}
        for name in names:
            if name not in records:
                raise ValueError(f'No DynamicWeight:Animated inventory record for {name!r}')
            register(name, bootstrap_symbol(records[name], blob_dir, selection_asset, store, verified))
        mode = 'inventory'
    else:
        selection = read_json(source_index['resourceSelectionAsset'], assets)
        cache = output / CACHE_DIRECTORY
        scripts = script_fingerprint()
        for name in names:
            register(name, direct_symbol(name, source_index, selection, cache, scripts, assets))
        mode = 'sources'

    total, index_bytes = assemble(output, symbols, blob_paths, blob_lengths, rows, namespaces, weights)
    print(f'{mode}: {len(names)} symbols, {len(blob_paths)} blobs, {total} payload bytes, '
          f'{index_bytes} index bytes')
    print(f'Generated {output / "build-data.zip"} and {output / "namespaces.tsv"} '
          f'({len(rows)} namespace rows)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output_directory', type=Path)
    parser.add_argument('--names', default='')
    parser.add_argument('--inventory', type=Path)
    parser.add_argument('--assets', type=Path, default=ASSETS,
                        help='asset directory containing symbols/index.json')
    parser.add_argument('--symbol-root', default=SYMBOL_ROOT,
                        help='root namespace for the generated Java API table')
    args = parser.parse_args()
    generate(args.output_directory, parse_names(args.names), args.inventory,
             args.assets, args.symbol_root)


if __name__ == '__main__':
    main()
