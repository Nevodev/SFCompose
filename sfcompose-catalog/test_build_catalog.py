"""Tests for the standalone build-data producer in generate_build_catalog."""

import contextlib
import gzip
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import generate_build_catalog as catalog

WEIGHTS = {
    'Ultralight': 0,
    'Thin': 1,
    'Light': 2,
    'Regular': 3,
    'Medium': 4,
    'Semibold': 5,
    'Bold': 6,
    'Heavy': 7,
    'Black': 8,
}
GLYPH = 'alpha.beta'
ENTRY = GLYPH + catalog.DYNAMIC_ENTRY_SUFFIX
EXTRAS = ('symbols/index.json', 'symbols/fill-images/index.json', 'symbols/selection.json')


def source_index():
    return {
        'weights': dict(WEIGHTS),
        'symbols': [GLYPH],
        'resourceSelectionAsset': 'symbols/selection.json',
    }


class BuildCatalogTest(unittest.TestCase):
    def setUp(self):
        self._temporary = tempfile.TemporaryDirectory()
        self.folder = Path(self._temporary.name)
        self.output = self.folder / 'output'
        self.blobs = self.folder / 'blobs'
        self.blobs.mkdir()

    def tearDown(self):
        self._temporary.cleanup()

    def payloads(self):
        return {
            'symbols/' + GLYPH + '.json': catalog.pack({
                'name': GLYPH,
                'catalogEntry': ENTRY,
                'scales': {'small': {'masters': []}},
            }),
            'symbols/index.json': catalog.pack({'formatVersion': 1, 'symbols': [GLYPH]}),
            'symbols/fill-images/index.json': catalog.pack({'images': []}),
            'symbols/selection.json': catalog.pack({'rightToLeftNames': {}}),
        }

    def write_inventory(self, payloads):
        records = []
        for asset, content in payloads.items():
            digest = hashlib.sha256(content).hexdigest()
            (self.blobs / digest).write_bytes(content)
            records.append({'asset': asset, 'sha256': digest, 'bytes': len(content)})
        inventory = self.folder / 'inventory.json'
        inventory.write_text(
            json.dumps({'format': 'jvm-symbols-v1', 'symbols': 1}) + '\n'
            + json.dumps({'entry': ENTRY, 'files': records}, separators=(',', ':')) + '\n',
            encoding='utf-8')
        return inventory

    def generate(self, inventory):
        with patch.object(catalog, 'read_json', return_value=source_index()), \
                contextlib.redirect_stdout(io.StringIO()):
            catalog.generate(self.output, [], inventory)

    def test_namespace_table_has_root_without_weight_or_animation_nodes(self):
        self.generate(self.write_inventory(self.payloads()))

        rows = [tuple(line.split('\t')) for line in
                (self.output / 'namespaces.tsv').read_text(encoding='utf-8').splitlines()]
        self.assertEqual([
            ('SFSymbols', '', 'Alpha'),
            ('SFSymbols$Alpha', '', 'Beta'),
            ('SFSymbols$Alpha$Beta', GLYPH, ''),
        ], rows)

        with zipfile.ZipFile(self.output / 'build-data.zip') as archive:
            index = json.loads(gzip.decompress(archive.read('index.json.gz')))
        namespaces = index['namespaces']
        self.assertIsNone(namespaces[catalog.NAMESPACE_PREFIX + 'SFSymbols'])
        self.assertEqual(namespaces[catalog.NAMESPACE_PREFIX + 'SFSymbols$Alpha$Beta'], GLYPH)
        reserved = [*WEIGHTS, 'DynamicWeight', 'Animated']
        for simple, _symbol, _children in rows:
            self.assertFalse([name for name in reserved if simple.endswith('$' + name)],
                             f'namespace table contains generated node {simple!r}')

    def test_private_assets_and_symbol_root_are_forwarded(self):
        assets, weights = self.private_assets()
        reads = []
        closures = []
        original_read_json = catalog.read_json
        original_resource_closure = catalog.resource_closure

        def spy_read_json(path, root):
            reads.append(root)
            return original_read_json(path, root)

        def spy_resource_closure(name, accessor, source_index, selection, root):
            closures.append(root)
            return original_resource_closure(name, accessor, source_index, selection, root)

        with patch.object(catalog, 'read_json', side_effect=spy_read_json), \
                patch.object(catalog, 'resource_closure', side_effect=spy_resource_closure), \
                contextlib.redirect_stdout(io.StringIO()):
            catalog.generate(self.output, [GLYPH], assets=assets, symbol_root='PrivateSFSymbols')

        self.assertTrue(reads, 'generate should read source JSON through the supplied assets')
        self.assertEqual({assets}, set(reads))
        self.assertEqual([assets], closures, 'resource_closure should receive the private asset root')
        self.assertTrue((self.output / catalog.CACHE_DIRECTORY / 'symbols').is_dir(),
                        'per-symbol cache should stay rooted in the output folder')

        with zipfile.ZipFile(self.output / 'build-data.zip') as archive:
            index = json.loads(gzip.decompress(archive.read('index.json.gz')))
        self.assertEqual([GLYPH], list(index['symbols']))
        self.assertEqual(sorted(weights), sorted(index['weights']))
        self.assertIsNone(index['namespaces'][catalog.NAMESPACE_PREFIX + 'PrivateSFSymbols'])
        self.assertEqual(index['namespaces'][catalog.NAMESPACE_PREFIX + 'PrivateSFSymbols$Alpha$Beta'], GLYPH)
        self.assertNotIn(catalog.NAMESPACE_PREFIX + 'SFSymbols', index['namespaces'])

        rows = [tuple(line.split('\t')) for line in
                (self.output / 'namespaces.tsv').read_text(encoding='utf-8').splitlines()]
        self.assertEqual([
            ('PrivateSFSymbols', '', 'Alpha'),
            ('PrivateSFSymbols$Alpha', '', 'Beta'),
            ('PrivateSFSymbols$Alpha$Beta', GLYPH, ''),
        ], rows)

    def private_assets(self):
        weights = {'Ultralight': 0, 'Regular': 3, 'Black': 8}
        masters = [
            {'weight': weights[weight], 'contours': [{'verbs': ['M'], 'points': [0.0]}],
             'compiledLayers': [],
             'systemSampling': {'coefficients': [0.0, 0.0], 'sizeScale': 1.0},
             'systemTransform': [1.0, 0.0, 0.0, 1.0, 0.0, 0.0]}
            for weight in ('Ultralight', 'Regular', 'Black')
        ]
        document = {
            'name': GLYPH,
            'upm': 1000,
            'capHeight': 700,
            'layerTree': {},
            'metadata': {},
            'systemVariantId': 'private',
            'systemVariants': [{'asset': None, 'localeTag': 'en', 'direction': 'ltr',
                                'appearance': 'regular', 'id': 1}],
            'scales': {scale: {'masters': [dict(master) for master in masters]}
                       for scale in ('small', 'medium', 'large')},
        }
        index = {
            'formatVersion': 1,
            'weights': weights,
            'drawWeightCoefficients': {},
            'symbols': [GLYPH],
            'resourceSelectionAsset': 'symbols/selection.json',
            'source': {'sourceCarSha256': '0' * 64, 'localizations': [], 'appearances': ['regular']},
        }
        selection = {'rightToLeftNames': {}, 'flippableVariants': []}
        assets = self.folder / 'private-assets'
        (assets / 'symbols').mkdir(parents=True)
        (assets / 'symbols/index.json').write_text(json.dumps(index), encoding='utf-8')
        (assets / 'symbols/selection.json').write_text(json.dumps(selection), encoding='utf-8')
        (assets / 'symbols' / (GLYPH + '.json')).write_text(json.dumps(document), encoding='utf-8')
        return assets, weights

    def test_bootstrap_archive_validates_offsets_and_strips_catalog_entry(self):
        originals = self.payloads()
        self.generate(self.write_inventory(originals))

        with zipfile.ZipFile(self.output / 'build-data.zip') as archive:
            payloads = archive.read('payloads.bin')
            index = json.loads(gzip.decompress(archive.read('index.json.gz')))

        self.assertEqual(catalog.FORMAT, index['format'])
        entries = index['symbols'][GLYPH]
        glyph_asset = 'symbols/' + GLYPH + '.json'
        glyph_digest = entries[glyph_asset]
        self.assertNotEqual(hashlib.sha256(originals[glyph_asset]).hexdigest(), glyph_digest)

        offset, length = index['blobs'][glyph_digest]
        glyph_bytes = payloads[offset:offset + length]
        self.assertEqual(glyph_digest, hashlib.sha256(glyph_bytes).hexdigest())
        glyph = json.loads(gzip.decompress(glyph_bytes))
        self.assertNotIn('catalogEntry', glyph)
        self.assertTrue(
            (self.output / catalog.CACHE_DIRECTORY / 'blobs' / glyph_digest).is_file(),
            'stripped glyph blob should be stored in the output cache')

        for asset in EXTRAS:
            digest = entries[asset]
            self.assertEqual(hashlib.sha256(originals[asset]).hexdigest(), digest)
            start, size = index['blobs'][digest]
            self.assertEqual(originals[asset], payloads[start:start + size])

        for digest, (start, size) in index['blobs'].items():
            blob = payloads[start:start + size]
            self.assertEqual(size, len(blob))
            self.assertEqual(digest, hashlib.sha256(blob).hexdigest())

    def test_corrupted_inventory_blob_is_rejected(self):
        content = b'0123456789'
        digest = hashlib.sha256(content).hexdigest()
        # Same length, different bytes, so only the hash check can reject it.
        (self.blobs / digest).write_bytes(b'9876543210')
        inventory = self.folder / 'inventory.json'
        inventory.write_text(
            json.dumps({'format': 'jvm-symbols-v1', 'symbols': 1}) + '\n'
            + json.dumps({'entry': ENTRY, 'files': [
                {'asset': 'symbols/' + GLYPH + '.json', 'sha256': digest, 'bytes': len(content)},
            ]}) + '\n',
            encoding='utf-8')

        with patch.object(catalog, 'read_json', return_value=source_index()), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError, 'Inventory blob hash mismatch'):
                catalog.generate(self.output, [], inventory)


if __name__ == '__main__':
    unittest.main()
