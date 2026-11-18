from pathlib import Path
import gzip
import hashlib
import json
import struct
import tempfile
import unittest

from import_resource_selection import build_selection


class ResourceSelectionImportTest(unittest.TestCase):
    def test_original_csi_bit_six_and_existing_counterparts_are_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'variants').mkdir()
            car = root / 'Assets.car'
            headers = b'ISTC' + struct.pack('<II', 1, 0x44) + b'ISTC' + struct.pack('<II', 1, 0x04)
            car.write_bytes(headers)
            for name, variant, offset in [('forward', 'flippable', 0), ('other', 'fixed', 12), ('other.rtl', 'rtl', 12)]:
                data = {'name': name, 'systemVariantId': variant,
                        'sourceSystemRenditions': [{'csiOffset': offset}]}
                (root / (name + '.json.sfgz')).write_bytes(gzip.compress(json.dumps(data).encode('utf-8')))
            manifest, audit = build_selection(root, car, hashlib.sha256(headers).hexdigest())
            self.assertEqual(['flippable'], manifest['flippableVariants'])
            self.assertEqual({'other': 'other.rtl'}, manifest['rightToLeftNames'])
            self.assertEqual(0x44, audit['headers']['flippable'][0]['flags'])
            self.assertEqual(hashlib.sha256(headers[:12]).hexdigest(), audit['headers']['flippable'][0]['sha256'])

    def test_mismatched_catalog_source_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            car = root / 'Assets.car'
            car.write_bytes(b'ISTC' + struct.pack('<II', 1, 4))
            with self.assertRaisesRegex(ValueError, 'does not match'):
                build_selection(root, car, 'wrong-source')


if __name__ == '__main__':
    unittest.main()
