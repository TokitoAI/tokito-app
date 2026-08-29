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

from canonical_footprint import load_board_footprint_geometry, register_model
from xlsx_bom_parser import (
    expand_by_reference_designator,
    extract_bare_header_spec,
    extract_chip_package_code,
    extract_sot23_variant,
    extract_tht_socket_spec,
    has_sod523_package,
    has_sot323_package,
    has_sot363_package,
    is_led_description,
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

ELONGATION_THRESHOLD = 1.15
"""A footprint's local pad bbox must be this much longer in one axis than
the other before an axis is asserted; near-square parts get no model
rotation."""


def model_rotate_z_deg(bbox: dict[str, float], canonical_axis: str) -> float:
    """Rotation (degrees, about Z, in the footprint's local frame) needed to
    align a family model with this footprint's real pad-array axis.

    KiCad model families have a canonical pad-array axis, read directly
    from KiCad's own authored footprints (each attaches its model with
    rotate 0 0 0): PinSocket/PinHeader pin rows run along local Y (pin1
    (0,0), pin3 (0,2.54), ...), while chip passives/LEDs/SOIC/SOT/SOD/
    crystal pads run along local X (e.g. R_0603 pads at (+-0.825, 0)).
    Allegro-imported footprints don't follow either convention
    consistently, so the footprint's real local axis is measured from its
    own pad bbox and compared against the family's canonical axis."""
    if canonical_axis not in ("X", "Y"):
        raise ValueError(f"canonical_axis must be 'X' or 'Y', got {canonical_axis!r}")
    width, height = bbox["width"], bbox["height"]
    if width >= height * ELONGATION_THRESHOLD:
        local_axis = "X"
    elif height >= width * ELONGATION_THRESHOLD:
        local_axis = "Y"
    else:
        return 0.0
    return 0.0 if local_axis == canonical_axis else 90.0


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


def resolve_led_chip_model(package_code: str, library_root: Path) -> tuple[str | None, str]:
    metric = IMPERIAL_TO_METRIC.get(package_code)
    if metric is None:
        return None, f"package code '{package_code}' has no known imperial->metric mapping"
    path = library_root / "LED_SMD.3dshapes" / f"LED_{package_code}_{metric}Metric.step"
    if not path.is_file():
        return None, f"expected model file does not exist on disk: {path}"
    return str(path), "matched real installed KiCad official 3D model file"


def resolve_sot363_model(library_root: Path) -> tuple[str | None, str]:
    path = library_root / "Package_TO_SOT_SMD.3dshapes" / "SOT-363_SC-70-6.step"
    if not path.is_file():
        return None, f"expected model file does not exist on disk: {path}"
    return str(path), "matched real installed KiCad official 3D model file"


def resolve_sot323_model(library_root: Path) -> tuple[str | None, str]:
    path = library_root / "Package_TO_SOT_SMD.3dshapes" / "SOT-323_SC-70.step"
    if not path.is_file():
        return None, f"expected model file does not exist on disk: {path}"
    return str(path), "matched real installed KiCad official 3D model file"


def resolve_tht_socket_model(rows: int, cols: int, pitch_mm: float, right_angle: bool, library_root: Path) -> tuple[str | None, str]:
    """Only for a THT female socket connector (THT_SOCKET_PATTERN). This is
    a generic family match on real pin-count and pitch from the BOM text
    against KiCad's unshrouded PinSocket models -- a shrouded ("SHRD")
    housing is approximated by the unshrouded generic body, and callers
    must record that distinction."""
    orientation = "Horizontal" if right_angle else "Vertical"
    pitch_folder = f"Connector_PinSocket_{pitch_mm:.2f}mm.3dshapes"
    filename = f"PinSocket_{rows}x{cols:02d}_P{pitch_mm:.2f}mm_{orientation}.step"
    path = library_root / pitch_folder / filename
    if not path.is_file():
        return None, f"expected model file does not exist on disk: {path}"
    return str(path), "matched real installed KiCad official 3D model file (generic pin-count/pitch socket family, unshrouded approximation of the BOM part)"


def resolve_bare_header_model(rows: int, cols: int, pitch_mm: float, library_root: Path) -> tuple[str | None, str]:
    """Only for a plain THT pin header (BARE_HEADER_PATTERN already
    excludes shrouded/SMD connectors). This is a generic family match on
    real pin-count and pitch from the BOM text, not an exact vendor part
    -- callers should record that distinction."""
    pitch_folder = f"Connector_PinHeader_{pitch_mm:.2f}mm.3dshapes"
    filename = f"PinHeader_{rows}x{cols:02d}_P{pitch_mm:.2f}mm_Vertical.step"
    path = library_root / pitch_folder / filename
    if not path.is_file():
        return None, f"expected model file does not exist on disk: {path}"
    return str(path), "matched real installed KiCad official 3D model file (generic pin-count/pitch family, not an exact vendor part)"


def registered_model_transform(
    reference: str,
    model_path: str,
    footprint_bbox_mm: dict[str, float],
    canonical_axis: str,
    footprint_geometry: dict[str, dict[str, Any]] | None,
) -> dict[str, Any]:
    """Exact per-footprint model transform via canonical-footprint pad
    registration, falling back to the family bbox-axis heuristic (flagged as
    such) only when registration evidence is unavailable. The heuristic can
    only say 0 or 90 -- it cannot see the sign of the pad field's direction
    from pin 1, which is what actually varies between Allegro-imported
    footprints -- so a heuristic fallback is reported, never silently used."""
    geometry = (footprint_geometry or {}).get(reference)
    if geometry and geometry["pads"]:
        registration = register_model(geometry["pads"], geometry["body_overhang"], model_path)
        if registration is not None:
            return {
                "model_rotate_z_deg": float(registration["rotate_z_deg"]),
                "model_offset_x_mm": round(registration["offset_x_mm"], 4),
                "model_offset_y_mm": round(registration["offset_y_mm"], 4),
                "model_registration": registration,
            }
        fallback_reason = "canonical footprint missing or pad registration failed"
    elif geometry is not None:
        fallback_reason = "footprint has no numbered pads"
    else:
        fallback_reason = "no footprint geometry provided (run with --kicad-pcb)"
    return {
        "model_rotate_z_deg": model_rotate_z_deg(footprint_bbox_mm, canonical_axis),
        "model_offset_x_mm": 0.0,
        "model_offset_y_mm": 0.0,
        "model_registration": {"method": "bbox_axis_heuristic_fallback", "reason": fallback_reason},
    }


def resolve_all(
    components: list[dict[str, Any]],
    bom_by_reference: dict[str, dict[str, Any]],
    library_root: Path,
    footprint_geometry: dict[str, dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    results = []
    for component in components:
        reference = component["reference_designator"]
        bom_record = bom_by_reference.get(reference)
        description = bom_record.get("Description", "") if bom_record else ""
        package_code = extract_chip_package_code(description) if description else None
        is_led = is_led_description(description) if description else False
        sot23_variant = extract_sot23_variant(description) if description else None
        is_sod523 = has_sod523_package(description) if description else False
        is_sot363 = has_sot363_package(description) if description else False
        is_sot323 = has_sot323_package(description) if description else False
        bare_header_spec = extract_bare_header_spec(description) if description else None
        tht_socket_spec = extract_tht_socket_spec(description) if description else None

        model_path = None
        status = "no_recognized_package_code"
        evidence = f"BOM description: {description!r}" if description else "reference not found in BOM"
        model_kind = None
        canonical_axis = "X"

        if package_code is not None and is_led:
            model_path, reason = resolve_led_chip_model(package_code, library_root)
            status = "matched_official_kicad_model" if model_path else "package_recognized_but_unresolved"
            evidence = reason
            model_kind = "exact_family" if model_path else None
        elif package_code is not None:
            model_path, reason = resolve_chip_passive_model(reference, package_code, library_root)
            status = "matched_official_kicad_model" if model_path else "package_recognized_but_unresolved"
            evidence = reason
            model_kind = "exact_family" if model_path else None
        elif sot23_variant is not None:
            model_path, reason = resolve_sot23_model(sot23_variant, library_root)
            status = "matched_official_kicad_model" if model_path else "package_recognized_but_unresolved"
            evidence = reason
            package_code = f"SOT-23-{sot23_variant}" if sot23_variant else "SOT-23"
            model_kind = "exact_family" if model_path else None
        elif is_sod523:
            model_path, reason = resolve_sod523_model(library_root)
            status = "matched_official_kicad_model" if model_path else "package_recognized_but_unresolved"
            evidence = reason
            package_code = "SOD-523"
            model_kind = "exact_family" if model_path else None
        elif is_sot363:
            model_path, reason = resolve_sot363_model(library_root)
            status = "matched_official_kicad_model" if model_path else "package_recognized_but_unresolved"
            evidence = reason
            package_code = "SOT-363"
            model_kind = "exact_family" if model_path else None
        elif is_sot323:
            model_path, reason = resolve_sot323_model(library_root)
            status = "matched_official_kicad_model" if model_path else "package_recognized_but_unresolved"
            evidence = reason
            package_code = "SOT-323"
            model_kind = "exact_family" if model_path else None
        elif bare_header_spec is not None:
            rows, cols, pitch_mm = bare_header_spec
            model_path, reason = resolve_bare_header_model(rows, cols, pitch_mm, library_root)
            status = "matched_official_kicad_model" if model_path else "package_recognized_but_unresolved"
            evidence = reason
            package_code = f"HDR-{rows}x{cols}-{pitch_mm}mm"
            model_kind = "generic_family_approximate" if model_path else None
            canonical_axis = "Y"
        elif tht_socket_spec is not None:
            rows, cols, pitch_mm, right_angle = tht_socket_spec
            model_path, reason = resolve_tht_socket_model(rows, cols, pitch_mm, right_angle, library_root)
            status = "matched_official_kicad_model" if model_path else "package_recognized_but_unresolved"
            evidence = reason
            package_code = f"SKT-{rows}x{cols}-{pitch_mm}mm" + ("-RA" if right_angle else "")
            model_kind = "generic_family_approximate" if model_path else None
            canonical_axis = "Y"

        if model_path:
            transform = registered_model_transform(
                reference, model_path, component["footprint_bbox_mm"], canonical_axis, footprint_geometry
            )
        else:
            transform = {
                "model_rotate_z_deg": 0.0,
                "model_offset_x_mm": 0.0,
                "model_offset_y_mm": 0.0,
                "model_registration": None,
            }

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
                "model_kind": model_kind,
                **transform,
                "status": status,
                "evidence": evidence,
            }
        )
    return results


def apply_manual_overrides(
    rows: list[dict[str, Any]],
    override_rows: list[dict[str, Any]],
    footprint_geometry: dict[str, dict[str, Any]] | None,
) -> list[dict[str, Any]]:
    """Merges manually-evidenced model overrides into resolver rows.

    An override pointing at an installed KiCad library model is registered
    against its canonical footprint exactly like a resolver match. A
    generated placeholder box (authored centred at its own origin, unlike
    library models which are authored over their canonical footprint) is
    anchored to the footprint's real pad-field centre instead -- Allegro
    footprints keep pin 1 at the origin, so a centred box left at (0,0)
    would sit displaced by half the part's span."""
    rows_by_reference = {row["reference"]: row for row in rows}
    for override in override_rows:
        reference = override["reference"]
        base = rows_by_reference.get(reference)
        if base is None:
            raise ValueError(f"override for unknown reference {reference!r}")
        merged = {**base, **override}
        geometry = (footprint_geometry or {}).get(reference)
        registration = None
        if geometry and geometry["pads"]:
            registration = register_model(geometry["pads"], geometry["body_overhang"], override["model_path"])
        if registration is not None:
            merged["model_rotate_z_deg"] = float(registration["rotate_z_deg"])
            merged["model_offset_x_mm"] = round(registration["offset_x_mm"], 4)
            merged["model_offset_y_mm"] = round(registration["offset_y_mm"], 4)
            merged["model_registration"] = registration
        elif geometry and geometry["pad_field_center"] is not None:
            merged["model_rotate_z_deg"] = 0.0
            merged["model_offset_x_mm"] = round(geometry["pad_field_center"][0], 4)
            merged["model_offset_y_mm"] = round(geometry["pad_field_center"][1], 4)
            merged["model_registration"] = {
                "method": "centered_model_at_pad_field_center",
                "reason": "model authored centred at its origin; anchored to the footprint's real pad-field bbox centre",
            }
        else:
            merged.setdefault("model_rotate_z_deg", 0.0)
            merged.setdefault("model_offset_x_mm", 0.0)
            merged.setdefault("model_offset_y_mm", 0.0)
            merged["model_registration"] = {
                "method": "no_transform_evidence",
                "reason": "no footprint geometry available for this reference",
            }
        rows_by_reference[reference] = merged
    return [rows_by_reference[row["reference"]] for row in rows]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Resolve real components to real installed KiCad 3D models.")
    parser.add_argument("--reference-package", required=True, type=Path)
    parser.add_argument("--bom-xlsx", required=True, type=Path)
    parser.add_argument("--kicad-3d-library-root", required=True, type=Path)
    parser.add_argument("--output-json", required=True, type=Path)
    parser.add_argument(
        "--kicad-pcb",
        type=Path,
        help="imported .kicad_pcb for exact per-footprint model registration (pad positions + body outlines); without it, model orientation degrades to the flagged bbox-axis heuristic",
    )
    parser.add_argument(
        "--overrides-json",
        type=Path,
        help="manually-evidenced model overrides ({'components': [...]}) merged after resolution",
    )
    args = parser.parse_args(argv)

    bundle = json.loads(args.reference_package.read_text(encoding="utf-8"))
    bom_records = parse_bom_records(args.bom_xlsx)
    bom_by_reference = expand_by_reference_designator(bom_records)
    footprint_geometry = load_board_footprint_geometry(args.kicad_pcb) if args.kicad_pcb else None

    results = resolve_all(bundle["components"], bom_by_reference, args.kicad_3d_library_root, footprint_geometry)

    if args.overrides_json:
        overrides = json.loads(args.overrides_json.read_text(encoding="utf-8"))
        results = apply_manual_overrides(results, overrides["components"], footprint_geometry)

    counts: dict[str, int] = {}
    for row in results:
        counts[row["status"]] = counts.get(row["status"], 0) + 1

    args.output_json.write_text(json.dumps({"summary": counts, "components": results}, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps({"total": len(results), "counts_by_status": counts}, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
