"""CLI for the offline board discovery reference pipeline."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from .pipeline import run_pipeline


def _read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def run(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run Tokito reference board discovery.")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)

    result = run_pipeline(_read_json(args.input))
    encoded = json.dumps(result, indent=2, sort_keys=True)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + "\n", encoding="utf-8")
    else:
        print(encoded)
    return 0 if result["outcome"] == "observation" else 1


if __name__ == "__main__":
    raise SystemExit(run(sys.argv[1:]))
