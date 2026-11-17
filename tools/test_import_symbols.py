"""Behavior checks for contour extraction and the generated original catalog.

Run with the same Python environment used by the importer:
python -m unittest discover -s tools -p test_import_symbols.py
"""
from pathlib import Path
import json
import math
import os
import struct
import unittest
import zipfile

from fontTools.ttLib import TTFont

from import_symbols import OutlinePen, digest, drawing_identifier


ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/symbols'


class OutlineExtractionTest(unittest.TestCase):
    def test_composite_source_selection_preserves_synthetic_identifiers(self):
        self.assertEqual(drawing_identifier({'PUAs': '1002DD', 'Composite Symbol Data': 'TRUE', 'Composite Symbol PUA': '101A81'}), '101A81')
        self.assertEqual(drawing_identifier({'PUAs': 'U+100078|badge.checkmark', 'Composite Symbol Data': 'TRUE', 'Composite Symbol PUA': 'COMPOSITE_ONLY'}), 'U+100078|badge.checkmark')

    def test_open_contours_are_rejected(self):
        pen = OutlinePen(None)
        pen.moveTo((0, 0))
        pen.lineTo((10, 10))
        with self.assertRaisesRegex(ValueError, 'Open contour'):
            pen.endPath()

    def test_component_decomposition_preserves_transform_and_contours(self):
        class Glyph:
            def draw(self, pen):
                pen.moveTo((0, 0))
                pen.curveTo((0, 10), (10, 10), (10, 0))
                pen.lineTo((0, 0))
                pen.closePath()

        pen = OutlinePen({'source': Glyph()})
        pen.addComponent('source', (2, 0, 0, 3, 4, 5))
        self.assertEqual(pen.contours, [{'verbs': 'MCLZ', 'points': [4.0, 5.0, 4.0, 35.0, 24.0, 35.0, 24.0, 5.0, 4.0, 5.0]}])


class OriginalCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        archive_path = os.environ.get('SF_SYMBOLS_LEGACY_ASSETS_ZIP')
        cls.archive = None
        if archive_path:
            cls.archive = zipfile.ZipFile(Path(archive_path))
            cls.addClassCleanup(cls.archive.close)
            cls.index = json.loads(cls.archive.read('index.json').decode('utf-8'))
        else:
            index_path = ASSETS / 'index.json'
            if not index_path.is_file():
                raise unittest.SkipTest('Import legacy font assets or set SF_SYMBOLS_LEGACY_ASSETS_ZIP')
            cls.index = json.loads(index_path.read_text(encoding='utf-8'))
        if cls.index.get('source', {}).get('kind', '').startswith('macOS-system'):
            raise unittest.SkipTest('Production assets use the system catalog; set SF_SYMBOLS_LEGACY_ASSETS_ZIP for isolated legacy font checks')

    def symbol(self, name):
        if self.archive is not None:
            symbol = json.loads(self.archive.read(name + '.json').decode('utf-8'))
        else:
            symbol = json.loads((ASSETS / (name + '.json')).read_text(encoding='utf-8'))
        if symbol.get('sourceKind', '').startswith('macOS-system'):
            self.skipTest(f'{name} is a system rendition retained in the legacy snapshot; covered by system catalog checks')
        return symbol

    def test_original_known_name_mapping_and_layers(self):
        heart = self.symbol('heart.fill')
        wifi = self.symbol('wifi')
        self.assertEqual(heart['pua'], 0x1002B5)
        self.assertEqual(heart['layerTree'][0]['multicolor']['color'], 'systemRedColor')
        self.assertEqual([node['components'] for node in wifi['layerTree']], [[2], [1], [0]])
        self.assertTrue(all(node['autovariable'] for node in wifi['layerTree']))
        self.assertIn('drawRailRoadTies', wifi['layerTree'][1])

    def test_cutout_annotations_remain_in_the_catalog(self):
        symbol = self.symbol('person.crop.circle.fill')

        def nodes(value):
            for node in value:
                yield node
                yield from nodes(node.get('children', []))

        self.assertTrue(any(node.get('monochrome') == 'erase' for node in nodes(symbol['layerTree'])))

    def test_composite_outlines_and_semantic_annotations_are_imported_together(self):
        slash = self.symbol('bell.slash')
        self.assertEqual(slash['pua'], 0x1002DD)
        self.assertEqual(slash['sourceGlyphPUA'], 0x101A81)
        self.assertEqual(slash['scales']['medium']['glyphName'], 'uni101A81.medium')
        self.assertEqual(len(slash['scales']['medium']['masters'][1]['contours']), 5)
        self.assertIn('bell', slash['layerTree'][0]['tags'])
        self.assertTrue(all('_slash' in node['tags'] for node in slash['layerTree'][1]['children']))
        badge = self.symbol('folder.badge.plus')
        self.assertEqual(badge['sourceGlyphPUA'], 0x100D33)
        self.assertEqual(len(badge['scales']['medium']['masters'][1]['contours']), 6)
        self.assertTrue(all('_badge' in node['tags'] for node in badge['layerTree'][1]['children']))

    def test_all_records_have_usable_outline_commands_and_matching_masters(self):
        widths = {'M': 2, 'L': 2, 'Q': 4, 'C': 6, 'Z': 0}
        self.assertEqual(len(self.index['symbols']), len(set(self.index['symbols'])))
        checked = 0
        for name in self.index['symbols']:
            with self.subTest(name=name):
                symbol = self.symbol(name)
                self.assertEqual(symbol['name'], name)
                self.assertEqual(set(symbol['scales']), {'small', 'medium', 'large'})
                for scale in symbol['scales'].values():
                    expected_weights = sorted(self.index['weights'].values()) if 'sourceIdentifier' in symbol else self.index['source']['masterWeights']
                    self.assertEqual([master['weight'] for master in scale['masters']], expected_weights)
                    topology = None
                    for master in scale['masters']:
                        current = [contour['verbs'] for contour in master['contours']]
                        if topology is not None:
                            self.assertEqual(current, topology, name)
                        topology = current
                        for contour in master['contours']:
                            verbs = contour['verbs']
                            self.assertTrue(verbs.startswith('M') and verbs.endswith('Z'), name)
                            self.assertEqual(len(contour['points']), sum(widths[verb] for verb in verbs), name)
                            self.assertTrue(all(math.isfinite(value) for value in contour['points']), name)
                checked += 1
        print(f'Legacy catalog topology: {checked} font records checked from {len(self.index["symbols"])} catalog names')

    def test_source_weights_and_explicit_unsupported_records(self):
        weights = self.index['weights']
        self.assertLess(weights['Ultralight'], weights['Thin'])
        self.assertEqual(weights['Regular'], 400)
        self.assertGreater(weights['Black'], 900)
        unsupported = {row['name']: row['reason'] for row in self.index['unsupported']}
        command = self.symbol('command')
        self.assertEqual(command['layerTree'][0]['components'], [0, 1])
        self.assertEqual(command['nativeSkippedComponents']['medium'], [1])
        self.assertEqual(len(command['scales']['medium']['masters'][1]['contours']), 1)
        self.assertTrue(all(name not in unsupported for name in self.index['symbols']))

    def test_synthetic_composite_has_original_identity_and_native_weight_samples(self):
        symbol = self.symbol('arrow.down.circle.badge.checkmark')
        self.assertEqual(symbol['sourceIdentifier'], 'U+100078|badge.checkmark')
        self.assertEqual(symbol['scales']['small']['masters'][3]['advance'], 1895)
        self.assertEqual(symbol['scales']['medium']['masters'][3]['nativeWeight'], 0)
        self.assertEqual(len(symbol['scales']['large']['masters']), len(self.index['weights']))
        self.assertTrue(symbol['layerTree'])

    @unittest.skipUnless(os.environ.get('SF_SYMBOLS_FONT'), 'Set SF_SYMBOLS_FONT to compare with the original local font')
    def test_android_float_interpolation_matches_original_named_weight_outlines(self):
        def float32(value):
            return struct.unpack('<f', struct.pack('<f', value))[0]

        def interpolate(start, end, fraction):
            return float32(float32(start) + float32(float32(float32(end) - float32(start)) * fraction))

        source = Path(os.environ['SF_SYMBOLS_FONT'])
        self.assertEqual(digest(source), self.index['source']['fontSha256'])
        font = TTFont(source)
        maximum_error = 0.0
        sample_count = 0
        try:
            for name in ['heart.fill', 'wifi', 'bell.slash', 'folder.badge.plus']:
                symbol = self.symbol(name)
                for scale in symbol['scales'].values():
                    masters = scale['masters']
                    for weight in self.index['weights'].values():
                        lower, upper = masters[0], masters[-1]
                        for first, second in zip(masters, masters[1:]):
                            if weight <= second['weight']:
                                lower, upper = first, second
                                break
                        fraction = float32(float32(float32(weight) - float32(lower['weight'])) /
                                           float32(float32(upper['weight']) - float32(lower['weight'])))
                        glyphs = font.getGlyphSet(location={'wght': weight})
                        glyph = glyphs[scale['glyphName']]
                        pen = OutlinePen(glyphs)
                        glyph.draw(pen)
                        self.assertEqual(len(pen.contours), len(lower['contours']), name)
                        errors = [abs(glyph.width - interpolate(lower['advance'], upper['advance'], fraction))]
                        for actual, first, second in zip(pen.contours, lower['contours'], upper['contours']):
                            self.assertEqual(actual['verbs'], first['verbs'], name)
                            self.assertEqual(len(actual['points']), len(first['points']), name)
                            errors.extend(abs(value - interpolate(start, end, fraction)) for value, start, end in
                                          zip(actual['points'], first['points'], second['points']))
                        maximum_error = max(maximum_error, max(errors))
                        self.assertLess(max(errors), 0.001, f'{name}/{scale["glyphName"]}/{weight}')
                        sample_count += 1
        finally:
            font.close()
        print(f'Original font comparison: {sample_count} named-weight/scale samples; '
              f'maximum Android Float interpolation error {maximum_error:.9f} font units')


if __name__ == '__main__':
    unittest.main()
