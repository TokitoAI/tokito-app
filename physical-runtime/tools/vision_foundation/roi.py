"""ROI conversion helpers for future bounded visual inspection."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .geometry import Transform2D
from .types import Point, Polygon


@dataclass(frozen=True)
class RegionObservation:
    region_id: str
    kind: str
    geometry: Polygon
    confidence: float
    source: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "region_id": self.region_id,
            "kind": self.kind,
            "geometry": self.geometry.to_dict(),
            "confidence": self.confidence,
            "source": self.source,
        }


def whole_board_region(board_width: float, board_height: float, board_frame: str, confidence: float) -> RegionObservation:
    return RegionObservation(
        region_id="whole-board",
        kind="board_visible_region",
        geometry=Polygon(
            (
                Point(0.0, 0.0),
                Point(board_width, 0.0),
                Point(board_width, board_height),
                Point(0.0, board_height),
            ),
            board_frame,
        ),
        confidence=confidence,
        source="deterministic_board_registration",
    )


def project_region(region: RegionObservation, transform: Transform2D) -> RegionObservation:
    return RegionObservation(
        region_id=region.region_id,
        kind=region.kind,
        geometry=transform.apply_polygon(region.geometry),
        confidence=region.confidence,
        source=region.source,
    )
