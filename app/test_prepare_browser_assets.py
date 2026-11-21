"""Checks that browser packaging preserves metadata and verifies compact catalog payloads."""

import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from prepare_browser_assets import prepare_catalog


def encode(value):
    return gzip.compress(json.dumps(value).encode('utf-8'), mtime=0)


class BrowserAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.source = root / 'source'
        self.output = root / 'output'
        self.archive = root / 'catalog.zip'
        (self.source / 'symbols/variants').mkdir(parents=True)
        self.header = {'symbols': ['heart'], 'weights': {'Regular': 400},
                       'resourceSelectionAsset': 'symbols/selection.json.gz'}
        (self.source / 'symbols/index.json').write_text(json.dumps(self.header), encoding='utf-8')
        for name in ['heart', 'variants/other']:
            (self.source / f'symbols/{name}.json.sfgz').write_bytes(encode({'original': name}))
        self.metadata = encode({'rightToLeftNames': {'heart': 'heart'}})
        (self.source / 'symbols/selection.json.sfgz').write_bytes(self.metadata)
        (self.source / 'symbols/system-import-report.json').write_text('{"diagnostic":true}', encoding='utf-8')
        self.compact = encode({'name': 'heart', 'scales': {}, 'outlineMasters': []})
        self.write_archive()

    def write_archive(self, names=None, corrupt=False, include_variant=True):
        digest = hashlib.sha256(self.compact).hexdigest()
        files = {'symbols/heart.json': digest}
        if include_variant:
            files['symbols/variants/other.json.gz'] = digest
        index = {'format': 'sfcompose-build-data-v1', 'symbols': names if names is not None else {'heart': files},
                 'weights': ['Regular'], 'blobs': {digest: [0, len(self.compact)]}}
        with zipfile.ZipFile(self.archive, 'w') as archive:
            archive.writestr('index.json.gz', encode(index))
            archive.writestr('payloads.bin', b'x' * len(self.compact) if corrupt else self.compact)

    def test_replaces_both_default_and_variant_but_preserves_global_metadata(self):
        retained = prepare_catalog(self.source, self.archive, self.output)
        for name in ['heart', 'variants/other']:
            self.assertEqual(self.compact, (self.output / f'symbols/{name}.json.sfgz').read_bytes())
        self.assertEqual(self.metadata, (self.output / 'symbols/selection.json.sfgz').read_bytes())
        self.assertEqual(self.header, json.loads((self.output / 'symbols/index.json').read_text(encoding='utf-8')))
        self.assertEqual(4, len(retained))
        self.assertFalse((self.output / 'symbols/system-import-report.json').exists())
        self.assertNotEqual(self.compact, (self.source / 'symbols/heart.json.sfgz').read_bytes())

    def test_rejects_a_filtered_catalog_instead_of_shipping_raw_documents(self):
        self.write_archive(names={})
        with self.assertRaisesRegex(ValueError, 'complete build catalog'):
            prepare_catalog(self.source, self.archive, self.output)

    def test_rejects_missing_variant(self):
        self.write_archive(include_variant=False)
        with self.assertRaisesRegex(ValueError, 'Missing compact document'):
            prepare_catalog(self.source, self.archive, self.output)

    def test_verifies_payload_hash(self):
        self.write_archive(corrupt=True)
        with self.assertRaisesRegex(ValueError, 'Invalid compact payload'):
            prepare_catalog(self.source, self.archive, self.output)


if __name__ == '__main__':
    unittest.main()
