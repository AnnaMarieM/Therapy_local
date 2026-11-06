#!/usr/bin/env python3
"""Job summary of one stress/test lane, or the test x lane matrix of all lanes (python3 stdlib only).

  qa/stress-summary.py out/stress/results.jsonl [--json out/stress/summary.json] >> "$GITHUB_STEP_SUMMARY"
  qa/stress-summary.py --matrix artifacts/ >> "$GITHUB_STEP_SUMMARY"      # artifacts/stress-<lane>/stress/results.jsonl

Input is `files/stress/results.jsonl` (schema tl.stress.v1, one line per test) and, next to it, `lane_caps.json` (the probe facts).
A missing or empty results file is not an error: the summary says so and exits 0, so a lane that died before any test still gets its artifact.
"""
import argparse
import json
import os
import sys
from collections import defaultdict


def load(path):
    rows = []
    if not os.path.isfile(path):
        return rows
    with open(path, encoding="utf-8", errors="replace") as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except ValueError:
                print(f"<!-- bad json on line {n} of {path} -->", file=sys.stderr)
                continue
            if r.get("schema") == "tl.stress.v1":
                rows.append(r)
    return rows


def pct(values, p):
    v = sorted(x for x in values if isinstance(x, (int, float)))
    if not v:
        return None
    return v[min(len(v) - 1, int(round(p / 100.0 * (len(v) - 1))))]


def family(test_id):
    t = str(test_id)
    return t[0] if t and t[0].isalpha() and t[0].isupper() and (len(t) > 1 and (t[1].isdigit() or t[1] == "-")) else "other"


def fmt(x):
    return "-" if x is None else str(x)


def lane_summary(rows, caps):
    s = {"total": len(rows), "passed": 0, "failed": 0, "skipped": 0, "families": {}, "timings": {}, "failures": [], "skips": [], "slowest": []}
    fam = defaultdict(lambda: {"passed": 0, "failed": 0, "skipped": 0})
    timing = defaultdict(list)
    for r in rows:
        st = r.get("status", "failed")
        s[st] = s.get(st, 0) + 1
        fam[family(r.get("test"))][st] = fam[family(r.get("test"))].get(st, 0) + 1
        for k, v in (r.get("timings_ms") or {}).items():
            if v is not None:
                timing[k].append(v)
        if st == "failed":
            s["failures"].append(r)
        if st == "skipped":
            s["skips"].append(r)
    s["families"] = dict(sorted(fam.items()))
    s["timings"] = {k: {"p50": pct(v, 50), "p95": pct(v, 95), "n": len(v)} for k, v in sorted(timing.items())}
    s["slowest"] = sorted(rows, key=lambda r: -(r.get("duration_ms") or 0))[:5]
    s["caps"] = caps
    return s


def render_lane(s, title):
    o = []
    o.append(f"### {title}")
    if not s["total"]:
        o.append("No `results.jsonl` lines: the lane produced no per-test records (see the logs in the artifact).")
    else:
        o.append(f"**{s['passed']} passed, {s['failed']} failed, {s['skipped']} skipped** of {s['total']}")
    caps = s["caps"] or {}
    if caps:
        o.append("")
        o.append("Lane facts (probes): " + ", ".join(
            f"{k}={json.dumps(v) if isinstance(v, (dict, list)) else v}" for k, v in sorted(caps.items())
            if k in ("lane", "api", "device", "ram_mb", "zram_swap_mb", "lmk_reports", "exit_record_latency_ms", "max_inline_bytes",
                     "p05_dialog", "p05_rebind_ms", "page_size")))
        for k in ("p01_segv", "p01_abrt", "p01_kill", "p02_adj"):
            if k in caps:
                o.append(f"- `{k}`: `{json.dumps(caps[k])}`")
    if s["families"]:
        o += ["", "| Family | passed | failed | skipped |", "|---|---:|---:|---:|"]
        for f, c in s["families"].items():
            o.append(f"| {f} | {c.get('passed', 0)} | {c.get('failed', 0)} | {c.get('skipped', 0)} |")
    if s["timings"]:
        o += ["", "| Recovery timing (ms) | p50 | p95 | n |", "|---|---:|---:|---:|"]
        for k, v in s["timings"].items():
            o.append(f"| {k} | {fmt(v['p50'])} | {fmt(v['p95'])} | {v['n']} |")
    # exit kinds: expected vs actual
    kinds = defaultdict(int)
    for r in s.get("_rows", []):
        for e in r.get("exits") or []:
            kinds[(e.get("expected_kind"), e.get("kind"))] += 1
    if kinds:
        o += ["", "| Exit kind expected | actual | n |", "|---|---|---:|"]
        for (a, b), n in sorted(kinds.items(), key=lambda x: str(x)):
            o.append(f"| {fmt(a)} | {fmt(b)} | {n} |")
    gap = max((((r.get("ui") or {}).get("frame_gap_ms") or {}).get("max") or 0 for r in s.get("_rows", [])), default=0)
    wd = max((((r.get("ui") or {}).get("watchdog_max_ms")) or 0 for r in s.get("_rows", [])), default=0)
    if gap or wd:
        o += ["", f"Worst frame gap {gap} ms, worst main-thread watchdog {wd} ms."]
    if s["slowest"]:
        o += ["", "Slowest tests: " + ", ".join(f"{r.get('test')} ({r.get('duration_ms')} ms)" for r in s["slowest"])]
    if s["failures"]:
        o += ["", "<details open><summary>Failures</summary>", ""]
        for r in s["failures"]:
            f = r.get("failure") or {}
            o.append(f"- **{r.get('test')}** `{r.get('class')}.{r.get('method')}`: {str(f.get('message'))[:300]}"
                     f" (first divergent event: `{f.get('first_divergent_event')}`)")
            for ln in ((r.get("excerpts") or {}).get("main") or [])[:5]:
                o.append(f"    - `{str(ln)[:200]}`")
        o += ["", "</details>"]
    if s["skips"]:
        reasons = defaultdict(list)
        for r in s["skips"]:
            reasons[r.get("skip_reason") or "no reason given"].append(str(r.get("test")))
        o += ["", "Skipped (never silent):"]
        for why, ids in sorted(reasons.items()):
            o.append(f"- `{why}`: {', '.join(ids)}")
    return "\n".join(o) + "\n"


def lane_of(path_dir):
    return os.path.basename(path_dir.rstrip("/")).removeprefix("stress-")


def render_matrix(root):
    lanes = {}
    for d in sorted(os.listdir(root)):
        p = os.path.join(root, d, "stress", "results.jsonl")
        if os.path.isdir(os.path.join(root, d)) and d.startswith("stress-"):
            lanes[lane_of(d)] = {r["test"]: r for r in load(p)}
    if not lanes:
        return "### Test x lane matrix\nNo lane artifacts found.\n"
    tests = sorted({t for l in lanes.values() for t in l})
    names = sorted(lanes)
    o = ["### Test x lane matrix", "", "| test | " + " | ".join(names) + " |", "|---|" + "|".join(["---"] * len(names)) + "|"]
    for t in tests:
        cells = []
        for n in names:
            r = lanes[n].get(t)
            if r is None:
                cells.append("")
            elif r["status"] == "passed":
                st = (r.get("timings_ms") or {}).get("stall_max")
                cells.append("ok" + (f" {st} ms" if st else ""))
            elif r["status"] == "failed":
                cells.append("FAIL")
            else:
                cells.append("skip")
        o.append(f"| {t} | " + " | ".join(cells) + " |")
    return "\n".join(o) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results", nargs="?")
    ap.add_argument("--matrix", metavar="DIR")
    ap.add_argument("--json", metavar="FILE", help="write summary.json here (default: next to results.jsonl)")
    ap.add_argument("--title")
    a = ap.parse_args()
    if a.matrix:
        sys.stdout.write(render_matrix(a.matrix))
        return 0
    if not a.results:
        ap.error("results.jsonl or --matrix DIR")
    rows = load(a.results)
    d = os.path.dirname(os.path.abspath(a.results))
    try:
        with open(os.path.join(d, "lane_caps.json"), encoding="utf-8") as f:
            caps = json.load(f)
    except (OSError, ValueError):
        caps = {}
    s = lane_summary(rows, caps)
    s["_rows"] = rows
    title = a.title or (rows[0].get("lane") if rows else caps.get("lane")) or "lane"
    sys.stdout.write(render_lane(s, f"Device tests: {title}"))
    del s["_rows"]
    s["failures"] = [{k: r.get(k) for k in ("test", "class", "method", "failure")} for r in s["failures"]]
    s["skips"] = [{k: r.get(k) for k in ("test", "skip_reason")} for r in s["skips"]]
    s["slowest"] = [{k: r.get(k) for k in ("test", "duration_ms")} for r in s["slowest"]]
    try:
        with open(a.json or os.path.join(d, "summary.json"), "w", encoding="utf-8") as f:
            json.dump(s, f, indent=2, sort_keys=True)
    except OSError as e:
        print(f"<!-- cannot write summary.json: {e} -->", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
