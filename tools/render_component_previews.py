#!/usr/bin/env python3
"""Render per-component isolation previews for human visual QA.

Highlightability is proven numerically by
`verify_component_highlightability.py`; this produces the human-inspectable
counterpart. For each requested reference it builds a board containing that
component's model and no other, then renders it -- so the image shows
exactly which body belongs to that designator and where it sits, with no
assumption about how board millimetres map to image pixels.

Nothing about placement changes: the same authored placement and registered
model transform are used, only the set of attached models is filtered.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path
from typing import Any

from attach_3d_models_text import attach_models


def render_previews(
    kicad_pcb: Path,
    resolution: dict[str, Any],
    references: list[str],
    output_dir: Path,
    kicad_cli: str,
    width: int = 1280,
    height: int = 720,
) -> list[dict[str, Any]]:
    output_dir.mkdir(parents=True, exist_ok=True)
    rows = resolution["components"]
    known = {row["reference"] for row in rows if row.get("model_path")}
    results = []
    for reference in references:
        if reference not in known:
            results.append({"reference": reference, "status": "skipped_no_model"})
            continue
        isolated = [row for row in rows if row["reference"] == reference]
        board = output_dir / f"isolate_{reference}.kicad_pcb"
        image = output_dir / f"component_preview_{reference}.png"
        attach_models(kicad_pcb, isolated, board)
        completed = subprocess.run(
            [
                kicad_cli, "pcb", "render",
                "-w", str(width), "-h", str(height),
                "--side", "top", "--quality", "basic",
                "-o", str(image), str(board),
            ],
            capture_output=True,
            text=True,
        )
        board.unlink(missing_ok=True)
        board.with_suffix(".kicad_prl").unlink(missing_ok=True)
        results.append(
            {
                "reference": reference,
                "status": "rendered" if image.is_file() else "render_failed",
                "image": str(image) if image.is_file() else None,
                "stderr": None if image.is_file() else completed.stderr[-400:],
            }
        )
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Render isolation previews for individual components.")
    parser.add_argument("--kicad-pcb", required=True, type=Path, help="the UNPOPULATED imported board")
    parser.add_argument("--resolution-json", required=True, type=Path)
    parser.add_argument("--references", required=True, help="comma-separated reference designators")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--kicad-cli", default="kicad-cli")
    args = parser.parse_args(argv)

    results = render_previews(
        args.kicad_pcb,
        json.loads(args.resolution_json.read_text(encoding="utf-8")),
        [r.strip() for r in args.references.split(",") if r.strip()],
        args.output_dir,
        args.kicad_cli,
    )
    print(json.dumps(results, indent=2))
    return 0 if all(r["status"] != "render_failed" for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
