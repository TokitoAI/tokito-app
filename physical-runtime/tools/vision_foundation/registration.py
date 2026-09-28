"""Board registration built from explicit image and board coordinate frames."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .detector import BoardDetection
from .geometry import Transform2D, axis_aligned_rect_to_board, finite_polygon, polygon_area
from .types import BoardProfile, Polygon


@dataclass(frozen=True)
class BoardRegistration:
    registered: bool
    confidence: float
    transform: Transform2D | None
    board_polygon: Polygon | None
    uncertainty: float | None
    messages: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return {
            "registered": self.registered,
            "confidence": self.confidence,
            "transform": self.transform.to_dict() if self.transform else None,
            "board_polygon": self.board_polygon.to_dict() if self.board_polygon else None,
            "uncertainty": self.uncertainty,
            "messages": list(self.messages),
        }


def register_board(detection: BoardDetection, board: BoardProfile) -> BoardRegistration:
    messages = board.validate()
    if messages:
        return BoardRegistration(False, 0.0, None, None, None, tuple(messages))
    if not detection.detected or detection.polygon is None:
        return BoardRegistration(False, detection.confidence, None, None, None, detection.messages)
    if len(detection.polygon.points) != 4 or not finite_polygon(detection.polygon):
        return BoardRegistration(False, 0.0, None, None, None, ("invalid detection polygon",))
    if polygon_area(detection.polygon) <= 0:
        return BoardRegistration(False, 0.0, None, None, None, ("detection polygon has zero area",))

    xs = [point.x for point in detection.polygon.points]
    ys = [point.y for point in detection.polygon.points]
    transform = axis_aligned_rect_to_board(
        detection.polygon.coordinate_frame,
        board.board_frame_id,
        min(xs),
        min(ys),
        max(xs),
        max(ys),
        board.width,
        board.height,
    )
    board_polygon = transform.apply_polygon(detection.polygon)
    uncertainty = round(1.0 - detection.confidence, 6)
    return BoardRegistration(
        registered=True,
        confidence=detection.confidence,
        transform=transform,
        board_polygon=board_polygon,
        uncertainty=uncertainty,
    )
