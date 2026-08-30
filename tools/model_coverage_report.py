#!/usr/bin/env python3
"""3D-coverage classification for every reference on the board.

Answers, from source data only, the question "which of the 391 references
actually need a 3D body, and what quality of body do they have":

- classification comes from the vendor BOM's own text ("NO PART TO ORDER"
  entries are bare copper / drill features -- test-point pads, solder-blob
  jumpers, non-plated mounting holes -- and cannot have a body because no
  part is mounted), never from assumptions;
- model quality is one of exact_package_geometry /
  verified_package_approximation / dimension_accurate_placeholder /
  missing, mapped from the resolver's evidence-backed statuses;
- the coverage figure counts only real physical components, so "100%"
  can never be reached by quietly ignoring unresolved parts: every
  missing physical component is listed with its BOM identity.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

NON_POPULATED_MARKER = "NO PART TO ORDER"

NON_POPULATED_SUBTYPES = (
    ("TEST POINT", "test_point_pad"),
    ("SOLDER BLOB JUMPER", "solder_jumper_pads"),
    ("MOUNTING HOLE", "mounting_hole"),
)

MODEL_QUALITY_BY_KIND = {
    "exact_family": "exact_package_geometry",
    "exact_datasheet_verified": "exact_package_geometry",
    "generic_family_approximate": "verified_package_approximation",
    "verified_package_approximation": "verified_package_approximation",
    "dimension_accurate_placeholder_not_true_package_shape": "dimension_accurate_placeholder",
}


def classify_reference(row: dict[str, Any]) -> dict[str, Any]:
    description = (row.get("bom_description") or "").upper()
    if NON_POPULATED_MARKER in description:
        subtype = "other_non_populated"
        for marker, name in NON_POPULATED_SUBTYPES:
            if marker in description:
                subtype = name
                break
        return {"category": "non_populated_reference", "subtype": subtype, "needs_3d_body": False}
    if not description:
        return {"category": "not_in_bom", "subtype": None, "needs_3d_body": False}
    return {"category": "physical_component", "subtype": None, "needs_3d_body": True}


def model_quality(row: dict[str, Any]) -> str:
    if not row.get("model_path"):
        return "missing"
    kind = row.get("model_kind")
    if kind in MODEL_QUALITY_BY_KIND:
        return MODEL_QUALITY_BY_KIND[kind]
    return "unclassified_model_kind"


def build_coverage_report(resolution: dict[str, Any]) -> dict[str, Any]:
    rows = []
    for component in resolution["components"]:
        classification = classify_reference(component)
        quality = model_quality(component) if classification["needs_3d_body"] else None
        registration = component.get("model_registration") or {}
        rows.append(
            {
                "reference": component["reference"],
                "category": classification["category"],
                "subtype": classification["subtype"],
                "needs_3d_body": classification["needs_3d_body"],
                "bom_description": component.get("bom_description") or None,
                "bom_manufacturer": component.get("bom_manufacturer"),
                "bom_part_number": component.get("bom_part_number"),
                "model_file": Path(component["model_path"]).name if component.get("model_path") else None,
                "model_quality": quality,
                "registration_method": registration.get("method"),
                "resolver_status": component.get("status"),
                "resolver_evidence": component.get("evidence"),
            }
        )

    physical = [r for r in rows if r["needs_3d_body"]]
    modeled = [r for r in physical if r["model_quality"] != "missing"]
    missing = [r for r in physical if r["model_quality"] == "missing"]
    quality_counts: dict[str, int] = {}
    for row in physical:
        quality_counts[row["model_quality"]] = quality_counts.get(row["model_quality"], 0) + 1
    category_counts: dict[str, int] = {}
    for row in rows:
        key = row["category"] + (f":{row['subtype']}" if row["subtype"] else "")
        category_counts[key] = category_counts.get(key, 0) + 1

    return {
        "total_references": len(rows),
        "categories": category_counts,
        "physical_components": len(physical),
        "physical_with_model": len(modeled),
        "physical_missing_model": len(missing),
        "coverage_pct_of_physical": round(100.0 * len(modeled) / len(physical), 2) if physical else None,
        "quality_counts": quality_counts,
        "missing_models": [
            {k: r[k] for k in ("reference", "bom_manufacturer", "bom_part_number", "bom_description", "resolver_status")}
            for r in sorted(missing, key=lambda r: r["reference"])
        ],
        "references": sorted(rows, key=lambda r: r["reference"]),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Classify every board reference and report 3D model coverage/quality.")
    parser.add_argument("--resolution-json", required=True, type=Path)
    parser.add_argument("--output-json", required=True, type=Path)
    args = parser.parse_args(argv)

    report = build_coverage_report(json.loads(args.resolution_json.read_text(encoding="utf-8")))
    args.output_json.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
    summary = {k: report[k] for k in ("total_references", "categories", "physical_components", "physical_with_model", "physical_missing_model", "coverage_pct_of_physical", "quality_counts")}
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
