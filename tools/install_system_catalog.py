"""Install a verified system catalog with a deployment suffix preserved by Android.

The .json.sfgz files keep the source gzip bytes. A receipt records the logical
source path, deployed path and digest; the catalog index is published last.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import zipfile


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def deployment_path(relative):
    if relative.endswith('.json.gz'):
        return relative.removesuffix('.gz') + '.sfgz'
    if relative == 'index.json':
        return relative
    raise ValueError(f'Unexpected catalog asset extension: {relative}')


def contained_path(directory, relative):
    path = (directory / relative).resolve()
    if not path.is_relative_to(directory):
        raise ValueError(f'Catalog asset is outside the requested directory: {relative}')
    return path


def install(source, destination, backup, receipt_path):
    source, destination, backup = [Path(path).resolve() for path in [source, destination, backup]]
    receipt_path = Path(receipt_path).resolve()
    manifest = json.loads((source / 'asset-install-manifest.json').read_text(encoding='utf-8'))
    if manifest['errors']:
        raise ValueError('The source catalog has verification failures')
    if not backup.is_file() or not zipfile.is_zipfile(backup):
        raise ValueError('A preserved previous-asset ZIP is required before installation')
    with zipfile.ZipFile(backup) as archive:
        if not any(Path(name).name == 'index.json' for name in archive.namelist()):
            raise ValueError('The preserved asset ZIP does not contain its catalog index')
    index = json.loads((source / 'index.json').read_text(encoding='utf-8'))
    if len(index['symbols']) != manifest['symbols'] or index['source']['generationFingerprint'] != manifest['generationFingerprint']:
        raise ValueError('Source index does not match its verification manifest')
    planned = []
    index_records = 0
    deployed_names = set()
    for record in manifest['files']:
        relative = record['relativePath']
        source_path = contained_path(source, relative)
        installed_relative = deployment_path(relative)
        target = contained_path(destination, installed_relative)
        if installed_relative in deployed_names:
            raise ValueError('Repeated deployed asset path')
        deployed_names.add(installed_relative)
        if source_path.stat().st_size != record['bytes'] or digest(source_path) != record['sha256']:
            raise ValueError(f'Source catalog digest mismatch: {relative}')
        legacy = contained_path(destination, relative) if relative.endswith('.json.gz') else None
        if legacy is not None and legacy.exists() and digest(legacy) != record['sha256']:
            raise ValueError(f'An existing gzip asset differs from the verified source: {relative}')
        planned.append((record, source_path, target, installed_relative, legacy))
        index_records += relative == 'index.json'
    if index_records != 1 or len(planned) != manifest['variants'] + 1:
        raise ValueError('The installation list is incomplete')
    planned.sort(key=lambda item: item[0]['relativePath'] == 'index.json')
    receipt = {'sourceKind': manifest['sourceKind'], 'generationFingerprint': manifest['generationFingerprint'],
               'sourceManifestSha256': digest(source / 'asset-install-manifest.json'),
               'backup': str(backup), 'backupSha256': digest(backup),
               'symbols': manifest['symbols'], 'variants': manifest['variants'], 'files': [], 'removedRedundantGzip': []}
    for record, source_path, target, installed_relative, legacy in planned:
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists() or target.stat().st_size != record['bytes'] or digest(target) != record['sha256']:
            temporary = target.with_name(target.name + '.installing')
            shutil.copyfile(source_path, temporary)
            if digest(temporary) != record['sha256']:
                raise ValueError(f'Copied asset digest mismatch: {installed_relative}')
            temporary.replace(target)
        if legacy is not None and legacy.exists():
            # Only redundant bytes already authenticated above are removed; unrelated files remain.
            legacy.unlink()
            receipt['removedRedundantGzip'].append(record['relativePath'])
        receipt['files'].append({'sourceRelativePath': record['relativePath'], 'installedRelativePath': installed_relative,
                                 'bytes': record['bytes'], 'sha256': record['sha256']})
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.write_text(json.dumps(receipt, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--backup', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    result = install(args.source, args.destination, args.backup, args.receipt)
    print(f"Installed {len(result['files'])} verified assets; {result['symbols']} symbols/{result['variants']} variants")
