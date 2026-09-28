"""Structured vision result types with explicit coordinate frames."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal


Outcome = Literal["observation", "abstained", "error"]


@dataclass(frozen=True)
class Point:
    x: float
    y: float

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "Point":
        return cls(float(value["x"]), float(value["y"]))

    def to_dict(self) -> dict[str, float]:
        return {"x": round(self.x, 12), "y": round(self.y, 12)}


@dataclass(frozen=True)
class Polygon:
    points: tuple[Point, ...]
    coordinate_frame: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "coordinate_frame": self.coordinate_frame,
            "points": [point.to_dict() for point in self.points],
        }


@dataclass(frozen=True)
class CameraFrame:
    frame_id: str
    coordinate_frame: str
    width: int
    height: int
    captured_monotonic_ms: int
    pixels: tuple[tuple[int, ...], ...]

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "CameraFrame":
        pixels = tuple(tuple(int(pixel) for pixel in row) for row in value["pixels"])
        return cls(
            frame_id=str(value["frame_id"]),
            coordinate_frame=str(value["coordinate_frame"]),
            width=int(value["width"]),
            height=int(value["height"]),
            captured_monotonic_ms=int(value["captured_monotonic_ms"]),
            pixels=pixels,
        )

    def validate(self) -> list[str]:
        messages: list[str] = []
        if self.width <= 0 or self.height <= 0:
            messages.append("frame dimensions must be positive")
        if len(self.pixels) != self.height:
            messages.append("pixel row count must equal frame height")
        for row in self.pixels:
            if len(row) != self.width:
                messages.append("pixel column count must equal frame width")
                break
            if any(pixel < 0 or pixel > 255 for pixel in row):
                messages.append("pixels must be 8-bit grayscale values")
                break
        return messages


@dataclass(frozen=True)
class BoardProfile:
    profile_id: str
    board_frame_id: str
    width: float
    height: float
    units: str
    coordinate_system: str

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "BoardProfile":
        return cls(
            profile_id=str(value["profile_id"]),
            board_frame_id=str(value["board_frame_id"]),
            width=float(value["width"]),
            height=float(value["height"]),
            units=str(value["units"]),
            coordinate_system=str(value["coordinate_system"]),
        )

    def validate(self) -> list[str]:
        messages: list[str] = []
        if self.width <= 0 or self.height <= 0:
            messages.append("board profile dimensions must be positive")
        if self.units != "mm":
            messages.append("only millimetre board profiles are supported")
        if "right-handed" not in self.coordinate_system:
            messages.append("board coordinate system must declare right-handed orientation")
        return messages


@dataclass(frozen=True)
class VisionMessage:
    code: str
    message: str
    path: str

    def to_dict(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message, "path": self.path}
