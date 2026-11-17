from pathlib import Path
import json
import os
import unittest

from bvg import read_bvg
from bvg_numbers import decode
from import_system_symbols import absolute_commands, compiled_keyframes, compiled_keyframe_sets, contours_from_commands, inverse, system_layers, transform_point, interpolate_contours


class SystemCompiledSourceTest(unittest.TestCase):
    def test_compiled_endpoint_order_is_preserved(self):
        frames = compiled_keyframes([0, 2, 0, .8, .1, 0, 1, .3, .7])
        self.assertGreater(frames[0]['first'], frames[0]['second'])
        self.assertEqual(frames[0]['flags'], 2)
        self.assertEqual(frames[1]['progress'], 1)

    def test_relative_controls_use_segment_start(self):
        commands = absolute_commands([['m', 10, 20], ['c', 1, 2, 3, 4, 5, 6, 2, 3, 4, 5, 6, 7], ['Z']])
        self.assertEqual(commands[1], ['C', 11, 22, 13, 24, 15, 26])
        self.assertEqual(commands[2], ['C', 17, 29, 19, 31, 21, 33])

    def test_source_units_flip_y_without_outer_canvas_scaling(self):
        contours = contours_from_commands([['m', 10, -20], ['h', 5], ['v', 10], ['Z']])
        self.assertEqual(contours, [{'verbs': 'MLLZ', 'points': [10, 20, 15, 20, 15, 10]}])
        matrix = (.00634765, 0, 0, .00634765, -.888671, 9.50244)
        point = transform_point(matrix, 12, -34)
        source = transform_point(inverse(matrix), *point)
        self.assertAlmostEqual(source[0], 12)
        self.assertAlmostEqual(source[1], -34)

    def test_invalid_compiled_formats_are_explicit(self):
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            compiled_keyframes([0, 2, 0, .5])
        with self.assertRaisesRegex(ValueError, 'offset'):
            compiled_keyframes([1, 1, 3, 0, 1, .2, .8])
        with self.assertRaisesRegex(ValueError, 'Unsupported BVG'):
            decode(b'\x01')

    def test_compound_keyframe_sets_keep_native_boundaries(self):
        sets = compiled_keyframe_sets([1, 2, 3, 7, 2, 0, .8, .1, 0, 1, 1.3, 1.7])
        self.assertEqual([len(value) for value in sets], [1, 1])
        self.assertEqual(sets[0][0]['flags'], 2)
        self.assertEqual(sets[1][0]['progress'], 1)
        self.assertGreater(sets[1][0]['first'], 1)

    def test_named_sample_scales_after_system_master_blend(self):
        contours = lambda x: [{'verbs': 'MLZ', 'points': [x, 0, x + 1, 0]}]
        result = interpolate_contours(contours(10), contours(2), contours(30), [.25, .5], 1.276)
        self.assertAlmostEqual(result[0]['points'][0], 18 * 1.276)
        with self.assertRaisesRegex(ValueError, 'topology'):
            interpolate_contours(contours(10), [{'verbs': 'MZ', 'points': [2, 0]}], contours(30), [.25, .5], 1)

    def test_semantic_group_is_never_silently_flattened(self):
        group = {'tag': 'g', 'attributes': {}, 'classIndices': [0], 'children': []}
        document = {'tree': {'tag': 'svg', 'attributes': {}, 'classIndices': [], 'children': [group]}}
        with self.assertRaisesRegex(ValueError, 'hierarchy'):
            system_layers(document)

    @unittest.skipUnless(os.environ.get('SF_SYSTEM_BVG_DIR'), 'Set SF_SYSTEM_BVG_DIR to verify retained system decoder oracles')
    def test_pure_decoder_matches_original_coreui_documents(self):
        paths = sorted(Path(os.environ['SF_SYSTEM_BVG_DIR']).glob('*.decoded.json'))
        self.assertGreaterEqual(len(paths), 6)
        for path in paths:
            original = json.loads(path.read_text(encoding='utf-8'))
            actual = read_bvg(path.with_suffix('').with_suffix('.bvg'))
            self.assertEqual(actual, original, path.name)
            layers, _ = system_layers(actual)
            self.assertEqual(len(layers), 4)


if __name__ == '__main__':
    unittest.main()
