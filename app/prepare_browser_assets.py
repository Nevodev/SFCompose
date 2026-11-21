"""Package full browser catalogs using the existing compact runtime documents."""

import argparse
from collections import defaultdict
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import zipfile


def read_json(data):
    return json.loads(gzip.decompress(data) if data.startswith(b'\x1f\x8b') else data)


def logical_path(path):
    value = path.as_posix()
    for suffix in ('.sfgz', '.gz'):
        if value.endswith(suffix):
            return value[:-len(suffix)]
    return value


def prepare_catalog(source, archive_path, output):
    source_index = next(source / name for name in ('symbols/index.json.sfgz', 'symbols/index.json')
                        if (source / name).is_file())
    header = read_json(source_index.read_bytes())
    metadata = {'symbols/index.json', 'symbols/fill-images/index.json',
                logical_path(Path(header['resourceSelectionAsset']))}
    with zipfile.ZipFile(archive_path) as archive:
        index = read_json(archive.read('index.json.gz'))
        if index['format'] != 'sfcompose-build-data-v1':
            raise ValueError('Unsupported build catalog format')
        if set(index['symbols']) != set(header['symbols']):
            raise ValueError('Browser requires a complete build catalog; remove the catalog name filter')
        if index['weights'] != sorted(header['weights'], key=header['weights'].__getitem__):
            raise ValueError('Build catalog weights do not match the browser source')
        documents = {}
        for files in index['symbols'].values():
            for asset, digest in files.items():
                asset = logical_path(Path(asset))
                if not asset.startswith('symbols/') or not asset.endswith('.json') or asset in metadata:
                    continue
                if asset in documents and documents[asset] != digest:
                    raise ValueError(f'Conflicting compact document: {asset}')
                documents[asset] = digest
        payload_size = archive.getinfo('payloads.bin').file_size
        pending = defaultdict(list)
        retained = set()
        for file in sorted(source.rglob('*')):
            if not file.is_file():
                continue
            relative = file.relative_to(source)
            logical = logical_path(relative)
            if logical == 'symbols/system-import-report.json':
                continue
            target = output / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            retained.add(target)
            digest = documents.get(logical)
            if digest is None:
                if logical.startswith('symbols/') and logical.endswith('.json') and logical not in metadata \
                        and logical != 'symbols/browser-catalog.json':
                    raise ValueError(f'Missing compact document: {logical}')
                shutil.copyfile(file, target)
            else:
                pending[digest].append(target)
        with archive.open('payloads.bin') as payloads:
            for digest in sorted(pending, key=lambda key: index['blobs'][key][0]):
                offset, length = index['blobs'][digest]
                if offset < 0 or length < 0 or offset + length > payload_size:
                    raise ValueError(f'Invalid payload range: {digest}')
                payloads.seek(offset)
                content = payloads.read(length)
                if len(content) != length or hashlib.sha256(content).hexdigest() != digest:
                    raise ValueError(f'Invalid compact payload: {digest}')
                for target in pending[digest]:
                    target.write_bytes(content)
    size = sum(file.stat().st_size for file in retained)
    print(f'{len(header["symbols"])} symbols, {len(documents)} compact documents, {size / 1048576:.2f} MiB')
    return retained


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--public-assets', type=Path, required=True)
    parser.add_argument('--private-assets', type=Path, required=True)
    parser.add_argument('--public-catalog', type=Path, required=True)
    parser.add_argument('--private-catalog', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    for source in (args.public_assets, args.private_assets):
        resolved = source.resolve()
        if output.is_relative_to(resolved) or resolved.is_relative_to(output):
            raise ValueError('Browser output and source directories must not overlap')
    retained = prepare_catalog(args.public_assets, args.public_catalog, output)
    retained.update(prepare_catalog(args.private_assets, args.private_catalog, output / 'private'))
    for stale in output.rglob('*'):
        if stale.is_file() and stale not in retained:
            stale.unlink()


if __name__ == '__main__':
    main()
