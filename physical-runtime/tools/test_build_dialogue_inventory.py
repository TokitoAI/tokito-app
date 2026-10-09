import json
import tempfile
import unittest
from pathlib import Path
from build_dialogue_inventory import build


class DialogueInventoryTest(unittest.TestCase):
    def test_all_payloads_are_hashed_and_anchor_is_inside_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'bundle').mkdir()
            (root / 'bundle/metadata.json').write_text('{"model_id":"example"}')
            (root / 'bundle/part.bin').write_bytes(b'weights')
            build(root, 'example')
            doc = json.loads((root / 'dialogue-model.manifest.json').read_text())
            self.assertEqual(doc['model_path'], 'bundle/metadata.json')
            self.assertEqual({x['path'] for x in doc['artifacts']}, {'bundle/metadata.json', 'bundle/part.bin'})
            self.assertTrue(all(len(x['sha256']) == 64 and x['size_bytes'] > 0 for x in doc['artifacts']))

    def test_missing_metadata_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                build(Path(directory), 'example')
