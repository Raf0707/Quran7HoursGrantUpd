#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

PROJECT = Path(__file__).resolve().parent.parent
ROOT = PROJECT / "app" / "src" / "main" / "assets" / "quran" / "qpc-v4"
REPORT = PROJECT / "quran-offline-pack-audit.json"


def is_font(path: Path) -> bool:
    try:
        if not path.is_file() or path.stat().st_size < 1024:
            return False
        return path.read_bytes()[:4] in (b"\x00\x01\x00\x00", b"OTTO", b"ttcf", b"true", b"typ1")
    except OSError:
        return False


def is_layout(path: Path, page: int) -> bool:
    try:
        if not path.is_file() or path.stat().st_size < 32:
            return False
        obj = json.loads(path.read_text(encoding="utf-8"))
        actual = obj.get("page", obj.get("pageNumber", page))
        return int(actual) == page and bool(obj.get("lines"))
    except Exception:
        return False


layout_bad: list[int] = []
v4_bad: list[int] = []
v2_bad: list[int] = []
for page in range(1, 605):
    padded = f"{page:03d}"
    if not is_layout(ROOT / "layout" / f"page-{padded}.json", page):
        layout_bad.append(page)
    if not is_font(ROOT / "fonts" / "v4" / f"p{page}.ttf"):
        v4_bad.append(page)
    if not is_font(ROOT / "fonts" / "v2" / f"p{page}.ttf"):
        v2_bad.append(page)

bad_pages = sorted(set(layout_bad + v4_bad + v2_bad))
report = {
    "schema": 2,
    "complete": not bad_pages,
    "expected_pages": 604,
    "expected_assets": 1812,
    "layout": {"valid": 604 - len(layout_bad), "bad_pages": layout_bad},
    "v4_tajweed": {"valid": 604 - len(v4_bad), "bad_pages": v4_bad},
    "v2_plain": {"valid": 604 - len(v2_bad), "bad_pages": v2_bad},
    "incomplete_pages": bad_pages,
}
REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

print("Quran7Hours offline Mushaf audit")
print(f"  layouts:    {604 - len(layout_bad)}/604")
print(f"  V4 Tajweed: {604 - len(v4_bad)}/604")
print(f"  V2 plain:   {604 - len(v2_bad)}/604")
print(f"  total:      {1812 - len(layout_bad) - len(v4_bad) - len(v2_bad)}/1812")
print(f"  report:     {REPORT}")

if bad_pages:
    print(f"INCOMPLETE: {len(bad_pages)} page(s) have missing/invalid QPC resources")
    print("first pages:", ", ".join(map(str, bad_pages[:40])))
    sys.exit(2)

print("OK: complete 604-page QPC pack is present and structurally valid")
sys.exit(0)
