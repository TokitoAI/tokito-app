#!/usr/bin/env python3
"""Generate a dimension-accurate rectangular box as a KiCad-compatible VRML
3D model.

This exists for components whose exact vendor 3D model is unavailable but
whose real package dimensions are published by the manufacturer (for
example NXP's SOT2172-1 page publishes VFBGA184 as 9 x 9 x 0.86 mm). The
output is deliberately a plain box: dimensionally honest, visually obvious
as a placeholder, and never presented as the true molded package shape.
Callers must supply the dimensions and a provenance note; this tool
hardcodes nothing about any specific component or board.

KiCad's VRML model convention is 1 VRML unit = 0.1 inch = 2.54 mm, with
z-up and z=0 at the board surface, so the box is translated up by half its
height to sit on the board.
"""

from __future__ import annotations

import argparse
from pathlib import Path

MM_PER_VRML_UNIT = 2.54


def box_vrml(width_mm: float, length_mm: float, height_mm: float, provenance: str) -> str:
    if min(width_mm, length_mm, height_mm) <= 0:
        raise ValueError("box dimensions must all be positive")
    w = width_mm / MM_PER_VRML_UNIT
    l = length_mm / MM_PER_VRML_UNIT
    h = height_mm / MM_PER_VRML_UNIT
    return (
        "#VRML V2.0 utf8\n"
        f"# dimension-accurate placeholder box, not a true package shape\n"
        f"# dimensions: {width_mm} x {length_mm} x {height_mm} mm\n"
        f"# provenance: {provenance}\n"
        "Transform {\n"
        f"  translation 0 0 {h / 2:.6f}\n"
        "  children [\n"
        "    Shape {\n"
        "      appearance Appearance {\n"
        "        material Material { diffuseColor 0.15 0.15 0.15 shininess 0.1 }\n"
        "      }\n"
        f"      geometry Box {{ size {w:.6f} {l:.6f} {h:.6f} }}\n"
        "    }\n"
        "  ]\n"
        "}\n"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate a dimension-accurate VRML placeholder box.")
    parser.add_argument("--width-mm", required=True, type=float)
    parser.add_argument("--length-mm", required=True, type=float)
    parser.add_argument("--height-mm", required=True, type=float)
    parser.add_argument("--provenance", required=True, help="Where the real dimensions come from (e.g. a manufacturer package page URL).")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(box_vrml(args.width_mm, args.length_mm, args.height_mm, args.provenance), encoding="utf-8")
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
