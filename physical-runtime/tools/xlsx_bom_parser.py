#!/usr/bin/env python3
"""Parse a generic Excel (.xlsx) bill-of-materials export.

Deliberately generic: column meaning is discovered from whichever row
contains a "Reference" header cell, not from hardcoded column letters, so
this works for any BOM export using this common row-per-line-item plus
wrapped-reference-continuation-rows shape (the same NXP-style shape
tools/build_frdm_reference_package.py's plain-text BOM parser already
handles, but this variant carries richer columns -- Description, MFG,
MFG_PN, VALUE -- that the plain-text export drops).
"""

from __future__ import annotations

import argparse
import json
import re
import zipfile
from pathlib import Path
from typing import Any
from xml.etree import ElementTree as ET

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}

# An .xlsx is a zip of XML, so a hostile or corrupt workbook can attack the
# parser in two ways that cost nothing to close: a decompression bomb (a small
# entry that inflates to gigabytes) and XML entity expansion. External entities
# are already refused by ElementTree, but internal expansion is not, and
# spreadsheet XML written by any real tool never declares a DOCTYPE -- so
# rejecting one outright is a safe, complete defence rather than a heuristic.
MAXIMUM_ENTRY_BYTES = 64 * 1024 * 1024
DOCTYPE_PATTERN = re.compile(rb"<!DOCTYPE", re.IGNORECASE)


def read_archive_entry(archive: zipfile.ZipFile, name: str) -> bytes:
    """Reads one archive entry with a declared-size ceiling, then verifies the
    bytes actually produced, so a lying zip header cannot exhaust memory."""
    info = archive.getinfo(name)
    if info.file_size > MAXIMUM_ENTRY_BYTES:
        raise ValueError(f"{name} declares {info.file_size} bytes, above the {MAXIMUM_ENTRY_BYTES} byte ceiling")
    with archive.open(name) as handle:
        data = handle.read(MAXIMUM_ENTRY_BYTES + 1)
    if len(data) > MAXIMUM_ENTRY_BYTES:
        raise ValueError(f"{name} inflates beyond the {MAXIMUM_ENTRY_BYTES} byte ceiling")
    return data


def parse_spreadsheet_xml(data: bytes) -> ET.Element:
    if DOCTYPE_PATTERN.search(data):
        raise ValueError("spreadsheet XML declares a DOCTYPE; refusing to parse")
    return ET.fromstring(data)


def column_letters(cell_ref: str) -> str:
    return "".join(ch for ch in cell_ref if ch.isalpha())


def load_shared_strings(archive: zipfile.ZipFile) -> list[str]:
    try:
        data = read_archive_entry(archive, "xl/sharedStrings.xml")
    except KeyError:
        return []
    root = parse_spreadsheet_xml(data)
    strings = []
    for si in root.findall("m:si", NS):
        text = "".join(node.text or "" for node in si.findall(".//m:t", NS))
        strings.append(text)
    return strings


def load_rows(archive: zipfile.ZipFile, shared_strings: list[str], sheet_path: str = "xl/worksheets/sheet1.xml") -> list[dict[str, str]]:
    root = parse_spreadsheet_xml(read_archive_entry(archive, sheet_path))
    rows = []
    for row in root.findall(".//m:sheetData/m:row", NS):
        cells: dict[str, str] = {}
        for cell in row.findall("m:c", NS):
            cell_ref = cell.get("r", "")
            column = column_letters(cell_ref)
            value_node = cell.find("m:v", NS)
            if value_node is None:
                continue
            if cell.get("t") == "s":
                text = shared_strings[int(value_node.text)]
            else:
                text = value_node.text or ""
            cells[column] = text
        if cells:
            rows.append(cells)
    return rows


def find_header_row(rows: list[dict[str, str]]) -> tuple[int, dict[str, str]] | None:
    for index, row in enumerate(rows):
        if "Reference" in row.values():
            return index, {column: name for column, name in row.items()}
    return None


def parse_bom_records(path: Path, sheet_path: str = "xl/worksheets/sheet1.xml") -> list[dict[str, Any]]:
    with zipfile.ZipFile(path) as archive:
        shared_strings = load_shared_strings(archive)
        rows = load_rows(archive, shared_strings, sheet_path)

    header = find_header_row(rows)
    if header is None:
        raise ValueError(f"no header row containing 'Reference' found in {path}")
    header_index, column_names = header

    records: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    item_column = next((column for column, name in column_names.items() if name == "Item"), None)
    reference_column = next((column for column, name in column_names.items() if name == "Reference"), None)
    if reference_column is None:
        raise ValueError("BOM header has no 'Reference' column")

    for row in rows[header_index + 1 :]:
        is_new_item = item_column is not None and item_column in row
        if is_new_item:
            current = {name: row.get(column, "") for column, name in column_names.items()}
            current["Reference"] = row.get(reference_column, "")
            records.append(current)
        elif current is not None and reference_column in row:
            current["Reference"] = current["Reference"] + row[reference_column]

    return records


def expand_by_reference_designator(records: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    by_reference: dict[str, dict[str, Any]] = {}
    for record in records:
        refs = [ref.strip() for ref in record.get("Reference", "").split(",") if ref.strip()]
        for ref in refs:
            by_reference[ref] = record
    return by_reference


CHIP_PACKAGE_PATTERN = re.compile(r"(?<!\d)(01005|0201|0402|0603|0805|1206|1210|1806|1812|2010|2512)(?!\d)")
SOT23_PATTERN = re.compile(r"\bSOT[- ]?23(-([3568]))?\b", re.IGNORECASE)
SOD523_PATTERN = re.compile(r"\bSOD[- ]?523\b", re.IGNORECASE)


def extract_chip_package_code(description: str) -> str | None:
    """Extracts a standard imperial chip-passive package code (e.g. 0603)
    from a free-text BOM description, if present. Allows a preceding
    letter with no boundary (e.g. "RC0402") but rejects a match embedded
    in a longer digit run, so it never guesses when no recognized code
    appears."""
    match = CHIP_PACKAGE_PATTERN.search(description)
    return match.group(1) if match else None


def extract_sot23_variant(description: str) -> str | None:
    """Extracts an explicit SOT-23 family pin-count suffix (5/6/8) if
    present in the description; returns "" (bare SOT-23, no suffix) when
    "SOT-23"/"SOT23" appears with no explicit pin count. Never guesses a
    pin count that isn't in the text."""
    match = SOT23_PATTERN.search(description)
    if not match:
        return None
    return match.group(2) or ""


def has_sod523_package(description: str) -> bool:
    return SOD523_PATTERN.search(description) is not None


SOT363_PATTERN = re.compile(r"\bSOT[- ]?363\b", re.IGNORECASE)


def has_sot363_package(description: str) -> bool:
    return SOT363_PATTERN.search(description) is not None


SOT323_PATTERN = re.compile(r"\bSOT[- ]?323\b", re.IGNORECASE)


def has_sot323_package(description: str) -> bool:
    return SOT323_PATTERN.search(description) is not None


LED_MARKER_PATTERN = re.compile(r"\bLED\b", re.IGNORECASE)


def is_led_description(description: str) -> bool:
    return LED_MARKER_PATTERN.search(description) is not None


BARE_HEADER_PATTERN = re.compile(r"\bHDR\s+(\d+)X(\d+)\s+TH\b", re.IGNORECASE)
HEADER_PITCH_MIL_PATTERN = re.compile(r"(\d+)MIL")

MIL_TO_MM = {"50": 1.27, "100": 2.54}


def extract_bare_header_spec(description: str) -> tuple[int, int, float] | None:
    """Extracts (rows, cols, pitch_mm) for a plain THT pin header
    ("HDR NxM TH ..."), never for a shrouded/SMD connector ("CON ... SKT
    SHRD" etc, which this pattern does not match). Returns None rather
    than guessing a pitch when a multi-pin header's pitch isn't stated in
    a recognized MIL value; a single-pin header (1x1) has no meaningful
    pitch, so it defaults to the near-universal 100mil/2.54mm post size."""
    match = BARE_HEADER_PATTERN.search(description)
    if not match:
        return None
    rows, cols = int(match.group(1)), int(match.group(2))
    pitch_match = HEADER_PITCH_MIL_PATTERN.search(description)
    if pitch_match:
        pitch_mm = MIL_TO_MM.get(pitch_match.group(1))
        if pitch_mm is None:
            return None
    elif rows * cols == 1:
        pitch_mm = 2.54
    else:
        return None
    return (rows, cols, pitch_mm)


THT_SOCKET_PATTERN = re.compile(r"\bCON\s+(\d+)X(\d+)\s+SKT\s+(?:SHRD\s+)?(RA\s+)?TH\s+(\d+)MIL\b", re.IGNORECASE)


def extract_tht_socket_spec(description: str) -> tuple[int, int, float, bool] | None:
    """Extracts (rows, cols, pitch_mm, right_angle) for a through-hole
    female socket connector ("CON NxM SKT [SHRD] [RA] TH <pitch>MIL ...").
    Only matches when the pitch is a recognized MIL value. The SHRD
    (shrouded) marker is accepted but not represented in the returned
    spec -- callers matching against unshrouded generic socket models
    must record that approximation explicitly."""
    match = THT_SOCKET_PATTERN.search(description)
    if not match:
        return None
    rows, cols = int(match.group(1)), int(match.group(2))
    pitch_mm = MIL_TO_MM.get(match.group(4))
    if pitch_mm is None:
        return None
    return (rows, cols, pitch_mm, match.group(3) is not None)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Parse an xlsx BOM export into per-reference-designator records.")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output-json", type=Path)
    args = parser.parse_args(argv)

    records = parse_bom_records(args.input)
    by_reference = expand_by_reference_designator(records)

    summary = {
        "line_item_count": len(records),
        "reference_designator_count": len(by_reference),
        "columns_found": sorted({name for record in records for name in record}),
        "descriptions_with_recognized_chip_package": sum(
            1 for record in by_reference.values() if extract_chip_package_code(record.get("Description", ""))
        ),
    }

    if args.output_json:
        args.output_json.write_text(json.dumps({"summary": summary, "by_reference": by_reference}, indent=2, sort_keys=True), encoding="utf-8")

    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
