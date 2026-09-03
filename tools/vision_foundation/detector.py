"""Deterministic board detector for synthetic/reference grayscale frames."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .types import CameraFrame, Point, Polygon


@dataclass(frozen=True)
class BoardDetection:
    detected: bool
    confidence: float
    polygon: Polygon | None
    source_frame_id: str
    messages: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return {
            "detected": self.detected,
            "confidence": self.confidence,
            "source_frame_id": self.source_frame_id,
            "polygon": self.polygon.to_dict() if self.polygon else None,
            "messages": list(self.messages),
        }


@dataclass(frozen=True)
class ThresholdBoardDetector:
    threshold: int
    minimum_area_ratio: float
    minimum_confidence: float

    def detect(self, frame: CameraFrame) -> BoardDetection:
        messages = frame.validate()
        if messages:
            return BoardDetection(False, 0.0, None, frame.frame_id, tuple(messages))

        foreground: list[tuple[int, int]] = []
        for y, row in enumerate(frame.pixels):
            for x, value in enumerate(row):
                if value >= self.threshold:
                    foreground.append((x, y))

        total = frame.width * frame.height
        area_ratio = len(foreground) / total if total else 0.0
        if area_ratio < self.minimum_area_ratio:
            return BoardDetection(
                False,
                round(area_ratio, 6),
                None,
                frame.frame_id,
                ("board foreground area below threshold",),
            )

        min_x = min(x for x, _ in foreground)
        max_x = max(x for x, _ in foreground) + 1
        min_y = min(y for _, y in foreground)
        max_y = max(y for _, y in foreground) + 1
        confidence = min(1.0, area_ratio / max(self.minimum_area_ratio, 1e-9))
        polygon = Polygon(
            (
                Point(float(min_x), float(min_y)),
                Point(float(max_x), float(min_y)),
                Point(float(max_x), float(max_y)),
                Point(float(min_x), float(max_y)),
            ),
            frame.coordinate_frame,
        )
        if confidence < self.minimum_confidence:
            return BoardDetection(
                False,
                round(confidence, 6),
                polygon,
                frame.frame_id,
                ("board confidence below operating threshold",),
            )
        return BoardDetection(True, round(confidence, 6), polygon, frame.frame_id)
