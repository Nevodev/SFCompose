from pathlib import Path
import hashlib
import json
import tempfile
import unittest
import zipfile

from install_system_catalog import deployment_path, install


class InstallSystemCatalogTest(unittest.TestCase):
    def fixture(self, directory):
        root = Path(directory)
        source, destination = root / 'source', root / 'destination'
        source.mkdir()
        destination.mkdir()
        old = {'symbols': ['previous']}
        (destination / 'index.json').write_text(json.dumps(old), encoding='utf-8')
        backup = root / 'previous.zip'
        with zipfile.ZipFile(backup, 'w') as archive:
            archive.write(destination / 'index.json', 'index.json')
        index = {'symbols': ['example'], 'source': {'generationFingerprint': 'test'}}
        (source / 'index.json').write_text(json.dumps(index), encoding='utf-8')
        (source / 'example.json.gz').write_bytes(b'original gzip bytes')
        files = []
        for name in ['index.json', 'example.json.gz']:
            data = (source / name).read_bytes()
            files.append({'relativePath': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        manifest = {'errors': [], 'symbols': 1, 'variants': 1, 'generationFingerprint': 'test',
                    'sourceKind': 'macOS-system-CoreGlyphs', 'files': files}
        (source / 'asset-install-manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
        return root, source, destination, backup

    def test_deployment_keeps_source_bytes_and_records_suffix_mapping(self):
        with tempfile.TemporaryDirectory() as directory:
            root, source, destination, backup = self.fixture(directory)
            (destination / 'example.json.gz').write_bytes((source / 'example.json.gz').read_bytes())
            (destination / 'unrelated.txt').write_text('preserve', encoding='utf-8')
            receipt = install(source, destination, backup, root / 'receipt.json')
            self.assertEqual((destination / 'example.json.sfgz').read_bytes(), (source / 'example.json.gz').read_bytes())
            self.assertFalse((destination / 'example.json.gz').exists())
            self.assertEqual((destination / 'unrelated.txt').read_text(encoding='utf-8'), 'preserve')
            self.assertEqual(receipt['files'][-1]['installedRelativePath'], 'index.json')
            self.assertEqual(receipt['files'][0]['sourceRelativePath'], 'example.json.gz')
            self.assertEqual(receipt['files'][0]['installedRelativePath'], 'example.json.sfgz')

    def test_source_mismatch_cannot_publish_or_partially_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root, source, destination, backup = self.fixture(directory)
            (source / 'example.json.gz').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'Source catalog digest mismatch'):
                install(source, destination, backup, root / 'receipt.json')
            self.assertEqual(json.loads((destination / 'index.json').read_text(encoding='utf-8'))['symbols'], ['previous'])
            self.assertFalse((destination / 'example.json.sfgz').exists())

    def test_conflicting_legacy_gzip_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root, source, destination, backup = self.fixture(directory)
            (destination / 'example.json.gz').write_bytes(b'user bytes')
            with self.assertRaisesRegex(ValueError, 'existing gzip asset differs'):
                install(source, destination, backup, root / 'receipt.json')
            self.assertEqual((destination / 'example.json.gz').read_bytes(), b'user bytes')
            self.assertFalse((destination / 'example.json.sfgz').exists())

    def test_paths_outside_destination_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root, source, destination, backup = self.fixture(directory)
            path = source / 'asset-install-manifest.json'
            manifest = json.loads(path.read_text(encoding='utf-8'))
            manifest['files'][0]['relativePath'] = '../outside.json.gz'
            path.write_text(json.dumps(manifest), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'outside the requested directory'):
                install(source, destination, backup, root / 'receipt.json')

    def test_unknown_asset_extension_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unexpected catalog asset extension'):
            deployment_path('unrelated.bin')


if __name__ == '__main__':
    unittest.main()
