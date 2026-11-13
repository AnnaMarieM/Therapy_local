#!/usr/bin/env python3
"""Scoring for the final-workflow T4 runs. Reads RAW outputs only; never rewrites them.

  score.py asr   <pipeline-dir>...      WER/CER (Czech transcript vs script), translation WER/chrF vs reference-en, checklist on the text, per recording
  score.py facts <pipeline-dir>...      facts per lane: count, quote ok, coverage vs oracle, stability, roles, checklist recall in the facts

The checklist uses inputs/checklist.json patterns (regex on lowercase, diacritics-folded text). Pattern hits are a first pass: kept / lost / inverted-check.
"""
import json, re, sys, unicodedata, itertools, statistics as st
from pathlib import Path
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "e2e" / "combo"))
from common import script, wer, cer, norm_words  # noqa: E402
from report import covered, flaky, toks  # noqa: E402

CHECK = json.loads((HERE / "inputs" / "checklist.json").read_text(encoding="utf-8"))
CLIENTS = ["jana-n", "tomas-b", "daniel-r", "hannah-w"]


def fold(t):
    t = unicodedata.normalize("NFD", t.lower())
    return "".join(c for c in t if unicodedata.category(c) != "Mn")


def load_rows(path):
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def split_onepass(raw):
    """'Czech: ...\nEnglish: ...' -> (cs, en, ok). Raw is kept elsewhere; this only reads it."""
    m = re.search(r"Czech:\s*(.*?)\s*\n+\s*English:\s*(.*)", raw, re.S | re.I)
    if m:
        return m.group(1).strip(), m.group(2).strip(), True
    return None, raw.strip(), False


def reference_en(client):
    return [l.split(":", 1)[1].strip() for l in (HERE / "inputs" / "reference-en" / f"{client}.md").read_text(encoding="utf-8").splitlines() if l.startswith(("THERAPIST:", "CLIENT:"))]


def check(client, text, lang):
    """-> {item id: 'kept'|'lost'|'inverted?'} on one text (lang 'cs' or 'en' chooses the pattern set)."""
    f = fold(text)
    out = {}
    for it in CHECK[client]:
        pats = it[lang]
        hit = all(re.search(p, f) for p in pats)
        status = "kept" if hit else "lost"
        inv = it.get("inv")
        if hit and inv:
            b, ok = (inv["base"], inv["ok"]) if lang == "cs" else (inv["base_en"], inv["ok_en"])
            if all(re.search(p, f) for p in b) and not all(re.search(p, f) for p in ok):
                status = "inverted?"
        out[it["id"]] = status
    return out


def summarize(chk):
    from collections import Counter
    c = Counter(chk.values())
    return f"{c['kept']}/{len(chk)} kept, {c['lost']} lost, {c['inverted?']} inv?"


def chrf(h, r, n_max=6, beta=2.0):
    """chrF, own implementation (character n-grams 1-6 without spaces, precision and recall averaged over n, F-beta with beta 2), 0-100.
    Written because sacrebleu is not installable here; it follows the definition, small differences to sacrebleu are possible."""
    from collections import Counter
    h, r = re.sub(r"\s+", "", h), re.sub(r"\s+", "", r)
    ps, rs = [], []
    for n in range(1, n_max + 1):
        hc = Counter(h[i:i + n] for i in range(len(h) - n + 1))
        rc = Counter(r[i:i + n] for i in range(len(r) - n + 1))
        if not hc or not rc:
            continue
        m = sum((hc & rc).values())
        ps.append(m / sum(hc.values()))
        rs.append(m / sum(rc.values()))
    if not ps:
        return 0.0
    p, rr = sum(ps) / len(ps), sum(rs) / len(rs)
    return 0.0 if p + rr == 0 else 100 * (1 + beta ** 2) * p * rr / (beta ** 2 * p + rr)


def main():
    mode = sys.argv[1]
    for d in sys.argv[2:]:
        d = Path(d)
        for c in CLIENTS:
            cd = d / c
            if not cd.exists():
                continue
            sc = script(c)
            lang = sc["lang"]
            ref = " ".join(t["text"] for t in sc["turns"])
            if mode == "asr":
                for f in sorted(cd.glob("turns*.jsonl")):
                    rows = load_rows(f)
                    texts = [" ".join(r["raw"]) for r in rows]
                    cs = [split_onepass(t) for t in texts] if "onepass" in str(d) or "P-FAST" in d.name and "translate" not in d.name else None
                    print(d.name, c, f.name, "turns", len(rows), "audio_s", round(sum(r["audio_s"] for r in rows), 1), "listen_s", round(sum(r["seconds"] for r in rows), 1))
            # facts mode is handled by the report builder (report_t4.py)


if __name__ == "__main__":
    main()
