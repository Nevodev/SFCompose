from pathlib import Path
import gzip
import json
import os
import tempfile
import unittest

from import_system_catalog import circle_commands, decode_metadata, default_variant_key, logical_compiled_layers, native_selector_document, system_document, variant_id, variant_key, write_gzip
from bvg import read_bvg
from read_coreglyphs_car import CoreGlyphsCatalog


class SystemCatalogImportTest(unittest.TestCase):
    def test_variant_identity_preserves_every_key_except_sampled_weight(self):
        row = {'key': [0, 0, 85, 59, 5, 40797, 0, 0, 0, 0, 1, 0, 0, 13, 4, 1]}
        alternate = {'key': [*row['key']]}
        alternate['key'][14] = 9
        self.assertEqual(variant_key(row), variant_key(alternate))
        alternate['key'][1] = 123
        self.assertNotEqual(variant_id('cellularbars', variant_key(row)), variant_id('cellularbars', variant_key(alternate)))
        self.assertEqual(sorted([variant_key(alternate), variant_key(row)], key=default_variant_key)[0], variant_key(row))

    def test_compressed_asset_is_deterministic_utf8(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'a.json.gz'
            write_gzip(path, {'name': '中文'})
            first = path.read_bytes()
            write_gzip(path, {'name': '中文'})
            self.assertEqual(first, path.read_bytes())
            self.assertEqual(json.loads(gzip.decompress(first)), {'name': '中文'})

    def test_circle_uses_original_start_and_orientation(self):
        commands = circle_commands({'cx': '2', 'cy': '3', 'r': '1'})
        self.assertEqual(commands[0], ['M', 3, 3])
        self.assertEqual([command[-2:] for command in commands[1:5]], [[2, 4], [1, 3], [2, 2], [3, 3]])
        self.assertEqual(circle_commands({'cx': '2', 'cy': '3'}), [])

    def test_logical_compilation_preserves_subset_order_and_raw_flags(self):
        frames = [{'flags': 4, 'progress': 0, 'first': 1.25, 'second': 3.75}]
        source = [{'contours': [{'id': index} for index in range(4)],
                   'clipStrokeKeyframeSets': [frames]}]
        tree = [{'type': 'layer', 'systemContourSubset': True, 'sourcePathIndices': [0],
                 'sourcePathContourIndices': [[0, 3], [0, 1]], 'sourceModeStyles': {}, 'sourceAttributes': {}}]
        layers, semantic = logical_compiled_layers(source, tree)
        self.assertTrue(semantic)
        self.assertEqual(layers[0]['contours'], [{'id': 3}, {'id': 1}])
        self.assertEqual(layers[0]['clipStrokeKeyframes'], [{'flags': 4, 'progress': 0, 'first': 1.25, 'second': .75}])
        self.assertEqual(tree[0]['sourceLayerIndex'], 0)
        tree[0]['sourcePathContourIndices'] = [[0, 1]]
        with self.assertRaisesRegex(ValueError, 'crosses semantic leaf'):
            logical_compiled_layers(source, tree)

    @unittest.skipUnless(os.environ.get('SF_SYSTEM_CATALOG_DIR'), 'Set SF_SYSTEM_CATALOG_DIR for original system catalog checks')
    def test_each_named_selector_keeps_its_own_style_for_a_shared_path(self):
        root = Path(os.environ['SF_SYSTEM_CATALOG_DIR']) / 'system-vector-bvg'
        rows = json.loads((root / 'index.json').read_text(encoding='utf-8'))['records']
        record = next(row for row in rows if row['name'] == 'air.purifier.fill' and row['key'][14] == 4)
        decoded = native_selector_document(read_bvg(root / record['file']))
        mono = [node for node in decoded['tree'] if node['systemRenderingMode'] == 'monochrome']
        erase = next(node for node in mono if node['sourceModeStyles']['monochrome']['selector'] == '.monochrome-0')
        draw = next(node for node in mono if node['sourceModeStyles']['monochrome']['selector'] == '.monochrome-2')
        self.assertTrue(set(erase['components']).intersection(draw['components']))
        self.assertEqual(erase['sourceModeStyles']['monochrome']['opacity'], '0.0')
        self.assertNotIn('opacity', draw['sourceModeStyles']['monochrome'])
        self.assertNotIn('-sfsymbols-clear-behind', draw['sourceModeStyles']['monochrome'])

    @unittest.skipUnless(os.environ.get('SF_SYSTEM_CATALOG_DIR'), 'Set SF_SYSTEM_CATALOG_DIR for original system catalog checks')
    def test_same_selector_concatenates_paths_and_offsets_keyframes(self):
        root = Path(os.environ['SF_SYSTEM_CATALOG_DIR']) / 'system-vector-bvg'
        rows = json.loads((root / 'index.json').read_text(encoding='utf-8'))['records']
        record = next(row for row in rows if row['name'] == 'homepod.mini.arrow.forward' and row['key'][14] == 4)
        source = read_bvg(root / record['file'])
        raw = system_document(source)['layers']
        decoded = native_selector_document(source)
        node = next(node for node in decoded['tree'] if node['systemRenderingMode'] == 'monochrome' and node['systemNativeLayerIndex'] == 2)
        compiled = decoded['layers'][node['sourceLayerIndex']]
        self.assertEqual(node['sourcePathContourIndices'], [[2, 0], [3, 0]])
        self.assertEqual(len(compiled['contours']), 2)
        self.assertEqual(len(compiled['clipStrokeKeyframes']), 3)
        for before, after in zip(raw[3]['clipStrokeKeyframes'], compiled['clipStrokeKeyframes']):
            self.assertAlmostEqual(after['first'], before['first'] + 1, places=6)
            self.assertAlmostEqual(after['second'], before['second'] + 1, places=6)

    @unittest.skipUnless(os.environ.get('SF_SYSTEM_CATALOG_DIR'), 'Set SF_SYSTEM_CATALOG_DIR for original system catalog checks')
    def test_named_identifiers_match_original_catalog_metadata(self):
        root = Path(os.environ['SF_SYSTEM_CATALOG_DIR'])
        actual = CoreGlyphsCatalog(root / 'Assets.car').metadata()
        self.assertEqual(len(actual['localizations']), 28)
        self.assertEqual(actual['localizations']['ar'], 59797)
        self.assertEqual(actual['localizations']['ja'], 29613)
        expected = json.loads((root / 'selected-inventory.json').read_text(encoding='utf-8'))[0]['Appearances']
        self.assertEqual(actual['appearances'], expected)

    @unittest.skipUnless(os.environ.get('SF_SYSTEM_CATALOG_DIR'), 'Set SF_SYSTEM_CATALOG_DIR for original system catalog checks')
    def test_system_metadata_matches_retained_original_getters(self):
        root = Path(os.environ['SF_SYSTEM_CATALOG_DIR'])
        rows = json.loads((root / 'system-vector-bvg/index.json').read_text(encoding='utf-8'))['records']
        expected = json.loads((root / 'target-vector-metadata.json').read_text(encoding='utf-8'))['renditions']
        for row in expected:
            actual = next(record for record in rows if record['name'] == row['name'].removesuffix('.svg') and record['key'] == row['key'])
            metadata = decode_metadata(actual)
            self.assertEqual(metadata['resource1018'], row['resource1018'])
            self.assertEqual(metadata['resource1019'], row['resource1019'])

    @unittest.skipUnless(os.environ.get('SF_SYSTEM_CATALOG_DIR'), 'Set SF_SYSTEM_CATALOG_DIR for original system catalog checks')
    def test_semantic_tree_preserves_shape_group_and_source_components(self):
        root = Path(os.environ['SF_SYSTEM_CATALOG_DIR']) / 'system-vector-bvg'
        rows = json.loads((root / 'index.json').read_text(encoding='utf-8'))['records']
        record = next(row for row in rows if row['name'] == 'flag.slash.circle.fill' and row['key'][14] == 4)
        decoded = system_document(read_bvg(root / record['file']))
        self.assertEqual(decoded['tree'][0]['components'], [0])
        group = decoded['tree'][1]
        self.assertEqual(group['type'], 'shapeGroup')
        self.assertEqual([node['components'] for node in group['children']], [[1], [3], [2]])
        self.assertEqual([node['shapeGroupBehavior'] for node in group['children']], ['draw', 'erase', 'draw'])
        self.assertEqual([node['sourcePathContourIndices'] for node in group['children']], [[[0, 1]], [[0, 3]], [[0, 2]]])


if __name__ == '__main__':
    unittest.main()
