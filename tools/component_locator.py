#!/usr/bin/env python3
"""Resolve a reference designator to a physical image ROI.

This module is deliberately generic: it takes a board name/component list as
data (built by tools/build_frdm_reference_package.py) and never branches on a
specific board. It consumes, but does not implement, board registration
(issue #13, owned separately) -- RegistrationResult below documents exactly
the input contract this module expects from that work. Until #13 lands, only
test/fixture RegistrationResult values exist; this module must not silently
substitute a fabricated transform for a missing one.

Pipeline implemented here:

    reference designator
        -> assembly-context lookup (real BOM-cross-checked placement data)
        -> board-plane centroid + rotated footprint bounding box (mm)
        -> registration transform (homography, supplied by #13)
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


@dataclass(frozen=True)
class RegistrationResult:
    """The input contract this module expects from board registration
    (issue #13). `homography` is a row-major 3x3 matrix mapping homogeneous
    board-plane millimeter coordinates (x, y, 1) to homogeneous image pixel
    coordinates (u, v, w); pixel = (u/w, v/w). This is a standard planar
    homography, not a board-specific invention -- but until #13 exists, any
    RegistrationResult used here is a test/fixture value, not a real
    camera-derived transform."""

    board: str
    revision: str
    registered: bool
    confidence: float
    transform_revision: str
    homography: tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]] | None
    image_width: int | None = None
    image_height: int | None = None


def identity_registration(board: str, revision: str, scale_px_per_mm: float, confidence: float = 1.0) -> RegistrationResult:
    """Builds a test-fixture RegistrationResult using a simple axis-aligned
    scale (no rotation/perspective). This exists only to exercise the math
    in tests and CLI demos -- it is not a substitute for #13's real
    board-to-camera registration."""
    return RegistrationResult(
        board=board,
        revision=revision,
        registered=True,
        confidence=confidence,
        transform_revision="test-fixture-identity-scale",
        homography=((scale_px_per_mm, 0.0, 0.0), (0.0, scale_px_per_mm, 0.0), (0.0, 0.0, 1.0)),
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


def apply_homography(x_mm: float, y_mm: float, homography: Sequence[Sequence[float]]) -> tuple[float, float]:
    row0, row1, row2 = homography
    u = row0[0] * x_mm + row0[1] * y_mm + row0[2]
    v = row1[0] * x_mm + row1[1] * y_mm + row1[2]
    w = row2[0] * x_mm + row2[1] * y_mm + row2[2]
    if w == 0:
        raise ValueError("degenerate homography: w=0 for this point")
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
    registration: RegistrationResult,
    min_registration_confidence: float,
) -> dict[str, Any]:
    if not registration.registered or registration.homography is None:
        return {"reference": reference, "located": False, "reason": "registration_not_available"}
    if registration.confidence < min_registration_confidence:
        return {
            "reference": reference,
            "located": False,
            "reason": "registration_low_confidence",
            "registration_confidence": registration.confidence,
            "required_confidence": min_registration_confidence,
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
    pixel_corners = [apply_homography(x, y, registration.homography) for x, y in board_corners]
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
        "confidence": registration.confidence,
        "transform_revision": registration.transform_revision,
        "board": registration.board,
        "revision": registration.revision,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Locate a reference designator's image ROI from a reference package and a registration result.")
    parser.add_argument("--reference-package", required=True, type=Path)
    parser.add_argument("--reference-designator", required=True)
    parser.add_argument("--min-registration-confidence", required=True, type=float)
    parser.add_argument("--fixture-scale-px-per-mm", type=float, default=None, help="Build a test-fixture identity registration at this scale instead of a real one (no #13 implementation exists yet).")
    args = parser.parse_args(argv)

    components = load_components(args.reference_package)
    bundle = json.loads(args.reference_package.read_text(encoding="utf-8"))
    if args.fixture_scale_px_per_mm is not None:
        registration = identity_registration(bundle["board"]["board_id"], bundle["board"]["revision"], args.fixture_scale_px_per_mm)
    else:
        registration = RegistrationResult(
            board=bundle["board"]["board_id"],
            revision=bundle["board"]["revision"],
            registered=False,
            confidence=0.0,
            transform_revision="none",
            homography=None,
        )

    result = locate_component(args.reference_designator, components, registration, args.min_registration_confidence)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result.get("located") else 1


if __name__ == "__main__":
    raise SystemExit(main())
