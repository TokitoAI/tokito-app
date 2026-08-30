#!/usr/bin/env python3
"""End-to-end test of the deterministic component chain, on real artifacts.

Runs the full path a user request takes on the board-data side:

    "where is the main chip"
        -> canonical component_id
        -> authored board position / side
        -> GLB selector
        -> that selector actually exists in the exported GLB
        -> and is independently highlightable
        -> plus its electrical facts (nets, endpoints, polarity)

Every step reads a real generated artifact -- no fixtures, no stubs. The
camera, registration and AR rendering are deliberately out of scope here:
those run on the device and depend on the vision lane's pose bundle. This
covers everything between "a person said something" and "the AR layer is
handed a selector and a position", which is the part this lane owns.

The test skips (rather than fails) when the generated artifacts are absent,
so CI without a KiCad install stays green while a developer with the build
outputs gets real coverage.
"""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from component_info import get_component_info, get_connection_guidance
from glb_inspect import load_gltf_json
from resolve_component_query import resolve_query
from verify_component_highlightability import verify_highlightability

GENERATED = Path(__file__).resolve().parent.parent / "build" / "ar-assets" / "frdm-mcxn236" / "generated"
COMPONENT_MAP = GENERATED / "frdm_mcxn236_component_map.json"
INDEX = GENERATED / "board_component_index.json"
# the lean export is the AR production asset: the heavier pads+soldermask
# variant crashes Filament's render thread on-device (Adreno allocation abort)
AR_GLB = GENERATED / "frdm_mcxn236_ar_lean_annotated.glb"
MANIFEST = GENERATED / "frdm_mcxn236_digital_twin_manifest.json"
BOARD_REFERENCE = Path(__file__).resolve().parent.parent / "reference" / "boards" / "frdm-mcxn236.board-reference.json"


def _load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


@unittest.skipUnless(
    COMPONENT_MAP.is_file() and INDEX.is_file() and AR_GLB.is_file() and MANIFEST.is_file(),
    "generated board artifacts are not present (run the 3D pipeline first)",
)
class ComponentChainEndToEndTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.component_map = _load(COMPONENT_MAP)
        cls.index = _load(INDEX)
        cls.manifest = _load(MANIFEST)
        cls.gltf = load_gltf_json(AR_GLB)
        cls.board_reference = _load(BOARD_REFERENCE) if BOARD_REFERENCE.is_file() else None
        cls.node_names = {node.get("name") for node in cls.gltf.get("nodes", []) if node.get("name")}

    def _resolve(self, query: str) -> dict:
        result = resolve_query(query, self.component_map, self.board_reference)
        self.assertEqual(result["status"], "resolved", f"{query!r} -> {result.get('reason') or result['status']}")
        return result

    def _assert_selector_is_live(self, result: dict) -> None:
        """The selector must name a real node in the exported GLB, and the
        manifest's declared prefix must be the one actually used."""
        selector = result["glb_selector"]
        self.assertTrue(selector.startswith(self.manifest["componentNodePrefix"]))
        self.assertIn(selector, self.node_names, f"{selector} is not a node in the exported GLB")

    def test_where_is_the_main_chip(self) -> None:
        result = self._resolve("where is the main chip")
        self.assertEqual(result["reference"], "U1")
        self.assertEqual(result["component_id"], "nxp-frdm-mcxn236:C:U1")
        self._assert_selector_is_live(result)
        # position must be real authored data, not a default
        self.assertNotEqual((result["board_position_mm"]["x"], result["board_position_mm"]["y"]), (0.0, 0.0))
        self.assertEqual(result["side"], "top")
        # and the identity must carry through to the BOM facts the LLM explains
        info = get_component_info(self.index, result["reference"])
        self.assertEqual(info["bom"]["part_number"], "MCXN236VDFT")

    def test_find_a_specific_passive(self) -> None:
        result = self._resolve("find R87")
        self.assertEqual(result["reference"], "R87")
        self._assert_selector_is_live(result)

    def test_rgb_led_resolves_by_role(self) -> None:
        result = self._resolve("where is the RGB LED")
        self.assertEqual(result["component_class"], "diode_or_led")
        self._assert_selector_is_live(result)

    def test_every_resolvable_query_yields_a_highlightable_node(self) -> None:
        """Resolution and highlightability must agree: anything the resolver
        hands to the AR lane has to be independently selectable."""
        queries = ["where is the main chip", "find R87", "where is the RGB LED", "show me the accelerometer"]
        references = [self._resolve(query)["reference"] for query in queries]
        report = verify_highlightability(self.gltf, references, self.manifest["componentNodePrefix"])
        self.assertEqual(report["fail_count"], 0, report["failures"])

    def test_connection_guidance_reaches_real_copper(self) -> None:
        """A diode request must produce source-backed electrical facts."""
        result = self._resolve("find D1")
        guidance = get_connection_guidance(self.index, result["reference"])
        self.assertEqual(guidance["status"], "ok")
        self.assertEqual(guidance["polarity_status"], "source_verified")
        by_pad = {terminal["pad"]: terminal for terminal in guidance["terminals"]}
        self.assertEqual({"A", "C"}, set(by_pad))
        self.assertEqual(by_pad["A"]["role"], "anode")
        for terminal in guidance["terminals"]:
            self.assertTrue(terminal["net"], "every diode terminal must sit on a real net")
            self.assertGreater(terminal["endpoint_count_total"], 0)
            for endpoint in terminal["connected_endpoints"]:
                self.assertIn(endpoint["reference"], self.index["components"])
                self.assertIsNotNone(endpoint["direction_from_component_mm"])

    def test_ambiguity_is_reported_not_guessed(self) -> None:
        result = resolve_query("where is a resistor", self.component_map, self.board_reference)
        self.assertEqual(result["status"], "ambiguous")
        self.assertGreater(result["candidate_count"], 1)

    def test_manifest_describes_the_glb_that_is_actually_shipped(self) -> None:
        import hashlib

        raw = AR_GLB.read_bytes()
        self.assertEqual(self.manifest["glbBytes"], len(raw))
        self.assertEqual(self.manifest["glbSha256"], "sha256:" + hashlib.sha256(raw).hexdigest())


if __name__ == "__main__":
    unittest.main()
