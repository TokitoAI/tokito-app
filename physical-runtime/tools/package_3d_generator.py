#!/usr/bin/env python3
"""Parametric package-model generation from a record file of verified
package dimensions.

Generalizes the one-off placeholder boxes into a data-driven step: the
input is a JSON list of package records -- each with body dimensions, the
source that published them, and the references that use the package -- and
the output is one VRML body per package plus the manual-override rows the
resolver consumes. Geometry is generated per PACKAGE (an 0805 record
serves every 0805 part), never per reference; placement still comes
exclusively from the imported board file through the resolver's anchoring.

Records without a real source do not generate anything: `source` and
`evidence` are required fields, and dimensions must be positive. This tool
encodes no component- or board-specific values of its own.

Record format:
{
  "package": "MicroSMP_DO-219AD",
  "body_l_mm": 2.6, "body_w_mm": 1.45, "body_h_mm": 0.98,
  "source": "https://www.vishay.com/docs/...pdf",
  "evidence": "package outline drawing, dimensions section",
  "references": ["D1", "D2", "D4"]
}
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from make_box_model import box_vrml

REQUIRED_FIELDS = ("package", "body_l_mm", "body_w_mm", "body_h_mm", "source", "evidence", "references")


def validate_record(record: dict[str, Any]) -> None:
    for field in REQUIRED_FIELDS:
        if field not in record or record[field] in (None, "", []):
            raise ValueError(f"package record {record.get('package', '?')!r} is missing required field {field!r}")
    if min(record["body_l_mm"], record["body_w_mm"], record["body_h_mm"]) <= 0:
        raise ValueError(f"package record {record['package']!r} has non-positive dimensions")


def package_model_filename(record: dict[str, Any]) -> str:
    return f"{record['package']}_{record['body_l_mm']:g}x{record['body_w_mm']:g}x{record['body_h_mm']:g}mm_datasheet_box.wrl"


def generate_packages(records: list[dict[str, Any]], output_dir: Path) -> dict[str, Any]:
    """Writes one VRML body per package record and returns the override rows
    (one per reference) for the resolver's --overrides-json input."""
    output_dir.mkdir(parents=True, exist_ok=True)
    override_rows = []
    generated = []
    for record in records:
        validate_record(record)
        # absolute path: kicad-cli resolves relative model paths against the
        # BOARD file, not against this tool's working directory
        model_path = (output_dir / package_model_filename(record)).resolve()
        provenance = f"{record['source']} ({record['evidence']})"
        model_path.write_text(
            box_vrml(record["body_l_mm"], record["body_w_mm"], record["body_h_mm"], provenance),
            encoding="utf-8",
        )
        generated.append(str(model_path))
        for reference in record["references"]:
            override_rows.append(
                {
                    "reference": reference,
                    "model_path": str(model_path),
                    "model_kind": "dimension_accurate_placeholder_not_true_package_shape",
                    "status": "manual_dimension_accurate_placeholder",
                    "evidence": (
                        f"Generated box at {record['package']} datasheet dimensions "
                        f"({record['body_l_mm']:g} x {record['body_w_mm']:g} x {record['body_h_mm']:g} mm), {provenance}. "
                        "Not the true package shape."
                    ),
                }
            )
    return {"models_generated": generated, "override_rows": override_rows}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate parametric package bodies from verified dimension records.")
    parser.add_argument("--records-json", required=True, type=Path, help="list of package records (see module docstring)")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--output-overrides-json", required=True, type=Path, help="override rows for component_model_resolver --overrides-json (merge with existing overrides upstream)")
    args = parser.parse_args(argv)

    records = json.loads(args.records_json.read_text(encoding="utf-8"))
    result = generate_packages(records, args.output_dir)
    args.output_overrides_json.write_text(
        json.dumps({"components": result["override_rows"]}, indent=2, sort_keys=True), encoding="utf-8"
    )
    print(json.dumps({"models_generated": result["models_generated"], "override_row_count": len(result["override_rows"])}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
