import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from report_jvm_catalog import report


class EncodedCatalogReportTest(unittest.TestCase):
    def inspect(self, strings):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            with zipfile.ZipFile(folder / 'consumer.apk', 'w'):
                pass
            inventory = folder / 'inventory.json'
            records = [
                {'format': 'jvm-symbols-v1'},
                {'entry': 'chosen:Regular', 'files': [
                    {'asset': 'symbols/chosen.json', 'entryMarker': True, 'sha256': 'chosen', 'bytes': 11, 'chunks': ['chosen']},
                    {'asset': 'symbols/shared.json', 'entryMarker': False, 'sha256': 'shared', 'bytes': 7, 'chunks': ['shared']},
                ]},
                {'entry': 'unused:Regular', 'files': [
                    {'asset': 'symbols/unused.json', 'entryMarker': True, 'sha256': 'unused', 'bytes': 13, 'chunks': ['unused', 'shared']},
                    {'asset': 'symbols/orphan.json', 'entryMarker': False, 'sha256': 'orphan', 'bytes': 17, 'chunks': ['orphan']},
                ]},
            ]
            inventory.write_text('\n'.join(json.dumps(record) for record in records), encoding='utf-8')
            observed = {chunk: [('classes.dex', 5)] for chunk in strings}
            with patch('report_jvm_catalog.dex_strings', return_value=observed), contextlib.redirect_stdout(io.StringIO()):
                return report(folder, inventory, folder / 'report.json', ['chosen:Regular'])

    def test_shared_chunks_do_not_retain_their_other_entries(self):
        result = self.inspect(['chosen', 'shared'])
        self.assertEqual(result['entries'], ['chosen:Regular'])
        self.assertEqual(result['symbolBytes'], 18)

    def test_unused_parent_entry_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Entry mismatch'):
            self.inspect(['chosen', 'shared', 'unused', 'orphan'])

    def test_resource_without_a_retained_entry_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'outside retained closures'):
            self.inspect(['chosen', 'shared', 'orphan'])

    def test_incomplete_resource_closure_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Incomplete payload closure'):
            self.inspect(['chosen'])


if __name__ == '__main__':
    unittest.main()
