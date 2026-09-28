#!/usr/bin/env python3
"""Resolve a human component request to a canonical component identity.

This is the deterministic step between the dialogue lane and everything
else: a phrase like "main chip" or "the RGB LED" becomes exactly one
`component_id`, or an explicit abstention. It exists so the language model
never has to pick a reference designator itself -- it supplies the phrase,
this supplies the identity, and the AR lane gets a selector it can trust.

Evidence, in priority order, all from published artifacts:

1. an exact reference designator in the phrase ("find R87");
2. a landmark in the reviewed board reference map whose display name or
   guide label matches, and which carries a reference designator or part
   number (that map is the human-facing naming authority for this board);
3. a BOM part number in the phrase;
4. BOM description / component-class keywords, but only when the phrase's
   role words select exactly one candidate.

Ambiguity is never resolved by ranking guesses: if more than one component
survives, every candidate is returned with `status: "ambiguous"` so the
caller can ask which one. Nothing here contains board-specific vocabulary
beyond the generic role words below, which are ordinary electronics terms,
not FRDM-MCXN236 knowledge.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

# Generic electronics role vocabulary -> component classes / description
# keywords. Board-independent: these are the words any engineer uses, and
# they only ever narrow candidates that the board's own BOM already
# describes. No reference designator is ever hardcoded here.
ROLE_TERMS: dict[str, dict[str, Any]] = {
    # `landmark_category` names the category the reviewed board reference map
    # uses for this role. A board can carry several parts of the same class
    # (this one has two MCUs), and the reviewed map is the authority on which
    # is the main processor -- so the category disambiguates on evidence
    # rather than on a preference invented here.
    "mcu": {"classes": {"integrated_circuit"}, "keywords": ("MCU", "MICROCONTROLLER"), "landmark_category": "processor"},
    "microcontroller": {"classes": {"integrated_circuit"}, "keywords": ("MCU", "MICROCONTROLLER"), "landmark_category": "processor"},
    "processor": {"classes": {"integrated_circuit"}, "keywords": ("MCU", "MICROCONTROLLER", "PROCESSOR"), "landmark_category": "processor"},
    "main chip": {"classes": {"integrated_circuit"}, "keywords": ("MCU", "MICROCONTROLLER"), "landmark_category": "processor"},
    "accelerometer": {"classes": {"integrated_circuit"}, "keywords": ("ACCELEROMETER",)},
    "microphone": {"classes": {"integrated_circuit"}, "keywords": ("MIC", "MEMS")},
    "flash": {"classes": {"integrated_circuit"}, "keywords": ("FLASH",)},
    "crystal": {"classes": {"crystal_or_oscillator"}, "keywords": ("CRYSTAL", "XTAL")},
    "resistor": {"classes": {"resistor"}, "keywords": ("RES",)},
    "capacitor": {"classes": {"capacitor"}, "keywords": ("CAP",)},
    "inductor": {"classes": {"inductor_or_ferrite"}, "keywords": ("IND",)},
    "diode": {"classes": {"diode_or_led"}, "keywords": ("DIODE",)},
    "led": {"classes": {"diode_or_led"}, "keywords": ("LED",)},
    "rgb led": {"classes": {"diode_or_led"}, "keywords": ("RED/GRN/BL", "RGB", "TRI")},
    "switch": {"classes": {"switch"}, "keywords": ("SWITCH", "SW")},
    "button": {"classes": {"switch"}, "keywords": ("SWITCH", "SW")},
    "connector": {"classes": {"connector_or_header"}, "keywords": ("CON",)},
    "header": {"classes": {"connector_or_header"}, "keywords": ("HDR", "CON")},
    "transceiver": {"classes": {"integrated_circuit"}, "keywords": ("XCVR", "TRANSCEIVER")},
    "regulator": {"classes": {"integrated_circuit"}, "keywords": ("VREG", "LDO", "REGULATOR")},
}

REFERENCE_PATTERN = re.compile(r"\b([A-Z]{1,3})(\d{1,3})\b", re.IGNORECASE)


def _normalize(text: str) -> str:
    return re.sub(r"[^a-z0-9 ]+", " ", text.lower()).strip()


def _result(component: dict[str, Any], evidence: str, method: str) -> dict[str, Any]:
    return {
        "component_id": component["component_id"],
        "reference": component["reference"],
        "component_class": component["component_class"],
        "bom_part_number": component.get("bom_part_number"),
        "bom_description": component.get("bom_description"),
        "board_position_mm": component["board_position_mm"],
        "side": component["side"],
        "glb_selector": component["glb_selector"],
        "match_method": method,
        "evidence": evidence,
    }


def _landmark_references(landmark: dict[str, Any]) -> list[str]:
    identifiers = landmark.get("identifiers") or {}
    raw = identifiers.get("reference_designator") or identifiers.get("reference_designators")
    return [reference.strip().upper() for reference in str(raw).split(",")] if raw else []


def landmark_index(board_reference: dict[str, Any] | None) -> list[dict[str, Any]]:
    """Landmarks that carry a usable identity, with their searchable names."""
    if not board_reference:
        return []
    entries = []
    for landmark in board_reference.get("landmarks", []):
        identifiers = landmark.get("identifiers") or {}
        references = identifiers.get("reference_designator") or identifiers.get("reference_designators")
        part_number = identifiers.get("part_number")
        if not references and not part_number:
            continue
        names = {
            _normalize(landmark.get("display_name", "")),
            _normalize(landmark.get("landmark_id", "").replace("-", " ")),
            _normalize(identifiers.get("guide_label", "")),
        }
        entries.append(
            {
                "landmark_id": landmark["landmark_id"],
                "names": {n for n in names if n},
                "references": [r.strip().upper() for r in str(references).split(",")] if references else [],
                "part_number": part_number,
            }
        )
    return entries


def resolve_query(
    query: str,
    component_map: dict[str, Any],
    board_reference: dict[str, Any] | None = None,
    max_candidates: int = 12,
) -> dict[str, Any]:
    components = {c["reference"].upper(): c for c in component_map["components"]}
    renderable = [c for c in component_map["components"] if c.get("glb_selector")]
    normalized = _normalize(query)

    # 1. explicit reference designator
    for match in REFERENCE_PATTERN.finditer(query):
        reference = (match.group(1) + match.group(2)).upper()
        component = components.get(reference)
        if component:
            return {
                "status": "resolved",
                "query": query,
                **_result(component, f"explicit reference designator {reference!r} in the request", "reference_designator"),
            }

    # 2. reviewed board-reference landmark (the board's human naming authority)
    for entry in landmark_index(board_reference):
        if not any(name and name in normalized for name in entry["names"]):
            continue
        matched = [components[r] for r in entry["references"] if r in components]
        if not matched and entry["part_number"]:
            matched = [c for c in component_map["components"] if (c.get("bom_part_number") or "").upper() == entry["part_number"].upper()]
        if len(matched) == 1:
            return {
                "status": "resolved",
                "query": query,
                **_result(matched[0], f"board-reference landmark {entry['landmark_id']!r}", "board_reference_landmark"),
            }
        if len(matched) > 1:
            return {
                "status": "ambiguous",
                "query": query,
                "reason": f"landmark {entry['landmark_id']!r} names {len(matched)} components",
                "candidates": [_result(c, f"landmark {entry['landmark_id']!r}", "board_reference_landmark") for c in matched[:max_candidates]],
            }

    # 3. explicit BOM part number
    for component in component_map["components"]:
        part_number = (component.get("bom_part_number") or "").lower()
        if part_number and len(part_number) > 4 and part_number in query.lower():
            return {
                "status": "resolved",
                "query": query,
                **_result(component, f"BOM part number {component['bom_part_number']!r} in the request", "bom_part_number"),
            }

    # 4. generic role words narrowed against the board's own BOM text
    matched_terms = sorted((term for term in ROLE_TERMS if term in normalized), key=len, reverse=True)
    if matched_terms:
        term = matched_terms[0]
        rule = ROLE_TERMS[term]
        candidates = [
            c
            for c in renderable
            if c["component_class"] in rule["classes"]
            and any(keyword in (c.get("bom_description") or "").upper() for keyword in rule["keywords"])
        ]
        if len(candidates) == 1:
            return {
                "status": "resolved",
                "query": query,
                **_result(candidates[0], f"only component matching role {term!r} in the BOM", "role_and_bom_description"),
            }
        if len(candidates) > 1 and rule.get("landmark_category"):
            # several parts share the role; the reviewed board reference map
            # says which one holds that role on this board
            designated = {
                reference
                for landmark in (board_reference or {}).get("landmarks", [])
                if landmark.get("category") == rule["landmark_category"]
                for reference in _landmark_references(landmark)
            }
            narrowed = [c for c in candidates if c["reference"].upper() in designated]
            if len(narrowed) == 1:
                return {
                    "status": "resolved",
                    "query": query,
                    **_result(
                        narrowed[0],
                        f"role {term!r}; the reviewed board reference designates it as the {rule['landmark_category']!r}",
                        "role_narrowed_by_landmark_category",
                    ),
                }
        if candidates:
            return {
                "status": "ambiguous",
                "query": query,
                "reason": f"{len(candidates)} components match the role {term!r}; ask which one",
                "candidate_count": len(candidates),
                "candidates": [_result(c, f"role {term!r}", "role_and_bom_description") for c in candidates[:max_candidates]],
            }

    return {
        "status": "unresolved",
        "query": query,
        "reason": "no reference designator, board-reference landmark, part number, or unambiguous role matched",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Resolve a human component request to a canonical component_id.")
    parser.add_argument("--component-map", required=True, type=Path)
    parser.add_argument("--board-reference", type=Path, help="reviewed board reference map (adds landmark naming)")
    parser.add_argument("--query", required=True)
    args = parser.parse_args(argv)

    board_reference = json.loads(args.board_reference.read_text(encoding="utf-8")) if args.board_reference else None
    result = resolve_query(args.query, json.loads(args.component_map.read_text(encoding="utf-8")), board_reference)
    print(json.dumps(result, indent=2))
    return 0 if result["status"] == "resolved" else 1


if __name__ == "__main__":
    raise SystemExit(main())
