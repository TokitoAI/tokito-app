"""Coordinate transforms for board discovery reference tooling."""

from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass
from typing import Any

from .types import Point, Polygon


@dataclass(frozen=True)
class Transform2D:
    source_frame: str
    target_frame: str
    matrix: tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]]

    def apply(self, point: Point) -> Point:
        x = self.matrix[0][0] * point.x + self.matrix[0][1] * point.y + self.matrix[0][2]
        y = self.matrix[1][0] * point.x + self.matrix[1][1] * point.y + self.matrix[1][2]
        w = self.matrix[2][0] * point.x + self.matrix[2][1] * point.y + self.matrix[2][2]
        if abs(w) < 1e-12:
            raise ValueError("transform produced a point at infinity")
        return Point(x / w, y / w)

    def apply_polygon(self, polygon: Polygon) -> Polygon:
        if polygon.coordinate_frame != self.source_frame:
            raise ValueError(f"polygon frame {polygon.coordinate_frame!r} does not match {self.source_frame!r}")
        return Polygon(tuple(self.apply(point) for point in polygon.points), self.target_frame)

    def inverse(self) -> "Transform2D":
        return Transform2D(
            source_frame=self.target_frame,
            target_frame=self.source_frame,
            matrix=_invert_3x3(self.matrix),
        )

    def revision(self) -> str:
        payload = {
            "source_frame": self.source_frame,
            "target_frame": self.target_frame,
            "matrix": self.matrix,
        }
        encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return "sha256:" + hashlib.sha256(encoded).hexdigest()

    def to_dict(self) -> dict[str, Any]:
        return {
            "source_frame": self.source_frame,
            "target_frame": self.target_frame,
            "matrix": [[round(value, 12) for value in row] for row in self.matrix],
            "transform_revision": self.revision(),
        }


def _invert_3x3(matrix: tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]]) -> tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]]:
    a, b, c = matrix[0]
    d, e, f = matrix[1]
    g, h, i = matrix[2]
    det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    if abs(det) < 1e-12:
        raise ValueError("transform matrix is not invertible")
    inv = (
        ((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
        ((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
        ((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det),
    )
    return inv


def axis_aligned_rect_to_board(
    source_frame: str,
    board_frame: str,
    min_x: float,
    min_y: float,
    max_x: float,
    max_y: float,
    board_width: float,
    board_height: float,
) -> Transform2D:
    image_width = max_x - min_x
    image_height = max_y - min_y
    if image_width <= 0 or image_height <= 0 or board_width <= 0 or board_height <= 0:
        raise ValueError("source rectangle and board dimensions must be positive")
    return Transform2D(
        source_frame=source_frame,
        target_frame=board_frame,
        matrix=(
            (board_width / image_width, 0.0, -min_x * board_width / image_width),
            (0.0, board_height / image_height, -min_y * board_height / image_height),
            (0.0, 0.0, 1.0),
        ),
    )


def polygon_area(polygon: Polygon) -> float:
    area = 0.0
    points = polygon.points
    for index, point in enumerate(points):
        other = points[(index + 1) % len(points)]
        area += point.x * other.y - other.x * point.y
    return abs(area) / 2.0


def finite_polygon(polygon: Polygon) -> bool:
    return all(math.isfinite(point.x) and math.isfinite(point.y) for point in polygon.points)
