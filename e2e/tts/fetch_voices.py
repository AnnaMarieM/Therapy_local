#!/usr/bin/env python3
"""Downloads the Piper voices used by render.py into e2e/voices (skips what is there)."""
import urllib.request
from pathlib import Path

VOICES = Path(__file__).resolve().parents[1] / "voices"
BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/main"
PATHS = [
    "cs/cs_CZ/jirka/medium/cs_CZ-jirka-medium",
    "en/en_US/lessac/medium/en_US-lessac-medium",
    "en/en_US/ryan/medium/en_US-ryan-medium",
]

VOICES.mkdir(exist_ok=True)
for p in PATHS:
    for ext in ("onnx", "onnx.json"):
        f = VOICES / f"{p.rsplit('/', 1)[1]}.{ext}"
        if not f.exists():
            print("downloading", f.name, flush=True)
            urllib.request.urlretrieve(f"{BASE}/{p}.{ext}", f)
