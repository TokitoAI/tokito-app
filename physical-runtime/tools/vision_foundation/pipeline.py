"""End-to-end reference board discovery pipeline."""

from __future__ import annotations

from typing import Any

from .detector import ThresholdBoardDetector
from .registration import register_board
from .roi import project_region, whole_board_region
from .types import BoardProfile, CameraFrame, VisionMessage


REQUIRED_DETECTOR_FIELDS = ("threshold", "minimum_area_ratio", "minimum_confidence")


def run_pipeline(payload: dict[str, Any]) -> dict[str, Any]:
    """Run deterministic board discovery over a structured frame payload."""
    messages: list[VisionMessage] = []
    if payload.get("schema_version") != "1.0":
        messages.append(VisionMessage("unsupported_capability", "unsupported schema_version", "$.schema_version"))
    try:
        frame = CameraFrame.from_dict(payload["frame"])
        board = BoardProfile.from_dict(payload["board_profile"])
    except KeyError as exc:
        messages.append(VisionMessage("invalid_argument", f"missing required field {exc}", "$"))
        return _error(messages)
    except (TypeError, ValueError) as exc:
        messages.append(VisionMessage("invalid_argument", str(exc), "$"))
        return _error(messages)
    if messages:
        return _error(messages)
    frame_errors = frame.validate()
    if frame_errors:
        return _error(
            [
                VisionMessage("invalid_geometry", message, "$.frame")
                for message in frame_errors
            ]
        )
    board_errors = board.validate()
    if board_errors:
        return _error(
            [
                VisionMessage(
                    "unsupported_capability" if "supported" in message else "invalid_geometry",
                    message,
                    "$.board_profile",
                )
                for message in board_errors
            ]
        )

    detector_config = payload.get("detector")
    if not isinstance(detector_config, dict):
        messages.append(VisionMessage("invalid_argument", "detector config is required", "$.detector"))
        return _error(messages)
    for field in REQUIRED_DETECTOR_FIELDS:
        if field not in detector_config:
            messages.append(VisionMessage("invalid_argument", f"missing detector field {field!r}", "$.detector"))
    if messages:
        return _error(messages)
    try:
        detector = ThresholdBoardDetector(
            threshold=int(detector_config["threshold"]),
            minimum_area_ratio=float(detector_config["minimum_area_ratio"]),
            minimum_confidence=float(detector_config["minimum_confidence"]),
        )
    except (TypeError, ValueError):
        return _error([VisionMessage("invalid_argument", "detector fields must be numeric", "$.detector")])
    if detector.threshold < 0 or detector.threshold > 255:
        return _error([VisionMessage("invalid_argument", "threshold must be in [0, 255]", "$.detector.threshold")])
    if detector.minimum_area_ratio <= 0 or detector.minimum_area_ratio > 1:
        return _error([VisionMessage("invalid_argument", "minimum_area_ratio must be in (0, 1]", "$.detector.minimum_area_ratio")])
    if detector.minimum_confidence <= 0 or detector.minimum_confidence > 1:
        return _error([VisionMessage("invalid_argument", "minimum_confidence must be in (0, 1]", "$.detector.minimum_confidence")])
    detection = detector.detect(frame)
    registration = register_board(detection, board)
    if not detection.detected or not registration.registered or registration.transform is None:
        return {
            "schema_version": "1.0",
            "outcome": "abstained",
            "frame_id": frame.frame_id,
            "detection": detection.to_dict(),
            "registration": registration.to_dict(),
            "regions": [],
            "messages": [
                VisionMessage("capture_quality_insufficient", "board could not be registered", "$.frame").to_dict()
            ],
        }

    board_region = whole_board_region(board.width, board.height, board.board_frame_id, registration.confidence)
    image_region = project_region(board_region, registration.transform.inverse())
    return {
        "schema_version": "1.0",
        "outcome": "observation",
        "frame_id": frame.frame_id,
        "detection": detection.to_dict(),
        "registration": registration.to_dict(),
        "regions": [
            board_region.to_dict(),
            image_region.to_dict(),
        ],
        "messages": [],
    }


def _error(messages: list[VisionMessage]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "outcome": "error",
        "detection": None,
        "registration": None,
        "regions": [],
        "messages": [message.to_dict() for message in messages],
    }
