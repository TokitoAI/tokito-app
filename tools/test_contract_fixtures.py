"""Validate language-neutral physical-runtime contract fixtures."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "contracts" / "physical-runtime.v1.schema.json"
FIXTURES = ROOT / "contracts" / "fixtures"


def load_json(path: Path) -> object:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


class ContractFixturesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = load_json(SCHEMA_PATH)
        Draft202012Validator.check_schema(cls.schema)
        cls.registry = Registry().with_resource(
            SCHEMA_PATH.as_uri(), Resource.from_contents(cls.schema)
        )

    def validate_fixture(self, definition: str, fixture_name: str) -> None:
        validator = Draft202012Validator(
            {"$ref": f"{SCHEMA_PATH.as_uri()}#/$defs/{definition}"},
            registry=self.registry,
        )
        fixture = load_json(FIXTURES / fixture_name)
        self.assertEqual(list(validator.iter_errors(fixture)), [])

    def test_capability_handshake_fixture(self) -> None:
        self.validate_fixture("capabilityHandshake", "physical-runtime.v1.handshake.json")

    def test_tool_call_fixture(self) -> None:
        self.validate_fixture("toolCall", "physical-runtime.v1.tool-call.json")


if __name__ == "__main__":
    unittest.main()
