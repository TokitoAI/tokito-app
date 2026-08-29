"""Command-line interface for reference source package manifests."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Sequence

from .manifest import fetch_allowed_sources, load_manifest, validate_manifest


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate or fetch reference source package artifacts.")
    subcommands = parser.add_subparsers(dest="command", required=True)

    validate_parser = subcommands.add_parser("validate", help="Validate a manifest without network access.")
    validate_parser.add_argument("--manifest", required=True, type=Path)

    fetch_parser = subcommands.add_parser("fetch", help="Fetch public artifacts explicitly marked fetch_allowed.")
    fetch_parser.add_argument("--manifest", required=True, type=Path)
    fetch_parser.add_argument("--output", type=Path)

    args = parser.parse_args(argv)
    manifest = load_manifest(args.manifest)
    if args.command == "validate":
        result = validate_manifest(manifest)
        print(json.dumps(result.to_dict(), indent=2, sort_keys=True))
        return 0 if result.ok else 1

    result = validate_manifest(manifest)
    if not result.ok:
        print(json.dumps(result.to_dict(), indent=2, sort_keys=True))
        return 1
    output = args.output or Path(manifest["retrieval_policy"]["default_output_directory"])
    try:
        fetched = fetch_allowed_sources(manifest, output)
    except OSError as exc:
        print(json.dumps({"error": {"code": "fetch_failed", "message": str(exc)}}, indent=2, sort_keys=True))
        return 1
    except ValueError as exc:
        print(json.dumps({"error": {"code": "invalid_argument", "message": str(exc)}}, indent=2, sort_keys=True))
        return 1
    print(json.dumps({"fetched": [item.to_dict() for item in fetched]}, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
