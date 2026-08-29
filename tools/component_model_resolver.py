#!/usr/bin/env python3
"""Resolve real NXP FRDM-MCXN236 components to real, installed KiCad 3D
models -- or honestly report why none is available.

This is deliberately generic: resolution keys off (reference-designator
prefix, BOM description package code), not a per-board or per-reference
lookup table. It only claims a match when a real file exists on disk at
the computed path; it never invents a model.

The imperial<->metric chip-passive size table below is a standard,
board-independent EIA/IPC convention (e.g. 0603 imperial == 1608 metric),
not something specific to this board.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from xlsx_bom_parser import (
    expand_by_reference_designator,
    extract_chip_package_code,
    extract_sot23_variant,
    has_sod523_package,
    parse_bom_records,
)

# Standard EIA/IPC imperial->metric chip-passive size code table.
IMPERIAL_TO_METRIC = {
    "01005": "0402",
    "0201": "0603",
    "0402": "1005",
    "0603": "1608",
    "0805": "2012",
    "1206": "3216",
    "1210": "3225",
    "1806": "4516",
    "1812": "4532",
    "2010": "5025",
    "2512": "6432",
}

# Reference-designator prefix -> (KiCad 3D library folder, file prefix letter).
CHIP_PASSIVE_LIBRARIES = {
    "R": ("Resistor_SMD.3dshapes", "R"),
    "C": ("Capacitor_SMD.3dshapes", "C"),
    "L": ("Inductor_SMD.3dshapes", "L"),
}


def reference_prefix(reference: str) -> str:
    prefix = ""
    for char in reference:
        if char.isalpha():
            prefix += char
        else:
            break
    return prefix


def resolve_chip_passive_model(reference: str, package_code: str, library_root: Path) -> tuple[str | None, str]:
    prefix = reference_prefix(reference)
    library_entry = CHIP_PASSIVE_LIBRARIES.get(prefix)
    if library_entry is None:
        return None, f"prefix '{prefix}' is not a recognized chip-passive prefix (R/C/L)"
    folder, letter = library_entry
    metric = IMPERIAL_TO_METRIC.get(package_code)
    if metric is None:
        return None, f"package code '{package_code}' has no known imperial->metric mapping"
    filename = f"{letter}_{package_code}_{metric}Metric.step"
    path = library_root / folder / filename
    if not path.is_file():
        return None, f"expected model file does not exist on disk: {path}"
    return str(path), "matched real installed KiCad official 3D model file"


def resolve_sot23_model(variant: str, library_root: Path) -> tuple[str | None, str]:
    filename = f"SOT-23-{variant}.step" if variant else "SOT-23.step"
    path = library_root / "Package_TO_SOT_SMD.3dshapes" / filename
    if not path.is_file():
        return None, f"expected model file does not exist on disk: {path}"
    return str(path), "matched real installed KiCad official 3D model file"


def resolve_sod523_model(library_root: Path) -> tuple[str | None, str]:
    path = library_root / "Diode_SMD.3dshapes" / "D_SOD-523.step"
    if not path.is_file():
        return None, f"expected model file does not exist on disk: {path}"
    return str(path), "matched real installed KiCad official 3D model file"


def resolve_all(
    components: list[dict[str, Any]],
    bom_by_reference: dict[str, dict[str, Any]],
    library_root: Path,
) -> list[dict[str, Any]]:
    results = []
    for component in components:
        reference = component["reference_designator"]
        bom_record = bom_by_reference.get(reference)
        description = bom_record.get("Description", "") if bom_record else ""
        package_code = extract_chip_package_code(description) if description else None
        sot23_variant = extract_sot23_variant(description) if description else None
        is_sod523 = has_sod523_package(description) if description else False

        model_path = None
        status = "no_recognized_package_code"
        evidence = f"BOM description: {description!r}" if description else "reference not found in BOM"

        if package_code is not None:
            model_path, reason = resolve_chip_passive_model(reference, package_code, library_root)
            status = "matched_official_kicad_model" if model_path else "package_recognized_but_unresolved"
            evidence = reason
        elif sot23_variant is not None:
            model_path, reason = resolve_sot23_model(sot23_variant, library_root)
            status = "matched_official_kicad_model" if model_path else "package_recognized_but_unresolved"
            evidence = reason
            package_code = f"SOT-23-{sot23_variant}" if sot23_variant else "SOT-23"
        elif is_sod523:
            model_path, reason = resolve_sod523_model(library_root)
            status = "matched_official_kicad_model" if model_path else "package_recognized_but_unresolved"
            evidence = reason
            package_code = "SOD-523"

        results.append(
            {
                "reference": reference,
                "x_mm": component["centroid"]["x"],
                "y_mm": component["centroid"]["y"],
                "rotation_degrees": component["rotation_degrees"],
                "side": component["side"],
                "bom_part_number": bom_record.get("MFG_PN") if bom_record else None,
                "bom_manufacturer": bom_record.get("MFG") if bom_record else None,
                "bom_description": description,
                "package_code": package_code,
                "model_path": model_path,
                "status": status,
                "evidence": evidence,
            }
        )
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Resolve real components to real installed KiCad 3D models.")
    parser.add_argument("--reference-package", required=True, type=Path)
    parser.add_argument("--bom-xlsx", required=True, type=Path)
    parser.add_argument("--kicad-3d-library-root", required=True, type=Path)
    parser.add_argument("--output-json", required=True, type=Path)
    args = parser.parse_args(argv)

    bundle = json.loads(args.reference_package.read_text(encoding="utf-8"))
    bom_records = parse_bom_records(args.bom_xlsx)
    bom_by_reference = expand_by_reference_designator(bom_records)

    results = resolve_all(bundle["components"], bom_by_reference, args.kicad_3d_library_root)

    counts: dict[str, int] = {}
    for row in results:
        counts[row["status"]] = counts.get(row["status"], 0) + 1

    args.output_json.write_text(json.dumps({"summary": counts, "components": results}, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps({"total": len(results), "counts_by_status": counts}, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
