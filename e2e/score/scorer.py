#!/usr/bin/env python3
"""Score a harness run (results.json) against the corpus ground truth.

  scorer.py --results results/run1/results.json [--thresholds thresholds-host.json] [--out results/run1/report]

Metrics (all per language, cs and en kept apart):
  stt          WER / CER of each stored transcript against the spoken script
  extraction   recall of planted facts (new facts only), extra facts, duplicated repeats
  dedupe       repeats that became mentions of the original fact
  recall       hit@1/3/5 and MRR of the planted answer for each recall query (facts or windows), negatives' top score
  isolation    any returned item from another client (must be 0)
Matching is lexical (diacritics folded, token F1 of the fact's own words) so it has no model dependency and runs anywhere.
"""
import argparse, functools, json, re, sys, unicodedata
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FACT_MATCH_F1 = 0.45


def fold(s: str) -> str:
    s = unicodedata.normalize("NFKD", s.lower())
    s = "".join(c for c in s if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9 ]+", " ", s)


@functools.lru_cache(maxsize=None)
def _tokens(s: str):
    return tuple(fold(s).split())


def tokens(s: str):
    return list(_tokens(s))


def edit_distance(a, b, band=None):
    """Levenshtein distance; with [band] only cells within that many positions of the diagonal are computed (an upper bound, exact below the band)."""
    n, m = len(a), len(b)
    if band is None:
        band = max(n, m)
    band = max(band, abs(n - m) + 1)
    inf = n + m + 1
    prev = {j: j for j in range(0, min(m, band) + 1)}
    for i in range(1, n + 1):
        cur = {}
        lo, hi = max(0, i - band), min(m, i + band)
        for j in range(lo, hi + 1):
            if j == 0:
                cur[0] = i
                continue
            cur[j] = min(prev.get(j, inf) + 1, cur.get(j - 1, inf) + 1, prev.get(j - 1, inf) + (a[i - 1] != b[j - 1]))
        prev = cur
    return prev.get(m, inf)


def wer(ref: str, hyp: str, strict_diacritics=False):
    f = (lambda s: re.sub(r"[^\w ]+", " ", s.lower()).split()) if strict_diacritics else tokens
    r, h = f(ref), f(hyp)
    return edit_distance(r, h) / max(1, len(r))


def cer(ref: str, hyp: str):
    """Character error rate: words are aligned first (difflib), then characters are compared inside the aligned stretches."""
    import difflib
    r, h = tokens(ref), tokens(hyp)
    errors = 0
    for op, i1, i2, j1, j2 in difflib.SequenceMatcher(None, r, h, autojunk=False).get_opcodes():
        if op == "equal":
            continue
        errors += edit_distance("".join(r[i1:i2]), "".join(h[j1:j2]))
    return errors / max(1, len("".join(r)))


def f1(a, b):
    ca, cb = defaultdict(int), defaultdict(int)
    for t in a: ca[t] += 1
    for t in b: cb[t] += 1
    common = sum(min(v, cb[k]) for k, v in ca.items())
    if not common:
        return 0.0
    p, r = common / len(b), common / len(a)
    return 2 * p * r / (p + r)


def match_score(expected_said: str, text: str):
    """How much of the planted statement this text covers, blended with precision so long texts do not win by size."""
    e, t = tokens(expected_said), tokens(text)
    if not e or not t:
        return 0.0
    cov = sum(1 for w in set(e) if w in set(t)) / len(set(e))
    return 0.6 * cov + 0.4 * f1(e, t)


def load_truth():
    sessions = {}
    for p in sorted((ROOT / "corpus" / "sessions").glob("*/s*.json")):
        d = json.loads(p.read_text(encoding="utf-8"))
        sessions[f"{d['client']}_{d['session']}"] = d
    idx = {c["id"]: c for c in json.loads((ROOT / "corpus" / "clients.json").read_text(encoding="utf-8"))["clients"]}
    return sessions, idx


def pct(n, d):
    return round(100 * n / d, 1) if d else None


def score(results, only_sessions=None):
    truth, clients = load_truth()
    rep = {"by_lang": {}, "sessions": {}, "problems": []}
    langs = defaultdict(lambda: defaultdict(float))

    said = {}  # "<client>.<sNN>.<fN>" -> (text, fact dict, session key)
    for key, d in truth.items():
        for f in d["expected"]["facts"]:
            said[f"{d['client']}.{f['id']}"] = (f["said"], f, key)

    facts_by_session = defaultdict(list)
    for f in results.get("facts", []):
        facts_by_session[f["session"]].append(f)

    present = {s["session"]: s for s in results["sessions"]}
    for sess_key, doc in truth.items():
        if sess_key not in present:
            continue
        lang, client = doc["lang"], doc["client"]
        L = langs[lang]
        s = present[sess_key]
        ref = " ".join(t["text"] for t in doc["turns"])
        hyp = " ".join(s["transcript"])
        w, c = wer(ref, hyp), cer(ref, hyp)
        L["stt_sessions"] += 1; L["wer_sum"] += w; L["cer_sum"] += c
        got = facts_by_session.get(sess_key, [])
        exp = doc["expected"]["facts"]
        r = {"wer": round(w, 3), "cer": round(c, 3), "facts_extracted": len(got), "found": [], "missed": [], "duplicated_repeats": [], "mention_ok": [], "mention_missed": []}
        matched_extracted = set()
        for ef in exp:
            best = max(((match_score(ef["said"], f["heading"] + ". " + f["content"] + " " + f["evidence"]), i) for i, f in enumerate(got)), default=(0, -1))
            if ef["repeat_of"] is None:
                L["new_total"] += 1
                if best[0] >= FACT_MATCH_F1:
                    L["new_found"] += 1; r["found"].append(ef["id"]); matched_extracted.add(best[1])
                else:
                    r["missed"].append(ef["id"])
            else:
                L["repeat_total"] += 1
                if best[0] >= FACT_MATCH_F1:
                    L["repeat_duplicated"] += 1; r["duplicated_repeats"].append(ef["id"]); matched_extracted.add(best[1])
                orig_text, _, orig_key = said[f"{client}.{ef['repeat_of']}"]
                cand = [f for f in facts_by_session.get(orig_key, []) if match_score(orig_text, f["heading"] + ". " + f["content"] + " " + f["evidence"]) >= FACT_MATCH_F1]
                if any(sess_key in f["mentionSessions"] for f in cand):
                    L["repeat_mention"] += 1; r["mention_ok"].append(ef["id"])
                else:
                    r["mention_missed"].append(ef["id"])
        extra = len(got) - len(matched_extracted)
        L["extra"] += extra; L["extracted"] += len(got)
        r["extra_facts"] = extra
        rep["sessions"][sess_key] = r

    # Recall queries
    for a in results.get("answers", []):
        client = a["client"]
        c = clients[client]; lang = c["lang"]; L = langs[lang]
        for item in a["windows"] + a["facts"]:
            if not item["session"].startswith(client + "_"):
                L["leakage"] += 1; rep["problems"].append(f"leak: {client} '{a['q']}' returned {item['session']}")
        q = next((x for x in c["queries"] if x["q"] == a["q"]), None)
        if q is None:  # negative
            top = max([x["score"] for x in a["facts"]] + [0])
            L["neg_n"] += 1; L["neg_top_sum"] += top
            continue
        expected = []
        for e in q["expect"]:
            expected.append((e, said[f"{client}.{e}"][0], f"{client}_{e.split('.')[0]}"))
            root = said[f"{client}.{e}"][1]["repeat_of"]  # a repeat is stored as a mention of the original fact
            if root:
                expected.append((e, said[f"{client}.{root}"][0], f"{client}_{root.split('.')[0]}"))
        # ranked list: facts first then windows, as lists of (session, text)
        ranked_facts = [(x["session"], x["heading"] + ". " + x["content"]) for x in a["facts"]]
        ranked_wins = [(x["session"], x["text"]) for x in a["windows"]]
        def first_hit(ranked):
            for rank, (sess, text) in enumerate(ranked, 1):
                for _, stext, skey in expected:
                    if sess == skey and match_score(stext, text) >= FACT_MATCH_F1:
                        return rank
            return None
        rf, rw = first_hit(ranked_facts), first_hit(ranked_wins)
        L["q_n"] += 1
        for k in (1, 3, 5):
            L[f"fact_hit@{k}"] += bool(rf and rf <= k)
        L["fact_mrr"] += 1 / rf if rf else 0
        L["win_hit@5"] += bool(rw)
        L["any_hit"] += bool(rf or rw)
        covered = len({e for e, stext, skey in expected if any(sess == skey and match_score(stext, t) >= FACT_MATCH_F1 for sess, t in ranked_facts + ranked_wins)})
        L["expect_total"] += len(q["expect"]); L["expect_covered"] += covered

    pipeline = bool(results.get("facts") or results.get("answers"))
    for lang, L in langs.items():
        n = L["stt_sessions"] or 1
        rep["by_lang"][lang] = {
            "sessions": int(L["stt_sessions"]),
            "wer": round(L["wer_sum"] / n, 3), "cer": round(L["cer_sum"] / n, 3),
            "fact_recall_pct": pct(L["new_found"], L["new_total"]),
            "extra_facts_per_session": round(L["extra"] / n, 2),
            "repeat_dedupe_pct": pct(L["repeat_mention"], L["repeat_total"]),
            "repeat_duplicated_pct": pct(L["repeat_duplicated"], L["repeat_total"]),
            "query_fact_hit@1_pct": pct(L["fact_hit@1"], L["q_n"]),
            "query_fact_hit@3_pct": pct(L["fact_hit@3"], L["q_n"]),
            "query_fact_hit@5_pct": pct(L["fact_hit@5"], L["q_n"]),
            "query_fact_mrr": round(L["fact_mrr"] / L["q_n"], 3) if L["q_n"] else None,
            "query_window_hit@5_pct": pct(L["win_hit@5"], L["q_n"]),
            "query_any_hit_pct": pct(L["any_hit"], L["q_n"]),
            "query_expected_coverage_pct": pct(L["expect_covered"], L["expect_total"]),
            "negative_top_fact_score": round(L["neg_top_sum"] / L["neg_n"], 3) if L["neg_n"] else None,
            "cross_client_leaks": int(L["leakage"]),
        }
        if not pipeline:
            rep["by_lang"][lang] = {k: v for k, v in rep["by_lang"][lang].items() if k in ("sessions", "wer", "cer")}
    return rep


def check_thresholds(rep, thresholds):
    fails = []
    for lang, limits in thresholds.items():
        got = rep["by_lang"].get(lang)
        if not got:
            continue
        for metric, lim in limits.items():
            v = got.get(metric)
            if v is None:
                continue
            if metric in ("wer", "cer", "extra_facts_per_session", "cross_client_leaks", "repeat_duplicated_pct") and v > lim:
                fails.append(f"{lang}.{metric} = {v} > {lim}")
            elif metric not in ("wer", "cer", "extra_facts_per_session", "cross_client_leaks", "repeat_duplicated_pct") and v < lim:
                fails.append(f"{lang}.{metric} = {v} < {lim}")
    return fails


def markdown(rep):
    langs = sorted(rep["by_lang"])
    metrics = list(next(iter(rep["by_lang"].values())).keys()) if langs else []
    lines = ["| metric | " + " | ".join(langs) + " |", "|---|" + "---|" * len(langs)]
    for m in metrics:
        lines.append(f"| {m} | " + " | ".join(str(rep["by_lang"][l][m]) for l in langs) + " |")
    if rep["problems"]:
        lines += ["", "Problems:"] + [f"- {p}" for p in rep["problems"][:20]]
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", required=True)
    ap.add_argument("--thresholds", help="JSON of limits per language; the run fails if it is given and missing")
    ap.add_argument("--out")
    ap.add_argument("--no-fail", action="store_true")
    a = ap.parse_args()
    rep = score(json.loads(Path(a.results).read_text(encoding="utf-8")))
    md = markdown(rep)
    print(md)
    out = Path(a.out) if a.out else Path(a.results).with_name("report")
    out.with_suffix(".json").write_text(json.dumps(rep, ensure_ascii=False, indent=1), encoding="utf-8")
    out.with_suffix(".md").write_text(md, encoding="utf-8")
    fails = []
    if a.thresholds:
        th = Path(a.thresholds)
        if not th.exists():
            sys.exit(f"thresholds file not found: {th}")
        fails = check_thresholds(rep, json.loads(th.read_text()))
    for f in fails:
        print("FAIL", f)
    sys.exit(0 if (a.no_fail or not fails) else 1)


if __name__ == "__main__":
    main()
