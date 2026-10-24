#!/usr/bin/env python3
"""Summarise TL trace logs: stage durations per chunk, per-span statistics, backends, errors.

Usage: trace_summary.py <tl.log> [<tl.1.log> ...]     (see scripts/traces.sh)
Lines look like: ts=... lvl=I tag=ASR sid=ab12cd rid=3 cid=3.2 evt=span_end span=asr_transcribe dur_ms=1234.5 k=v ...
"""
import re
import sys
from collections import defaultdict

PAIR = re.compile(r'(\w+)=("(?:[^"\\]|\\.)*"|\S*)')


def parse(line):
    d = {}
    for k, v in PAIR.findall(line):
        if v.startswith('"') and v.endswith('"'):
            v = v[1:-1].replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\")
        d[k] = v
    return d


def fnum(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def table(rows, header):
    widths = [max(len(str(r[i])) for r in [header] + rows) for i in range(len(header))]
    fmt = "  ".join("{:<%d}" % w for w in widths)
    print(fmt.format(*header))
    print(fmt.format(*["-" * w for w in widths]))
    for r in rows:
        print(fmt.format(*[str(c) for c in r]))


def main(paths):
    events = []
    for p in paths:
        with open(p, errors="replace") as f:
            for line in f:
                if "evt=" in line:
                    events.append(parse(line))
    if not events:
        print("no trace lines found")
        return 1

    sessions = sorted({e.get("sid") for e in events if e.get("sid")})
    print(f"events: {len(events)}   sessions: {', '.join(sessions)}\n")

    print("== startup / backends ==")
    for e in events:
        if e.get("evt") in ("startup", "backend_hints", "model_files", "backend_selected", "backend_failed"):
            keep = {k: v for k, v in e.items() if k not in ("ts", "lvl", "sid", "tag")}
            print(f"{e.get('ts', '')[11:]}  {e.get('tag')}  " + " ".join(f"{k}={v}" for k, v in keep.items()))
    print()

    spans = defaultdict(list)
    per_chunk = defaultdict(dict)
    errors = []
    for e in events:
        if e.get("evt") == "span_end":
            d = fnum(e.get("dur_ms"))
            if d is None:
                continue
            spans[(e.get("tag"), e.get("span"))].append(d)
            if e.get("cid"):
                per_chunk[e["cid"]][e["span"]] = d
        elif e.get("evt") == "span_error" or e.get("lvl") == "E":
            errors.append(e)

    print("== span statistics (ms) ==")
    rows = []
    for (tag, name), ds in sorted(spans.items()):
        rows.append([tag, name, len(ds), f"{sum(ds) / len(ds):.1f}", f"{min(ds):.1f}", f"{max(ds):.1f}"])
    table(rows, ["tag", "span", "n", "avg", "min", "max"])
    print()

    stages = ["chunk_cut", "audio_wav_encode", "audio_to_float", "asr_transcribe", "embed", "db_insert_chunk", "chunk_pipeline"]
    present = [s for s in stages if any(s in v for v in per_chunk.values())]
    if per_chunk:
        print("== per chunk (ms) ==")

        def key(c):
            r, _, i = c.partition(".")
            return (int(r) if r.isdigit() else 0, int(i) if i.isdigit() else 0)

        rows = [[cid] + [f"{per_chunk[cid].get(s, ''):.1f}" if s in per_chunk[cid] else "" for s in present] for cid in sorted(per_chunk, key=key)]
        table(rows, ["chunk"] + present)
        print()

    asr = [e for e in events if e.get("evt") == "span_end" and e.get("span") == "asr_transcribe"]
    if asr:
        print("== transcription speed ==")
        rows = [[e.get("cid", ""), e.get("model"), e.get("backend"), e.get("audio_sec"), e.get("dur_ms"), e.get("rtf"), e.get("chars"), e.get("chars_per_sec")] for e in asr]
        table(rows, ["chunk", "model", "backend", "audio_s", "wall_ms", "rtf", "chars", "chars/s"])
        print()

    searches = [e for e in events if e.get("evt") == "span_end" and e.get("span") == "search_scan"]
    if searches:
        print("== searches ==")
        rows = [[e.get("kind"), e.get("rows_scanned"), e.get("scan_ms"), e.get("top", "")] for e in searches]
        table(rows, ["kind", "rows", "scan_ms", "top (rid.idx:score)"])
        print()

    deletes = [e for e in events if e.get("evt") in ("delete_verified", "delete_all_verified")]
    if deletes:
        print("== deletes ==")
        for e in deletes:
            print(" ".join(f"{k}={v}" for k, v in e.items() if k not in ("ts", "lvl", "sid", "tag")))
        print()

    print(f"== errors: {len(errors)} ==")
    for e in errors[:20]:
        print(f"{e.get('ts', '')[11:]}  {e.get('tag')}  {e.get('evt')} {e.get('span', '')} err={e.get('err', '')} {e.get('err_msg', '')}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]) if len(sys.argv) > 1 else print(__doc__) or 1)
