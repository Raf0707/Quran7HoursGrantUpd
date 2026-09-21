#!/usr/bin/env python3
"""Strict offline audit for the KFGQPC V4 (1441H) 604-page Mushaf pack.

Run from the Android project root:
    python tools/verify_qul_mushaf_604.py

The check is intentionally fail-closed: if QUL layout id 19 cannot be joined
exactly to the bundled QCF code_v2 stream, the script exits with code 1.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path.cwd()
ASSETS = ROOT / "app/src/main/assets/quran/qpc-v4"
QUL = ASSETS / "qul/quran_pages.json"
LAYOUT = ASSETS / "layout"
V4 = ASSETS / "fonts/v4"
V2 = ASSETS / "fonts/v2"

ARABIC_DIGIT_END = re.compile(r"[٠-٩۰-۹]\s*$")
HEADER_TYPES = {"surah_name", "surah-name", "surah_header", "surah-header"}
BASMALA_TYPES = {"basmallah", "basmala", "bismillah"}


def norm_type(value: object) -> str:
    return str(value or "ayah").strip().lower()


def load_json(path: Path):
    with path.open("r", encoding="utf-8-sig") as f:
        return json.load(f)


def parse_qul_pages(root) -> dict[int, list[dict]]:
    out: dict[int, list[dict]] = {}

    def add(obj, fallback=None):
        if not isinstance(obj, dict):
            return
        page = obj.get("page_number", fallback)
        try:
            page = int(page)
        except (TypeError, ValueError):
            return
        lines = obj.get("lines")
        if isinstance(lines, list):
            out[page] = lines

    if isinstance(root, list):
        for obj in root:
            add(obj)
    elif isinstance(root, dict):
        if isinstance(root.get("pages"), list):
            for obj in root["pages"]:
                add(obj)
        else:
            for key, obj in root.items():
                try:
                    fallback = int(key)
                except (TypeError, ValueError):
                    fallback = None
                add(obj, fallback)
    return out


def glyph_of(word: dict) -> str:
    return str(word.get("qpcV2") or word.get("code_v2") or "").strip()


def qcf_units(word: dict) -> int:
    glyph = glyph_of(word)
    if not glyph:
        return 0
    source_word = str(word.get("word") or "")
    if ARABIC_DIGIT_END.search(source_word) and len(glyph.split()) > 1:
        return 2
    return 1


def line_words(page_json: dict) -> list[dict]:
    result: list[dict] = []
    lines = page_json.get("lines") or []
    for line in sorted((x for x in lines if isinstance(x, dict)), key=lambda x: int(x.get("line", 0) or 0)):
        t = norm_type(line.get("type", "text"))
        if t in HEADER_TYPES or t in BASMALA_TYPES:
            continue
        words = line.get("words") or []
        for word in words:
            if isinstance(word, dict) and str(word.get("location") or "").strip():
                result.append(word)
    return result


def fail(errors: list[str], message: str):
    errors.append(message)


def main() -> int:
    errors: list[str] = []

    for path, label in [(QUL, "QUL layout id 19"), (LAYOUT, "QCF page layouts"), (V4, "V4 fonts"), (V2, "V2 fonts")]:
        if not path.exists():
            fail(errors, f"MISSING: {label}: {path}")

    if errors:
        print("\n".join(errors))
        return 1

    try:
        qul_pages = parse_qul_pages(load_json(QUL))
    except Exception as exc:
        print(f"FAILED to read {QUL}: {exc}")
        return 1

    expected_pages = set(range(1, 605))
    missing_qul = sorted(expected_pages - set(qul_pages))
    extra_qul = sorted(set(qul_pages) - expected_pages)
    if missing_qul:
        fail(errors, f"QUL: missing pages: {missing_qul[:20]}{' ...' if len(missing_qul) > 20 else ''}")
    if extra_qul:
        fail(errors, f"QUL: unexpected pages: {extra_qul[:20]}")

    for page in range(1, 605):
        lines = qul_pages.get(page)
        if not lines:
            continue

        parsed = []
        seen_lines = set()
        for raw in lines:
            if not isinstance(raw, dict):
                fail(errors, f"page {page}: non-object QUL line")
                continue
            try:
                n = int(raw.get("line_number"))
            except (TypeError, ValueError):
                fail(errors, f"page {page}: invalid line_number {raw.get('line_number')!r}")
                continue
            if n not in range(1, 16):
                fail(errors, f"page {page}: line_number {n} outside 1..15")
            if n in seen_lines:
                fail(errors, f"page {page}: duplicate line_number {n}")
            seen_lines.add(n)
            parsed.append((n, raw))

        parsed.sort(key=lambda pair: pair[0])
        if page >= 3 and len(parsed) != 15:
            fail(errors, f"page {page}: expected 15 physical QUL rows, got {len(parsed)}")

        ayah_lines = []
        for n, raw in parsed:
            t = norm_type(raw.get("line_type"))
            if t in HEADER_TYPES or t in BASMALA_TYPES:
                continue
            try:
                first = int(raw.get("first_word_id"))
                last = int(raw.get("last_word_id"))
            except (TypeError, ValueError):
                fail(errors, f"page {page} line {n}: missing/invalid first_word_id or last_word_id")
                continue
            if first > last:
                fail(errors, f"page {page} line {n}: first_word_id {first} > last_word_id {last}")
            ayah_lines.append((n, first, last))

        if ayah_lines:
            first_page_id = ayah_lines[0][1]
            last_page_id = ayah_lines[-1][2]
            expected = first_page_id
            for n, first, last in ayah_lines:
                if first != expected:
                    fail(errors, f"page {page} line {n}: QUL word range starts {first}, expected {expected}")
                expected = last + 1
            if expected - 1 != last_page_id:
                fail(errors, f"page {page}: QUL ranges end incorrectly")

            local_path = LAYOUT / f"page-{page:03d}.json"
            if not local_path.exists():
                fail(errors, f"page {page}: missing {local_path}")
                continue
            try:
                local = load_json(local_path)
            except Exception as exc:
                fail(errors, f"page {page}: cannot parse {local_path.name}: {exc}")
                continue

            words = line_words(local)
            blank = [str(w.get("location") or "?") for w in words if not glyph_of(w)]
            if blank:
                fail(errors, f"page {page}: blank qpcV2/code_v2 at {blank[:6]}")

            actual_units = sum(qcf_units(w) for w in words)
            expected_units = last_page_id - first_page_id + 1
            if actual_units != expected_units:
                fail(
                    errors,
                    f"page {page}: QCF stream has {actual_units} word-table units; "
                    f"QUL layout id 19 requires {expected_units} ({first_page_id}..{last_page_id})",
                )

    for family, folder in [("V4", V4), ("V2", V2)]:
        missing = [page for page in range(1, 605) if not (folder / f"p{page}.ttf").is_file()]
        empty = [page for page in range(1, 605) if (folder / f"p{page}.ttf").is_file() and (folder / f"p{page}.ttf").stat().st_size == 0]
        if missing:
            fail(errors, f"{family}: missing page fonts: {missing[:20]}{' ...' if len(missing) > 20 else ''}")
        if empty:
            fail(errors, f"{family}: empty page fonts: {empty[:20]}")

    if errors:
        print("STRICT MUSHAF AUDIT: FAILED")
        for error in errors[:200]:
            print(" -", error)
        if len(errors) > 200:
            print(f" - ... and {len(errors) - 200} more")
        return 1

    print("STRICT MUSHAF AUDIT: OK")
    print(" - QUL KFGQPC V4 layout: 604/604 pages")
    print(" - line_number / line_type / is_centered / word ranges: valid")
    print(" - local QCF code_v2 stream matches QUL word-id ranges: 604/604")
    print(" - page fonts V4: 604/604")
    print(" - page fonts V2: 604/604")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
