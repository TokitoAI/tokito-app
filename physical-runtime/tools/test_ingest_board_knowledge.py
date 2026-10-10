"""Compiler security/format tests; these fixtures never enter the released corpus."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from ingest_board_knowledge import compile_corpus, read_source


class CompilerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Compiler Test")
        self.git("config", "user.email", "compiler@example.invalid")
        self.put("source-bundles.json", json.dumps({
            "board-a": {"path": "a", "relation": "reference_variant", "exact_component_mapping": False},
            "board-b": {"path": "b", "relation": "revision_unknown", "exact_component_mapping": False},
            "board-c": {"path": "c", "relation": "source_revision", "exact_component_mapping": False},
        }))
        self.put("docs/sources/LICENSE_NOTES.md", "Private source review; no redistribution grant.\n")
        self.put("a/community-clone/structured/bom-components.csv", "reference,value,footprint\nU1,MCU,QFN\n")
        self.put("b/structured-source/schematic-component-inventory.json", json.dumps([
            {"designator": "U1", "part_id": 1, "comment": "=Value", "parameters": {"Value": "Controller"}},
            {"designator": "U1", "part_id": 2, "comment": "=Value", "parameters": {"Value": "Controller"}},
        ]))
        self.put("c/structured-rev-c/components.json", json.dumps({"components": [
            {"refdes": "U1", "dnp_explicit_in_bom": True, "bom_columns": {"Description": "Unfitted controller"},
             "connections": [{"pin": "A4", "net_name": "POWER"}]},
        ]}))
        self.commit()

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True).stdout.decode().strip()

    def put(self, relative, text):
        file = self.root / relative
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding="utf-8", newline="\n")

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "Fixture source")

    def test_real_format_adapters_preserve_source_authority_and_units(self):
        corpus = compile_corpus(self.root, ["board-a", "board-b", "board-c"])
        self.assertEqual(4, len(corpus["records"]))
        self.assertEqual(4, len({r["record_id"] for r in corpus["records"]}))
        self.assertEqual(3, len({r["component_id"] for r in corpus["records"]}))
        self.assertTrue(all(r["ar_node_id"] is None and not r["physical_identity_proven"] for r in corpus["records"]))
        dnp = next(r for r in corpus["records"] if r["board_id"] == "board-c")
        self.assertTrue(dnp["facts"]["dnp_explicit_in_bom"])
        self.assertEqual("A4", dnp["facts"]["connections"][0]["pin"])
        self.assertFalse(corpus["distribution_admitted"])
        self.assertEqual(corpus, compile_corpus(self.root, ["board-a", "board-b", "board-c"]))

    def test_board_scope_required_unique_and_known(self):
        for scope in ([], ["board-a", "board-a"], ["missing"]):
            with self.assertRaises(ValueError):
                compile_corpus(self.root, scope)

    def test_dirty_source_rejected(self):
        self.put("a/community-clone/structured/bom-components.csv", "reference,value,footprint\nU1,CHANGED,QFN\n")
        with self.assertRaisesRegex(ValueError, "differs"):
            compile_corpus(self.root, ["board-a"])

    def test_lfs_pointer_is_not_evidence(self):
        self.put("a/community-clone/structured/bom-components.csv", "version https://git-lfs.github.com/spec/v1\n")
        self.commit()
        with self.assertRaisesRegex(ValueError, "LFS"):
            compile_corpus(self.root, ["board-a"])

    def test_crlf_has_committed_source_identity(self):
        file = self.root / "a/community-clone/structured/bom-components.csv"
        file.write_bytes(file.read_bytes().replace(b"\n", b"\r\n"))
        self.assertEqual("U1", compile_corpus(self.root, ["board-a"])["records"][0]["reference_designator"])

    def test_path_escape_rejected(self):
        with self.assertRaises(ValueError):
            read_source(self.root, "../outside", self.git("rev-parse", "HEAD"))

    def test_ambiguous_inventory_rejected(self):
        self.put("a/structured-source/schematic-component-inventory.json", "[]")
        self.commit()
        with self.assertRaisesRegex(ValueError, "one supported"):
            compile_corpus(self.root, ["board-a"])

    def test_reference_identifier_cannot_change_scope(self):
        self.put("a/community-clone/structured/bom-components.csv", "reference,value,footprint\nboard-b:U1,MCU,QFN\n")
        self.commit()
        with self.assertRaisesRegex(ValueError, "designator"):
            compile_corpus(self.root, ["board-a"])


if __name__ == "__main__":
    unittest.main()
