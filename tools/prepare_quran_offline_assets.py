#!/usr/bin/env python3
"""Compatibility entry point for Quran7Hours offline assets.

v1.4 downloaded 1812 small files one-by-one. v1.4.1 intentionally does not.
On Windows this wrapper invokes the archive-based PowerShell installer which
fetches only three ZIP archives and expands them directly into app assets.
Gradle never invokes this file.
"""
from __future__ import annotations

import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "install_quran_offline_pack.ps1"

if sys.platform != "win32":
    print("Quran7Hours v1.4.1: use tools/install_quran_offline_pack.ps1 on the Windows development machine.")
    print("Gradle itself performs no Quran downloads.")
    raise SystemExit(2)

cmd = [
    "powershell.exe",
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", str(SCRIPT),
    *sys.argv[1:],
]
raise SystemExit(subprocess.call(cmd, cwd=str(ROOT)))
