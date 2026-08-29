#!/usr/bin/env python3
"""Plan open-source 3D/AR asset generation from local board design files."""

from __future__ import annotations

import argparse
import json
import shutil
import zipfile
from pathlib import Path
from typing import Any, Sequence


KICAD_BOARD_EXTENSIONS = {".kicad_pcb"}
GERBER_EXTENSIONS = {".gbr", ".ger", ".gtl", ".gbl", ".gts", ".gbs", ".gto", ".gbo"}
DRILL_EXTENSIONS = {".drl", ".xln"}
EXISTING_MODEL_EXTENSIONS = {".step", ".stp", ".wrl", ".stl", ".obj", ".gltf", ".glb"}
VENDOR_CAD_EXTENSIONS = {".brd", ".dsn", ".opj"}
ARCHIVE_EXTENSIONS = {".zip"}


def executable_path(*names: str) -> str | None:
    for name in names:
        found = shutil.which(name)
        if found:
            return found
    return None


def classify_path(path: Path, groups: dict[str, list[str]], display_path: str) -> None:
    suffix = path.suffix.lower()
    if suffix in KICAD_BOARD_EXTENSIONS:
        groups["kicad_boards"].append(display_path)
    elif suffix in GERBER_EXTENSIONS:
        groups["gerbers"].append(display_path)
    elif suffix in DRILL_EXTENSIONS:
        groups["drills"].append(display_path)
    elif suffix in EXISTING_MODEL_EXTENSIONS:
        groups["existing_models"].append(display_path)
    elif suffix in VENDOR_CAD_EXTENSIONS:
        groups["vendor_cad"].append(display_path)


def discover_files(source: Path) -> dict[str, list[str]]:
    groups = {
        "kicad_boards": [],
        "gerbers": [],
        "drills": [],
        "existing_models": [],
        "vendor_cad": [],
        "archives": [],
        "archive_errors": [],
    }
    for path in sorted(source.rglob("*")):
        if not path.is_file():
            continue
        relative = str(path.relative_to(source))
        classify_path(path, groups, relative)
        if path.suffix.lower() in ARCHIVE_EXTENSIONS:
            groups["archives"].append(relative)
            discover_archive_files(source, path, groups)
    return groups


def discover_archive_files(source: Path, archive: Path, groups: dict[str, list[str]]) -> None:
    archive_name = str(archive.relative_to(source))
    try:
        with zipfile.ZipFile(archive) as package:
            for entry in sorted(package.infolist(), key=lambda info: info.filename):
                if entry.is_dir():
                    continue
                display_path = f"{archive_name}!/{entry.filename}"
                classify_path(Path(entry.filename), groups, display_path)
    except zipfile.BadZipFile:
        groups["archive_errors"].append(f"{archive_name}: bad zip file")


def has_archive_entry(paths: Sequence[str]) -> bool:
    return any("!/" in path or "!\\" in path for path in paths)


def plan_ar_asset_generation(source: Path, output_dir: Path) -> dict[str, Any]:
    files = discover_files(source)
    tools = {
        "kicad_cli": executable_path("kicad-cli"),
        "gerber2blend": executable_path("gerber2blend"),
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
        if has_archive_entry(files["existing_models"]):
            plan["status"] = "blocked_archive_requires_extraction"
            plan["messages"].append("Existing 3D model files were found inside a ZIP archive; extract them into ignored local data before AR scale, origin, orientation, and license review.")
            return plan
        plan["status"] = "ready_existing_model_review"
        plan["messages"].append("Existing 3D model files are present; review scale, origin, orientation, and license before AR use.")
        return plan

    if files["kicad_boards"]:
        if has_archive_entry(files["kicad_boards"]):
            plan["status"] = "blocked_archive_requires_extraction"
            plan["messages"].append("A KiCad board file was found inside a ZIP archive; extract it into ignored local data before running kicad-cli export.")
            return plan
        board = Path(files["kicad_boards"][0])
        glb_output = output_dir / f"{board.stem}.glb"
        step_output = output_dir / f"{board.stem}.step"
        vrml_output = output_dir / f"{board.stem}.wrl"
        plan["commands"] = [
            ["kicad-cli", "pcb", "export", "glb", "--force", "--output", str(glb_output), str(source / board)],
            ["kicad-cli", "pcb", "export", "step", "--force", "--output", str(step_output), str(source / board)],
            ["kicad-cli", "pcb", "export", "vrml", "--force", "--output", str(vrml_output), str(source / board)],
        ]
        if tools["kicad_cli"]:
            plan["status"] = "ready_kicad_cli_export"
            plan["messages"].append("KiCad board file and kicad-cli are available for GLB, STEP, and VRML export.")
        else:
            plan["status"] = "blocked_missing_kicad_cli"
            plan["messages"].append("KiCad board file is present, but kicad-cli is not installed or not on PATH.")
        return plan

    if files["gerbers"] and files["drills"]:
        if has_archive_entry(files["gerbers"]) or has_archive_entry(files["drills"]):
            plan["status"] = "blocked_archive_requires_extraction"
            plan["messages"].append("Gerber and drill files were found inside a ZIP archive; extract them into ignored local data before running gerber2blend or other mesh conversion.")
            return plan
        if tools["gerber2blend"]:
            plan["status"] = "ready_gerber2blend_board_mesh"
            plan["commands"] = [
                ["gerber2blend", "--help"],
            ]
            plan["messages"].append("Gerber and drill files can support board substrate mesh generation with gerber2blend; populated components still need reviewed placement data and 3D package models.")
        else:
            plan["status"] = "blocked_missing_gerber2blend"
            plan["messages"].append("Gerber and drill files are present, but gerber2blend is not installed or not on PATH.")
        return plan

    if files["vendor_cad"]:
        allegro_boards = [Path(path) for path in files["vendor_cad"] if Path(path).suffix.lower() == ".brd"]
        if allegro_boards:
            board = allegro_boards[0]
            if has_archive_entry([str(board)]):
                plan["status"] = "blocked_archive_requires_extraction"
                plan["messages"].append("An importable Allegro board was found inside a ZIP archive; extract the archive into ignored local data before running KiCad import/export.")
                return plan
            kicad_board = output_dir / f"{board.stem}.kicad_pcb"
            plan["commands"] = [
                ["kicad-cli", "pcb", "import", "--output", str(kicad_board), str(source / board)],
                ["kicad-cli", "pcb", "export", "glb", "--force", "--output", str(output_dir / f"{board.stem}.glb"), str(kicad_board)],
                ["kicad-cli", "pcb", "export", "step", "--force", "--output", str(output_dir / f"{board.stem}.step"), str(kicad_board)],
                ["kicad-cli", "pcb", "export", "vrml", "--force", "--output", str(output_dir / f"{board.stem}.wrl"), str(kicad_board)],
            ]
            if tools["kicad_cli"]:
                plan["status"] = "ready_kicad_allegro_import"
                plan["messages"].append("Allegro board file is present and kicad-cli is available; inspect the installed KiCad version and import report before accepting AR geometry.")
            else:
                plan["status"] = "blocked_missing_kicad_10_for_allegro_import"
                plan["messages"].append("Allegro board file is present, but KiCad 10 kicad-cli is not installed or not on PATH for open-source import/export.")
            return plan
        plan["status"] = "blocked_vendor_cad_requires_export"
        plan["messages"].append("Vendor CAD files are present, but this local toolchain needs exported Gerber/drill, STEP/WRL, KiCad board files, or an importable Allegro board before AR asset generation.")
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
