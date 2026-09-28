import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("capture_device_profile.py")
SPEC = importlib.util.spec_from_file_location("capture_device_profile", MODULE_PATH)
CAPTURE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(CAPTURE)


class DeviceProfileCaptureTests(unittest.TestCase):
    def test_properties_exclude_sensitive_identifiers(self) -> None:
        raw = "[ro.product.model]: [Target]\n[ro.serialno]: [secret]\n"

        self.assertEqual(
            CAPTURE.parse_android_properties(raw, {"ro.serialno"}),
            {"ro.product.model": "Target"},
        )

    def test_capture_records_unknowns_and_evidence_digests(self) -> None:
        config = {
            "schema_version": "tokito.device-profile-probes.v1",
            "output_schema_version": "tokito.device-profile.v1",
            "privacy": {"redacted_property_names": [], "redacted_text_patterns": []},
            "unknown_capabilities": ["declared by the probe configuration"],
            "probes": [{"id": "properties", "argv": ["shell", "getprop"], "format": "android_properties", "required": True}],
            "fields": [{"path": "device.model", "probe": "properties", "property": "ro.product.model"}],
        }
        original = CAPTURE.run_probe
        CAPTURE.run_probe = lambda *_: "[ro.product.model]: [Measured model]\n"
        try:
            profile, raw_outputs = CAPTURE.capture(config, Path("adb"), "never-written")
        finally:
            CAPTURE.run_probe = original

        self.assertEqual(profile["fields"]["device"]["model"], "Measured model")
        self.assertIn("properties", profile["probe_evidence"])
        self.assertEqual(profile["unknown_capabilities"], ["declared by the probe configuration"])
        self.assertEqual(raw_outputs["properties"], "[ro.product.model]: [Measured model]\n")

    def test_optional_probe_failure_is_explicit(self) -> None:
        config = {
            "schema_version": "tokito.device-profile-probes.v1",
            "output_schema_version": "tokito.device-profile.v1",
            "privacy": {"redacted_property_names": [], "redacted_text_patterns": []},
            "unknown_capabilities": [],
            "probes": [{"id": "optional", "argv": ["shell", "missing"], "format": "text", "required": False}],
            "fields": [],
        }
        original = CAPTURE.run_probe
        CAPTURE.run_probe = lambda *_: (_ for _ in ()).throw(RuntimeError("unavailable"))
        try:
            profile, raw_outputs = CAPTURE.capture(config, Path("adb"), "never-written")
        finally:
            CAPTURE.run_probe = original

        self.assertEqual(profile["unavailable_probes"], ["optional"])
        self.assertEqual(raw_outputs, {})

    def test_repeated_regex_matches_are_preserved_in_order(self) -> None:
        config = {
            "schema_version": "tokito.device-profile-probes.v1",
            "output_schema_version": "tokito.device-profile.v1",
            "privacy": {"redacted_property_names": [], "redacted_text_patterns": []},
            "unknown_capabilities": [],
            "probes": [{"id": "camera", "argv": ["shell", "camera"], "format": "text", "required": True}],
            "fields": [{"path": "camera.facing", "probe": "camera", "regex": "^Facing: (.+)$", "all_matches": True}],
        }
        original = CAPTURE.run_probe
        CAPTURE.run_probe = lambda *_: "Facing: Back\nFacing: Front\nFacing: Back\n"
        try:
            profile, _ = CAPTURE.capture(config, Path("adb"), "never-written")
        finally:
            CAPTURE.run_probe = original

        self.assertEqual(profile["fields"]["camera"]["facing"], ["Back", "Front", "Back"])

    def test_nonempty_output_directory_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "existing.txt").write_text("existing", encoding="utf-8")
            with self.assertRaises(ValueError):
                CAPTURE.require_empty_output_dir(path)


if __name__ == "__main__":
    unittest.main()
