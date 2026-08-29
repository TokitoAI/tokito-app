#!/usr/bin/env python3
"""Plan open-source 3D/AR asset generation from local board design files."""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path
from typing import Any, Sequence


KICAD_BOARD_EXTENSIONS = {".kicad_pcb"}
GERBER_EXTENSIONS = {".gbr", ".ger", ".gtl", ".gbl", ".gts", ".gbs", ".gto", ".gbo"}
DRILL_EXTENSIONS = {".drl", ".xln"}
EXISTING_MODEL_EXTENSIONS = {".step", ".stp", ".wrl", ".stl", ".obj", ".gltf", ".glb"}
VENDOR_CAD_EXTENSIONS = {".brd", ".dsn", ".opj"}


def executable_path(*names: str) -> str | None:
    for name in names:
        found = shutil.which(name)
        if found:
            return found
    return None


def discover_files(source: Path) -> dict[str, list[str]]:
    groups = {
        "kicad_boards": [],
        "gerbers": [],
        "drills": [],
        "existing_models": [],
        "vendor_cad": [],
    }
    for path in sorted(source.rglob("*")):
        if not path.is_file():
            continue
        suffix = path.suffix.lower()
        relative = str(path.relative_to(source))
        if suffix in KICAD_BOARD_EXTENSIONS:
            groups["kicad_boards"].append(relative)
        elif suffix in GERBER_EXTENSIONS:
            groups["gerbers"].append(relative)
        elif suffix in DRILL_EXTENSIONS:
            groups["drills"].append(relative)
        elif suffix in EXISTING_MODEL_EXTENSIONS:
            groups["existing_models"].append(relative)
        elif suffix in VENDOR_CAD_EXTENSIONS:
            groups["vendor_cad"].append(relative)
    return groups


def plan_ar_asset_generation(source: Path, output_dir: Path) -> dict[str, Any]:
    files = discover_files(source)
    tools = {
        "kicad_cli": executable_path("kicad-cli"),
        "blender": executable_path("blender"),
        "freecad": executable_path("FreeCADCmd", "freecadcmd", "freecadcmd.exe"),
    }
    plan: dict[str, Any] = {
        "source": str(source),
        "output_dir": str(output_dir),
        "open_source_tools": tools,
        "files": files,
        "commands": [],
        "status": "blocked_no_board_geometry",
        "messages": [],
    }

    if files["existing_models"]:
        plan["status"] = "ready_existing_model_review"
        plan["messages"].append("Existing 3D model files are present; review scale, origin, orientation, and license before AR use.")
        return plan

    if files["kicad_boards"]:
        board = Path(files["kicad_boards"][0])
        step_output = output_dir / f"{board.stem}.step"
        vrml_output = output_dir / f"{board.stem}.wrl"
        plan["commands"] = [
            ["kicad-cli", "pcb", "export", "step", "--force", "--output", str(step_output), str(source / board)],
            ["kicad-cli", "pcb", "export", "vrml", "--force", "--output", str(vrml_output), str(source / board)],
        ]
        if tools["kicad_cli"]:
            plan["status"] = "ready_kicad_cli_export"
            plan["messages"].append("KiCad board file and kicad-cli are available for STEP/VRML export.")
        else:
            plan["status"] = "blocked_missing_kicad_cli"
            plan["messages"].append("KiCad board file is present, but kicad-cli is not installed or not on PATH.")
        return plan

    if files["gerbers"] and files["drills"]:
        plan["status"] = "ready_board_mesh_conversion_planning"
        plan["messages"].append("Gerber and drill files can support board-outline/copper-layer mesh planning, but not full component 3D placement without reviewed assembly data.")
        return plan

    if files["vendor_cad"]:
        plan["status"] = "blocked_vendor_cad_requires_export"
        plan["messages"].append("Vendor CAD files are present, but this local toolchain needs exported Gerber/drill, STEP/WRL, or KiCad board files before AR asset generation.")
        return plan

    plan["messages"].append("No supported board geometry or 3D model files were found.")
    return plan


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Plan AR 3D asset generation from board design files.")
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args(argv)

    plan = plan_ar_asset_generation(args.source, args.output_dir)
    print(json.dumps(plan, indent=2, sort_keys=True))
    return 0 if not plan["status"].startswith("blocked_no") else 1


if __name__ == "__main__":
    raise SystemExit(main())
