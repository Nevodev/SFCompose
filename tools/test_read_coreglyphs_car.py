import struct
import tempfile
import unittest
from pathlib import Path

from read_coreglyphs_car import CoreGlyphsCatalog


def catalog_bytes(second_backward=3, second_following=0, expected=2):
    blocks = [b'', struct.pack('>4s5I', b'tree', 1, 2, 0, expected, 0),
              struct.pack('>HHIIII', 0, 1, 0, 0, 3, 6),
              struct.pack('>HHIIII', 1, 1, 4, 0, 5, 6),
              struct.pack('>HHIIII', 1, 1, second_following, second_backward, 7, 8),
              b'first-vector', struct.pack('<16H', *range(16)),
              b'last-vector', struct.pack('<16H', *range(16, 32))]
    variables = struct.pack('>IIB', 1, 1, 10) + b'RENDITIONS'
    index_size = 4 + 8 * len(blocks)
    cursor = 32 + index_size + len(variables)
    entries = []
    for block in blocks:
        entries.append((cursor, len(block)))
        cursor += len(block)
    header = struct.pack('>8s6I', b'BOMStore', 1, len(blocks), 32, index_size,
                         32 + index_size, len(variables))
    index = struct.pack('>I', len(blocks)) + b''.join(struct.pack('>II', *entry) for entry in entries)
    return header + index + variables + b''.join(blocks)


class CoreGlyphsCatalogTest(unittest.TestCase):
    def read(self, data):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'Assets.car'
            path.write_bytes(data)
            reader = CoreGlyphsCatalog(path)
            return [(key, reader.block(value)) for key, value in reader.records()]

    def test_forward_chain_includes_leaf_missing_from_root_separators(self):
        records = self.read(catalog_bytes())
        self.assertEqual([item[1] for item in records], [b'first-vector', b'last-vector'])
        self.assertEqual(records[1][0], tuple(range(16, 32)))

    def test_broken_backward_link_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Invalid rendition leaf chain'):
            self.read(catalog_bytes(second_backward=2))

    def test_cyclic_leaf_chain_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Cyclic rendition leaf chain'):
            self.read(catalog_bytes(second_following=3))

    def test_rendition_count_cannot_silently_truncate(self):
        with self.assertRaisesRegex(ValueError, 'Rendition count mismatch'):
            self.read(catalog_bytes(expected=3))

    def test_truncated_payload_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'BOM region outside catalog'):
            self.read(catalog_bytes()[:-1])

    def test_truncated_header_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Truncated BOM header'):
            self.read(b'BOMStore')


if __name__ == '__main__':
    unittest.main()
