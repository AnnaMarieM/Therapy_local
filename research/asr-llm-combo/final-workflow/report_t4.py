#!/usr/bin/env python3
"""Builds the T4 tables of RESULTS-T4.md from the RAW outputs in final-workflow/t4/. Nothing is written back to the raw files.

  report_t4.py > tables.md

Layout read (see PLAN.md section 5):
  t4/<pipeline>/<client>/turns[__laneN].jsonl     raw per-turn output of the listening stage (rows: raw = list of piece outputs)
  t4/<pipeline>/<client>/timing[__laneN].json     listening-stage timing
  t4/<pipeline>/stage2_<model>/laneN.json         llm.py result (all four recordings): turns after pivot/roles, roles answer, facts with quotes, stage times
"""
import json, re, sys, statistics as st
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import score as S  # noqa: E402
from common import script, wer, cer  # noqa: E402
from report import covered, flaky  # noqa: E402

T4 = HERE / "t4"
CLIENTS = S.CLIENTS
CS = ["jana-n", "tomas-b"]
FACTS_CI = HERE.parents[0] / "facts" / "ci-cpu"


def rows(p, c, lane=None):
    d = T4 / p / c
    f = d / (f"turns__lane{lane}.jsonl" if lane else "turns.jsonl")
    if not f.exists():
        fs = sorted(d.glob("turns*.jsonl"))
        if not fs:
            return None
        f = fs[0]
    return S.load_rows(f)


def timing(p, c, lane=None):
    d = T4 / p / c
    f = d / (f"timing__lane{lane}.json" if lane else "timing.json")
    if not f.exists():
        fs = sorted(d.glob("timing*.json"))
        if not fs:
            return None
        f = fs[0]
    return json.loads(f.read_text(encoding="utf-8"))


def texts(p, c, lane=None):
    """(cs_text, en_text) of a listening-stage output. One-pass Gemma pipelines have both; transcribe-only have cs (Czech recording) or en."""
    rs = rows(p, c, lane)
    if rs is None:
        return None, None
    lang = script(c)["lang"]
    raws = [" ".join(r["raw"]) for r in rs]
    onepass = p.startswith("P-FAST") and "translate-only" not in p and lang == "cs"
    if onepass:
        parts = [S.split_onepass(" ".join(r["raw"])) for r in rs]
        return " ".join(x[0] or "" for x in parts).strip(), " ".join(x[1] for x in parts).strip()
    if lang == "en":
        return None, " ".join(raws).strip()
    if "translate" in p or p.startswith("P-QUALITY"):
        return None, " ".join(raws).strip()
    return " ".join(raws).strip(), None


def lane_json(p, m, lane):
    f = T4 / p / f"stage2_{m}" / f"lane{lane}.json"
    return json.loads(f.read_text(encoding="utf-8")) if f.exists() else None


def pct(x):
    return "n/a" if x is None else f"{100 * x:.1f}%"


def main():
    pipes = sorted(p.name for p in T4.iterdir() if p.is_dir())
    out = []
    # ---------------- listening stage
    out.append("## Listening stage: time (T4) and text quality\n")
    out.append("| pipeline | recording | audio s | wall s | RTF | Czech WER | Czech CER | EN vs ref WER | EN vs ref chrF | EN WER vs script (English rec.) |\n|---|---|---|---|---|---|---|---|---|---|")
    for p in pipes:
        for c in CLIENTS:
            tcs, ten = texts(p, c)
            tm = timing(p, c)
            if tcs is None and ten is None:
                continue
            sc = script(c)
            ref = " ".join(t["text"] for t in sc["turns"])
            w = ch = rw = rc = ew = None
            if tcs:
                w, ch = wer(ref, tcs, "cs"), cer(ref, tcs, "cs")
            if ten and sc["lang"] == "cs":
                r = " ".join(S.reference_en(c))
                rw, rc = wer(r, ten, "en"), S.chrf(ten, r)
            if ten and sc["lang"] == "en":
                ew = wer(ref, ten, "en")
            wall = tm["listen_wall_s"] if tm else None
            aud = tm["audio_in_pieces_s"] if tm else None
            out.append(f"| {p} | {c} | {aud} | {wall} | {tm['rtf'] if tm else '-'} | {pct(w)} | {pct(ch)} | {pct(rw)} | {'n/a' if rc is None else round(rc, 1)} | {pct(ew)} |")
    return "\n".join(out)


if __name__ == "__main__":
    print(main())
