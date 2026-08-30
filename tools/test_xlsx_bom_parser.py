#!/usr/bin/env python3
"""Hardening tests for the xlsx BOM parser.

An .xlsx is a zip of XML, so it is attacker-shaped input even when it
arrives from a vendor design package. These tests pin the two cheap
defences: refuse a DOCTYPE (spreadsheet XML from real tools never has one,
and internal entity expansion is otherwise unbounded) and refuse an entry
that inflates past the size ceiling.
"""

from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

from xlsx_bom_parser import MAXIMUM_ENTRY_BYTES, load_shared_strings, read_archive_entry

SHEET_XML = (
    b'<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
    b"<sheetData/></worksheet>"
)


class SpreadsheetHardeningTest(unittest.TestCase):
    def _workbook(self, directory: str, shared_strings: bytes) -> Path:
        path = Path(directory) / "book.xlsx"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("xl/sharedStrings.xml", shared_strings)
            archive.writestr("xl/worksheets/sheet1.xml", SHEET_XML)
        return path

    def test_doctype_is_refused(self) -> None:
        payload = (
            b'<?xml version="1.0"?><!DOCTYPE lolz [ <!ENTITY a "aaaaaaaaaa">'
            b' <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;"> ]>'
            b'<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
            b"<si><t>&b;</t></si></sst>"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = self._workbook(directory, payload)
            with zipfile.ZipFile(path) as archive:
                with self.assertRaises(ValueError) as caught:
                    load_shared_strings(archive)
        self.assertIn("DOCTYPE", str(caught.exception))

    def test_oversized_entry_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bomb.xlsx"
            with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
                # highly compressible, standing in for a decompression bomb
                archive.writestr("xl/sharedStrings.xml", b"A" * (MAXIMUM_ENTRY_BYTES + 1024))
            with zipfile.ZipFile(path) as archive:
                with self.assertRaises(ValueError) as caught:
                    read_archive_entry(archive, "xl/sharedStrings.xml")
        self.assertIn("ceiling", str(caught.exception))

    def test_ordinary_workbook_still_parses(self) -> None:
        payload = (
            b'<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
            b"<si><t>RES 10K 0603</t></si></sst>"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = self._workbook(directory, payload)
            with zipfile.ZipFile(path) as archive:
                self.assertEqual(load_shared_strings(archive), ["RES 10K 0603"])


if __name__ == "__main__":
    unittest.main()
