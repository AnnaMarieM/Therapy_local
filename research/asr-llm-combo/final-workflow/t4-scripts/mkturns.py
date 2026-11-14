"""Turns file for llm.py --turns-file from a per-turn pipeline's RAW output (turns*.jsonl). The raw files are only read.
  mkturns.py --src DIR --lane N --kind en|cs|auto --out FILE
Gemma one-pass answers 'Czech: ...\\nEnglish: ...'; kind en takes the English line, kind cs the Czech line. If a one-pass answer does not parse, the whole raw text is used and the miss is
counted (printed). English recordings: the single transcript line is used as it is (listen prompts answer with the text only)."""
import argparse, json, re, sys
from pathlib import Path


def split_onepass(raw):
    m = re.search(r"Czech:\s*(.*?)\s*\n+\s*English:\s*(.*)", raw, re.S | re.I)
    if m:
        return m.group(1).strip(), m.group(2).strip(), True
    return None, raw.strip(), False


ap = argparse.ArgumentParser()
ap.add_argument("--src", required=True); ap.add_argument("--lane", type=int, default=1); ap.add_argument("--kind", default="en"); ap.add_argument("--out", required=True)
ap.add_argument("--onepass", action="store_true")
a = ap.parse_args()
res, miss = {}, 0
for c in ["jana-n", "tomas-b", "daniel-r", "hannah-w"]:
    d = Path(a.src) / c
    f = d / f"turns__lane{a.lane}.jsonl"
    if not f.exists():
        f = d / "turns.jsonl"
    if not f.exists():
        continue
    cs = c in ("jana-n", "tomas-b")
    rows = [json.loads(l) for l in f.read_text(encoding="utf-8").splitlines() if l.strip()]
    out = []
    for r in rows:
        raw = " ".join(r["raw"]) if not (a.onepass and cs) else None
        if a.onepass and cs:
            parts = [split_onepass(p) for p in r["raw"]]
            miss += sum(1 for p in parts if not p[2])
            raw = " ".join((p[0] if a.kind == "cs" else p[1]) or p[1] for p in parts)
        out.append({"who": r["who"], "text": raw.strip()})
    res[c] = out
json.dump(res, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("wrote", a.out, {k: len(v) for k, v in res.items()}, "onepass parse misses:", miss)
