#!/usr/bin/env python3
"""Aggregate the combo test: asr/*.json + llm/*.json -> report.md (rankings) and review.html (everything, for reading by hand).

  report.py --asr asr --llm llm --out report
Automatic numbers are only a guide; the HTML is what you judge by. Per language (cs, en) because the answer differs.
"""
import argparse, html, itertools, json, statistics as st, sys
from collections import defaultdict
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from common import CLIENTS, script, norm_words

LANGS = {"cs": ["jana-n", "tomas-b"], "en": ["daniel-r", "hannah-w"]}


def load(d):
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(Path(d).rglob("*.json"))]


def toks(f):
    return set(norm_words(f.get("heading", "") + " " + f.get("content", "")))


def sim(a, b):
    return len(a & b) / max(1, len(a | b))


def covered(oracle, mine, thr=0.3):
    """Share of the oracle's facts that some fact of `mine` matches (token overlap)."""
    if not oracle:
        return 0.0
    m = [toks(f) for f in mine]
    return sum(any(sim(toks(o), x) >= thr for x in m) for o in oracle) / len(oracle)


def flaky(lanes):
    """Mean pairwise overlap of the fact sets of the lanes: 1 = every lane found the same facts."""
    ss = []
    for a, b in itertools.combinations(lanes, 2):
        if not a or not b:
            ss.append(0.0)
            continue
        ss.append((sum(any(sim(toks(x), toks(y)) >= 0.3 for y in b) for x in a) / len(a) + sum(any(sim(toks(y), toks(x)) >= 0.3 for x in a) for y in b) / len(b)) / 2)
    return st.mean(ss) if ss else 1.0


def pct(x):
    return f"{100 * x:.1f}%"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--asr", default="asr")
    ap.add_argument("--llm", default="llm")
    ap.add_argument("--out", default="report")
    a = ap.parse_args()
    asr, llm = load(a.asr), load(a.llm)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    md = ["# ASR x LLM combo test\n"]

    # ---- ASR
    by = defaultdict(list)
    for r in asr:
        by[(r["engine"], r["client"])].append(r)
    md.append("## Speech recognition (WER vs the script as read; lowest is best)\n")
    for lang, cl in LANGS.items():
        rows = []
        for e in sorted({k[0] for k in by}):
            ws = [r["wer"] for c in cl for r in by.get((e, c), [])]
            if ws:
                per = {c: [r["wer"] for r in by.get((e, c), [])] for c in cl}
                rtf = st.mean(r["rtf"] for c in cl for r in by.get((e, c), []))
                rows.append((st.mean(ws), e, per, rtf, max(ws) - min(ws)))
        md.append(f"### {lang}\n\n| engine | mean WER | " + " | ".join(f"{c} (min-max over lanes)" for c in cl) + " | RTF (CPU) |\n|---|---|" + "---|" * (len(cl) + 1))
        for m, e, per, rtf, _ in sorted(rows):
            md.append(f"| {e} | {pct(m)} | " + " | ".join(f"{pct(min(per[c]))}-{pct(max(per[c]))}" if per[c] else "-" for c in cl) + f" | {rtf:.2f} |")
        md.append("")

    # ---- LLM (every ASR engine x model), with the script-as-input run as the oracle
    cell = defaultdict(dict)  # (engine, model) -> lane -> result
    for r in llm:
        cell[(r["engine"], r["model"])][r["lane"]] = r
    asr_wer = {(e, l): st.mean(r["wer"] for c in cl for r in by.get((e, c), [])) for e in {k[0] for k in by} for l, cl in LANGS.items() if any(by.get((e, c)) for c in cl)}
    md.append("## Extraction (facts), per ASR engine x model\n")
    md.append("- **coverage**: share of the facts the same model found on the perfect script (all lanes pooled) that this combo also found. A guide to what ASR errors cost, not a score of the model.")
    md.append("- **quote ok**: the evidence quote really is in the transcript the model saw (low = it makes things up).")
    md.append("- **stable**: how much the four lanes agree with each other (low = flaky).\n")
    combos = {}
    for lang, cl in LANGS.items():
        md.append(f"### {lang}\n\n| ASR | LLM | mean WER | facts/recording | coverage | quote ok | stable | lanes |\n|---|---|---|---|---|---|---|---|")
        rows = []
        for (e, m), lanes in cell.items():
            orc = {}
            for c in cl:
                pool = [f for r in cell.get(("script", m), {}).values() for f in r["clients"].get(c, {}).get("facts", [])]
                orc[c] = pool
            cov, ok, n, stab = [], [], [], []
            for c in cl:
                per_lane = [r["clients"].get(c, {}).get("facts", []) for r in lanes.values()]
                if not any(per_lane):
                    continue
                cov.append(st.mean(covered(orc[c], f) for f in per_lane) if orc[c] else float("nan"))
                allf = [f for f in itertools.chain(*per_lane)]
                ok.append(sum(f["quote_found"] for f in allf) / max(1, len(allf)))
                n.append(st.mean(len(f) for f in per_lane))
                stab.append(flaky(per_lane))
            if n:
                rows.append((st.mean(cov), e, m, st.mean(n), st.mean(ok), st.mean(stab), len(lanes)))
        for cv, e, m, n, ok, stab, ln in sorted(rows, reverse=True):
            w = asr_wer.get((e, lang))
            md.append(f"| {e} | {m} | {pct(w) if w is not None else 'oracle'} | {n:.1f} | {pct(cv)} | {pct(ok)} | {pct(stab)} | {ln} |")
            combos[(lang, e, m)] = cv
        md.append("")
    (out / "report.md").write_text("\n".join(md), encoding="utf-8")

    # ---- review.html: transcript errors and every lane's facts, side by side
    h = ["<meta charset=utf-8><title>Combo review</title><style>body{font:14px system-ui;max-width:1200px;margin:2em auto;padding:0 1em}details{margin:.3em 0}summary{cursor:pointer}"
         "table{border-collapse:collapse}td,th{border:1px solid #bbb;padding:3px 6px;vertical-align:top}.bad{background:#fdd}.ok{background:#dfd}small{color:#666}pre{white-space:pre-wrap}</style><h1>Combo review</h1>"]
    h.append("<pre>" + html.escape("\n".join(md)) + "</pre>")
    for c in CLIENTS:
        h.append(f"<h2>{c}</h2>")
        h.append("<details><summary>Script as read</summary><pre>" + html.escape("\n".join(f"{t['who']}: {t['text']}" for t in script(c)["turns"])) + "</pre></details>")
        for e in sorted({r["engine"] for r in asr if r["client"] == c}):
            rs = sorted((r for r in asr if r["client"] == c and r["engine"] == e), key=lambda r: r["lane"])
            h.append(f"<details><summary><b>{e}</b> transcript, WER {pct(rs[0]['wer'])} (lanes: {', '.join(pct(r['wer']) for r in rs)})</summary><pre>"
                     + html.escape("\n".join(f"[{s['start']:.0f}s] {s['text']}" for s in rs[0]["segments"])) + "</pre></details>")
        for (e, m), lanes in sorted(cell.items()):
            if c not in next(iter(lanes.values()))["clients"]:
                continue
            h.append(f"<details><summary><b>{e}</b> + <b>{m}</b>: facts per lane ({', '.join(str(len(r['clients'][c]['facts'])) for _, r in sorted(lanes.items()))})</summary>")
            for ln, r in sorted(lanes.items()):
                h.append(f"<h4>lane {ln}</h4><table><tr><th>heading<th>content<th>evidence<th>category")
                for f in r["clients"][c]["facts"]:
                    h.append(f"<tr class={'ok' if f['quote_found'] else 'bad'}><td>{html.escape(f['heading'])}<td>{html.escape(f['content'])}<td><small>{html.escape(f.get('evidence', ''))}</small><td><small>{html.escape(f.get('category', ''))}</small>")
                h.append("</table>")
            h.append("</details>")
    (out / "review.html").write_text("\n".join(h), encoding="utf-8")
    print("\n".join(md))


if __name__ == "__main__":
    main()
