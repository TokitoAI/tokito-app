#!/usr/bin/env python3
"""Resolve a reference designator to a physical image ROI.

This module is deliberately generic: it takes a board name/component list as
data (built by tools/build_frdm_reference_package.py) and never branches on a
specific board.

It consumes, but does not implement, board registration. `ResolvedBoardTransform`
below mirrors -- in field semantics, not byte layout -- android/ar-runtime's
`ResolvedTransform` (PR #60, merged to main:
android/ar-runtime/src/main/kotlin/ai/tokito/physical/ar/FrameGraph.kt): a
monotonic `revision`, a row-major 3x3 projective matrix, an additive pixel
`uncertainty_px` accumulated across a composed frame path, and a monotonic-clock
`expires_at_nanos`. PR #60 only implements that runtime's presentation/graph-
resolution consumer; it explicitly does not compute a real transform from a
camera image itself ("the AR renderer never reconstructs those transforms
from screen dimensions" -- docs/AR_RUNTIME.md). The real registration
computation remains issue #13, owned separately. Until it lands, only
test/fixture ResolvedBoardTransform values exist here; this module must not
silently substitute a fabricated transform for a missing one.

Pipeline implemented here:

    reference designator
        -> assembly-context lookup (real BOM-cross-checked placement data)
        -> board-plane centroid + rotated footprint bounding box (mm)
        -> resolved board-frame transform (supplied by #13's future output,
           already composed/resolved the way FrameGraph.resolve() would)
        -> image-pixel ROI

Every number in the output is computed from the inputs; nothing is
hardcoded per board or per component.
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

Matrix3 = tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]]


@dataclass(frozen=True)
class ResolvedBoardTransform:
    """Mirrors android/ar-runtime's `ResolvedTransform` (PR #60): the output
    of that runtime's `FrameGraph.resolve(source, destination, revision,
    nowNanos)` for a path starting at the `board` frame. Path composition
    (which intermediate frames -- sensor/upright_image/crop/preview/design --
    were traversed) is that runtime's job, not duplicated here; this module
    only consumes an already-resolved matrix plus its accumulated
    uncertainty and expiry. `destination_frame` records only which frame the
    matrix resolves into, for logging/debugging."""

    matrix: Matrix3
    revision: int
    uncertainty_px: float
    expires_at_nanos: int
    destination_frame: str


def identity_test_transform(scale_px_per_mm: float, revision: int = 1, uncertainty_px: float = 0.0, expires_at_nanos: int = 2**62, destination_frame: str = "preview") -> ResolvedBoardTransform:
    """Builds a test-fixture ResolvedBoardTransform using a simple
    axis-aligned scale (no rotation/perspective). This exists only to
    exercise the math in tests and CLI demos -- it is not a substitute for
    #13's real board registration or #60's real FrameGraph.resolve()
    output."""
    return ResolvedBoardTransform(
        matrix=((scale_px_per_mm, 0.0, 0.0), (0.0, scale_px_per_mm, 0.0), (0.0, 0.0, 1.0)),
        revision=revision,
        uncertainty_px=uncertainty_px,
        expires_at_nanos=expires_at_nanos,
        destination_frame=destination_frame,
    )


def load_components(reference_package_path: Path) -> dict[str, dict[str, Any]]:
    bundle = json.loads(reference_package_path.read_text(encoding="utf-8"))
    return {component["reference_designator"]: component for component in bundle["components"]}


def rotate_point(x: float, y: float, degrees: float) -> tuple[float, float]:
    radians = math.radians(degrees)
    cos_a = math.cos(radians)
    sin_a = math.sin(radians)
    return (x * cos_a - y * sin_a, x * sin_a + y * cos_a)


def footprint_corners_board_mm(component: dict[str, Any]) -> list[tuple[float, float]]:
    bbox = component["footprint_bbox_mm"]
    half_w = bbox["width"] / 2
    half_h = bbox["height"] / 2
    local_corners = [(-half_w, -half_h), (half_w, -half_h), (half_w, half_h), (-half_w, half_h)]
    centroid = component["centroid"]
    rotation = component["rotation_degrees"]
    corners = []
    for local_x, local_y in local_corners:
        rotated_x, rotated_y = rotate_point(local_x, local_y, rotation)
        corners.append((centroid["x"] + rotated_x, centroid["y"] + rotated_y))
    return corners


def apply_matrix3(x_mm: float, y_mm: float, matrix: Matrix3) -> tuple[float, float]:
    """Applies a row-major 3x3 projective matrix, matching android/ar-
    runtime's `Matrix3.map` exactly (m[6]*x + m[7]*y + m[8] as the
    homogeneous w term)."""
    row0, row1, row2 = matrix
    u = row0[0] * x_mm + row0[1] * y_mm + row0[2]
    v = row1[0] * x_mm + row1[1] * y_mm + row1[2]
    w = row2[0] * x_mm + row2[1] * y_mm + row2[2]
    if w == 0:
        raise ValueError("degenerate matrix: w=0 for this point")
    return (u / w, v / w)


FALLBACK_MIN_BBOX_MM = 1.0
"""Used only when a component's real footprint_bbox_mm is {0,0} (see
docs/FRDM_MCXN236_REFERENCE_PACKAGE.md's zero-extent case). This is an
explicit, documented fallback margin, not a claim about the component's
real physical size -- callers can see footprint_extent_source in the
result to know when this applied."""


def locate_component(
    reference: str,
    components: dict[str, dict[str, Any]],
    transform: ResolvedBoardTransform | None,
    now_nanos: int,
    max_uncertainty_px: float,
) -> dict[str, Any]:
    if transform is None:
        return {"reference": reference, "located": False, "reason": "registration_not_available"}
    if now_nanos > transform.expires_at_nanos:
        return {
            "reference": reference,
            "located": False,
            "reason": "registration_expired",
            "now_nanos": now_nanos,
            "expires_at_nanos": transform.expires_at_nanos,
        }
    if transform.uncertainty_px > max_uncertainty_px:
        return {
            "reference": reference,
            "located": False,
            "reason": "registration_uncertainty_too_high",
            "uncertainty_px": transform.uncertainty_px,
            "max_uncertainty_px": max_uncertainty_px,
        }
    component = components.get(reference)
    if component is None:
        return {"reference": reference, "located": False, "reason": "reference_not_found_in_assembly_context"}

    bbox = component["footprint_bbox_mm"]
    footprint_extent_source = "measured"
    working_component = component
    if bbox["width"] == 0.0 or bbox["height"] == 0.0:
        footprint_extent_source = "fallback_default_margin"
        working_component = {
            **component,
            "footprint_bbox_mm": {"width": FALLBACK_MIN_BBOX_MM, "height": FALLBACK_MIN_BBOX_MM},
        }

    board_corners = footprint_corners_board_mm(working_component)
    pixel_corners = [apply_matrix3(x, y, transform.matrix) for x, y in board_corners]
    xs = [x for x, _ in pixel_corners]
    ys = [y for _, y in pixel_corners]
    roi = {
        "x": min(xs),
        "y": min(ys),
        "width": max(xs) - min(xs),
        "height": max(ys) - min(ys),
    }

    return {
        "reference": reference,
        "located": True,
        "board_position_mm": component["centroid"],
        "rotation_degrees": component["rotation_degrees"],
        "side": component["side"],
        "footprint_extent_source": footprint_extent_source,
        "roi": roi,
        "coordinate_frame": transform.destination_frame,
        "transform_revision": transform.revision,
        "uncertainty_px": transform.uncertainty_px,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Locate a reference designator's image ROI from a reference package and a resolved board transform.")
    parser.add_argument("--reference-package", required=True, type=Path)
    parser.add_argument("--reference-designator", required=True)
    parser.add_argument("--max-uncertainty-px", required=True, type=float)
    parser.add_argument("--now-nanos", required=True, type=int)
    parser.add_argument("--fixture-scale-px-per-mm", type=float, default=None, help="Build a test-fixture identity transform at this scale instead of a real one (no #13 implementation exists yet).")
    args = parser.parse_args(argv)

    components = load_components(args.reference_package)
    transform = identity_test_transform(args.fixture_scale_px_per_mm) if args.fixture_scale_px_per_mm is not None else None

    result = locate_component(args.reference_designator, components, transform, args.now_nanos, args.max_uncertainty_px)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result.get("located") else 1


if __name__ == "__main__":
    raise SystemExit(main())
