#!/usr/bin/env python3
"""results.json + sitting truth -> report.md (and report.json): what the app made of each sitting.

  report.py --results results/run1/results.json --out results/run1 [--title "..."] [--note "..."]

Per sitting, in this order: memories (stored facts against the planted ones, lexical matching of score/scorer.py), the speaker map against the
expected roles, diarization (speakers found vs expected, % of transcript words given to the right speaker), the transcript in the PR 1 format.
Then, per client, every memory across that client's sittings. Not a gate: nothing here fails a run.
Needs from results.json: sessions[].segments (with speaker) and speakerMap, facts[] (DevResults of the PR 1 build). A results.json from an
older build still works: the plain transcript lines are used and the speaker parts read "no diarization".
"""
import argparse, json, sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "score"))
from scorer import FACT_MATCH_F1, match_score, wer  # noqa: E402  (reused, not forked)

ROLE_LABEL = {"therapist": "Therapist", "patient": "Patient", "other": "Other"}


# ---- transcript ------------------------------------------------------------------------------------------------------------

def clock(ms):
    s = max(int(ms or 0), 0) // 1000
    return "%02d:%02d:%02d" % (s // 3600, s // 60 % 60, s % 60)


def segments_of(sess):
    """[{startMs,endMs,speaker,text}]; older results only have the plain lines (no times, no speakers)."""
    if sess.get("segments"):
        return [{"idx": s.get("idx"), "startMs": s.get("startMs") or 0, "endMs": s.get("endMs") or 0, "speaker": s.get("speaker"), "text": s["text"]} for s in sess["segments"]]
    return [{"idx": i + 1, "startMs": 0, "endMs": 0, "speaker": None, "text": t} for i, t in enumerate(sess.get("transcript", []))]


def label_fn(segs, smap):
    order = {}
    for s in segs:
        if s["speaker"] and s["speaker"] not in order:
            order[s["speaker"]] = len(order) + 1
    roles = (smap or {}).get("map") or {}

    def label(sp):
        r = roles.get(sp) if sp else None
        if r in ("therapist", "patient"):
            return ROLE_LABEL[r]
        return f"Speaker {order[sp]}" if sp in order else "Speaker ?"
    return label


def transcript_text(segs, smap):
    label = label_fn(segs, smap)
    timed = any(s["endMs"] for s in segs)
    out = []
    for s in segs:
        if s["text"].strip():
            out.append(f"[{clock(s['startMs'])}]\n{label(s['speaker'])}: {s['text'].strip()}" if timed else f"{label(s['speaker'])}: {s['text'].strip()}")
    return "\n\n".join(out)


# ---- diarization against the truth -------------------------------------------------------------------------------------------

def overlap(a0, a1, b0, b1):
    return max(0, min(a1, b1) - max(a0, b0))


def diarization(segs, truth):
    """hyp speaker -> truth speaker by best time overlap; word share given to the right speaker."""
    hyp = sorted({s["speaker"] for s in segs if s["speaker"]})
    res = {"found": len(hyp), "expected": 2, "mapping": {}, "word_accuracy_pct": None, "diarized": bool(hyp)}
    if not hyp or not truth or not any(s["endMs"] for s in segs):
        return res
    tturns = truth["turns"]
    per_hyp = defaultdict(lambda: defaultdict(float))
    seg_best = []
    for s in segs:
        ov = defaultdict(float)
        for t in tturns:
            o = overlap(s["startMs"], s["endMs"], t["start_ms"], t["end_ms"])
            if o:
                ov[t["speaker"]] += o
        best = max(ov, key=ov.get) if ov else None
        seg_best.append(best)
        if s["speaker"]:
            for k, v in ov.items():
                per_hyp[s["speaker"]][k] += v
    res["mapping"] = {h: (max(per_hyp[h], key=per_hyp[h].get) if per_hyp[h] else None) for h in hyp}
    right = total = 0
    for s, best in zip(segs, seg_best):
        n = len(s["text"].split())
        total += n
        if s["speaker"] and best and res["mapping"].get(s["speaker"]) == best:
            right += n
    res["word_accuracy_pct"] = round(100 * right / total, 1) if total else None
    return res


def roles_check(truth, dia, smap):
    """Expected role per truth speaker against the role the app gave the hypothesis speaker mapped to it."""
    exp = truth["expect"]["roles"] if truth else {}
    rows = []
    inv = defaultdict(list)
    for h, t in dia["mapping"].items():
        inv[t].append(h)
    for tspk, role in exp.items():
        hs = inv.get(tspk, [])
        got = [((smap or {}).get("map") or {}).get(h) for h in hs]
        ok = bool(hs) and any(g == role for g in got) and all(g == role for g in got if g)
        rows.append({"speaker": tspk, "expected": role, "hyp": hs, "got": got, "ok": ok})
    return rows


# ---- memories ----------------------------------------------------------------------------------------------------------------

def fact_text(f):
    return f["heading"] + ". " + f["content"] + " " + f.get("evidence", "")


def match_facts(doc, sess_id, facts_all, planted_by_id):
    """planted facts of the sitting against the facts stored for it."""
    stored = [f for f in facts_all if f["session"] == sess_id]
    rows, used = [], set()
    for pf in doc["facts"]:
        best = max(((match_score(pf["said"], fact_text(f)), i) for i, f in enumerate(stored)), default=(0, -1))
        hit = best[0] >= FACT_MATCH_F1
        row = {"id": pf["id"], "heading": pf["heading"], "said": pf["said"], "who": pf["who"], "repeat_of": pf["repeat_of"], "status": None, "stored": None}
        if hit:
            used.add(best[1])
            row["stored"] = stored[best[1]]
        if pf["repeat_of"] is None:
            row["status"] = "found" if hit else "missing"
        else:
            orig = planted_by_id[pf["repeat_of"]]
            osess = pf["repeat_of"].rsplit(".", 1)[0]
            cands = [f for f in facts_all if f["session"] == osess and match_score(orig["said"], fact_text(f)) >= FACT_MATCH_F1]
            m = next((c for c in cands if sess_id in c.get("mentionSessions", [])), None)
            if m:
                row["status"], row["stored"] = "mention", m
            elif hit:
                row["status"] = "duplicated"
            else:
                row["status"] = "missing"
        rows.append(row)
    extra = [f for i, f in enumerate(stored) if i not in used]
    return rows, extra, stored


def fact_time(f, segs):
    if f.get("timeMs") is not None:
        return clock(f["timeMs"])
    for s in segs:
        if s["idx"] == f.get("segIdx") and s["endMs"]:
            return clock(s["startMs"])
    return "--:--:--"


def one_line(s, n=260):
    s = " ".join((s or "").split())
    return s if len(s) <= n else s[: n - 1] + "…"


STATUS_MARK = {"found": "FOUND", "mention": "MENTION", "duplicated": "DUPLICATED", "missing": "MISSING"}


# ---- report ------------------------------------------------------------------------------------------------------------------

def build(results, docs, truths, title, note):
    sessions = {s["session"]: s for s in results.get("sessions", [])}
    facts_all = results.get("facts", [])
    planted_by_id = {f["id"]: f for d in docs.values() for f in d["facts"]}
    md = [f"# {title}", ""]
    if note:
        md += [note, ""]
    any_diar = any(s.get("speaker") for sess in sessions.values() for s in sess.get("segments", []))
    any_map = any(sess.get("speakerMap") for sess in sessions.values())
    if not any_diar:
        md += ["> No speaker was recorded for any segment: this run is from a build without diarization (a pre-PR-1 baseline). "
               "Memories and transcripts below are real output; the speaker map and diarization parts are empty.", ""]
    summary, jrep = [], {"sittings": {}}
    body = []
    for sid, doc in docs.items():
        sess = sessions.get(sid)
        truth = truths.get(sid)
        body += [f"## {sid} - {doc['what']}", "",
                 f"Client {doc['client_name']} ({doc['client']}), {doc['lang']}, {doc['date']}"
                 + (f", {truth['duration_ms'] / 60000:.1f} min" if truth else "") + f". Voices: " + ", ".join(f"{k} {v}" for k, v in doc["voices"].items()) + "."
                 + (f" Effects: {doc['fx']}." if doc["fx"] else ""), ""]
        if not sess:
            body += ["Not in the results (not run, or the run failed).", ""]
            jrep["sittings"][sid] = {"present": False}
            summary.append((sid, doc["lang"], "-", "-", "-", "-", "-"))
            continue
        segs = segments_of(sess)
        smap = sess.get("speakerMap")
        rows, extra, stored = match_facts(doc, sid, facts_all, planted_by_id)
        earlier = [f for f in facts_all if f["client"] == doc["client"] and f["session"] != sid and sid in f.get("mentionSessions", [])]
        n_new = sum(1 for r in rows if r["repeat_of"] is None)
        n_found = sum(1 for r in rows if r["repeat_of"] is None and r["status"] == "found")
        n_rep = sum(1 for r in rows if r["repeat_of"])
        n_men = sum(1 for r in rows if r["status"] == "mention")
        # memories
        body += ["### Memories", "", f"{len(stored)} stored for this session; {n_found} of {n_new} planted new facts found, {n_men} of {n_rep} planted repeats recorded as mentions, {len(extra)} extra, "
                 f"{sum(1 for r in rows if r['status'] == 'missing')} planted missing.", ""]
        if stored:
            body += ["Stored (what the app remembers from this sitting):", ""]
            planted_of = {id(r["stored"]): r for r in rows if r["stored"] is not None and r["status"] in ("found", "duplicated")}
            for f in sorted(stored, key=lambda f: (f.get("timeMs") if f.get("timeMs") is not None else f.get("segIdx", 0))):
                r = planted_of.get(id(f))
                tag = f"planted, said by {r['who']}" if r else "extra"
                body += [f"- **{f['heading']}** [{fact_time(f, segs)}] ({tag})", f"  {one_line(f['content'])}", f"  evidence: \"{one_line(f.get('evidence'), 200)}\""]
            body.append("")
        if earlier:
            body += ["Earlier facts this sitting mentioned again:", ""] + [f"- **{f['heading']}** (first in {f['session']}): {one_line(f['content'], 160)}" for f in earlier] + [""]
        body += ["Planted against stored:", "", "| fact | said by | planted | result | stored as |", "|---|---|---|---|---|"]
        for r in rows:
            kind = "repeat of " + r["repeat_of"].split(".")[0] + "." + r["repeat_of"].split(".")[1] if r["repeat_of"] else "new"
            st = f"{r['stored']['heading']}" if r["stored"] else ""
            body.append(f"| {r['id'].split('.')[1]} {r['heading']} | {r['who']} | {kind} | {STATUS_MARK[r['status']]} | {st} |")
        miss = [r for r in rows if r["status"] == "missing"]
        if miss:
            body += ["", "Missing planted facts:", ""] + [f"- {r['id']} ({r['who']}): \"{r['said']}\"" for r in miss]
        body.append("")
        # speaker map
        dia = diarization(segs, truth)
        body += ["### Speaker map", ""]
        rc = roles_check(truth, dia, smap)
        if smap:
            ev = smap.get("evidence")
            body += [f"Map (version {smap.get('version')}, source {smap.get('source')}, confidence {smap.get('confidence')}): `{json.dumps(smap.get('map'), ensure_ascii=False)}`",
                     f"Evidence: {one_line(json.dumps(ev, ensure_ascii=False) if not isinstance(ev, str) else ev, 400)}"]
        else:
            body.append("No speaker map stored.")
        for r in rc:
            body.append(f"- expected {r['speaker']} = {r['expected']}; app: {r['hyp'] or 'no matching speaker'} -> {r['got']}: " + ("OK" if r["ok"] else "WRONG"))
        body.append("")
        # diarization
        body += ["### Diarization", ""]
        lim = (truth or doc)["expect"].get("limitation")
        if lim:
            body += [f"**Known limitation ({lim}):** this recording has {doc['expect']['voices']} real voice; the diarizer is always asked for exactly two speakers, "
                     "so a second, invented speaker and a role guess are expected. Not a failure; read the transcript to see how it looks.", ""]
        if dia["diarized"]:
            acc = "n/a (one real voice)" if lim else f"{dia['word_accuracy_pct']}%"
            body += [f"Speakers found {dia['found']}, expected {dia['expected']}. Speaker to truth speaker: `{json.dumps(dia['mapping'])}`. "
                     f"Words attributed to the right speaker: {acc}."]
        else:
            body += [f"No diarization in the results (speakers found 0, expected {dia['expected']})."]
        spoken = " ".join(t["text"] for t in doc["turns"])
        w = wer(spoken, " ".join(s["text"] for s in segs))
        body += ["", f"Transcript against the spoken script: WER {100 * w:.0f}%.", ""]
        # transcript
        body += ["### Transcript", "", "```", transcript_text(segs, smap), "```", ""]
        jrep["sittings"][sid] = {
            "present": True, "facts_stored": len(stored), "planted_new": n_new, "found": n_found, "planted_repeats": n_rep, "mentions": n_men,
            "extra": len(extra), "missing": [r["id"] for r in rows if r["status"] == "missing"],
            "speakers_found": dia["found"], "speakers_expected": dia["expected"], "word_attribution_pct": dia["word_accuracy_pct"], "known_limitation": lim,
            "roles": [{"speaker": r["speaker"], "expected": r["expected"], "got": r["got"], "ok": r["ok"]} for r in rc], "wer": round(w, 3),
        }
        roles_cell = ("-" if not rc else f"{sum(r['ok'] for r in rc)}/{len(rc)}") if any_map or rc else "-"
        summary.append((sid, doc["lang"], f"{n_found}/{n_new}", f"{n_men}/{n_rep}", len(extra), f"{dia['found']}/{dia['expected']}" + (f" (known limitation {lim})" if lim else ""), roles_cell))
    md += ["| sitting | lang | new found | repeats as mention | extra | speakers found/expected | roles ok |", "|---|---|---|---|---|---|---|"]
    md += ["| " + " | ".join(str(x) for x in row) + " |" for row in summary] + [""]
    md += body
    # per client
    md += ["# What the app remembers, per client", ""]
    clients = defaultdict(list)
    for sid, doc in docs.items():
        clients[doc["client"]].append(doc)
    for cid, cdocs in clients.items():
        cdocs.sort(key=lambda d: d["date"])
        md += [f"## {cdocs[0]['client_name']} ({cid})", "", "Sittings: " + ", ".join(f"{d['id']} ({d['date']}, {d['tag']})" for d in cdocs), ""]
        cf = [f for f in facts_all if f["client"] == cid]
        order = {d["id"]: i for i, d in enumerate(cdocs)}
        cf.sort(key=lambda f: (order.get(f["session"], 99), f.get("timeMs") if f.get("timeMs") is not None else f.get("segIdx", 0)))
        planted_hit = {}
        for d in cdocs:
            if d["id"] in sessions:
                rows, _, _ = match_facts(d, d["id"], facts_all, planted_by_id)
                for r in rows:
                    if r["stored"] is not None and r["status"] in ("found", "duplicated"):
                        planted_hit[r["stored"]["id"]] = r
        if not cf:
            md += ["Nothing stored for this client.", ""]
        for f in cf:
            r = planted_hit.get(f["id"])
            who = f", said by {r['who']}" if r else ", not planted"
            again = f"; mentioned again in {', '.join(f['mentionSessions'])}" if f.get("mentionSessions") else ""
            md += [f"- {f['session']}: **{f['heading']}** - {one_line(f['content'], 220)} _({'planted' if r else 'extra'}{who.replace(', not planted', '') if r else ''}{again})_"]
        missed = [r for d in cdocs if d["id"] in sessions for r in match_facts(d, d["id"], facts_all, planted_by_id)[0] if r["status"] == "missing"]
        if missed:
            md += ["", "Planted but not remembered:", ""] + [f"- {r['id']} ({r['who']}): {r['heading']}" for r in missed]
        md.append("")
    return "\n".join(md), jrep


def load_docs(sessions_dir: Path, truth_dir: Path, only=None):
    docs, truths = {}, {}
    for p in sorted(sessions_dir.glob("*.json")):
        d = json.loads(p.read_text(encoding="utf-8"))
        if only and d["id"] not in only:
            continue
        docs[d["id"]] = d
        tp = truth_dir / f"{d['id']}.truth.json"
        if tp.exists():
            truths[d["id"]] = json.loads(tp.read_text(encoding="utf-8"))
    return docs, truths


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", required=True)
    ap.add_argument("--sessions", default=str(HERE / "sessions"))
    ap.add_argument("--truth", default=str(HERE / "audio"), help="folder with <id>.truth.json (turn timings) from render.py")
    ap.add_argument("--out", help="folder for report.md / report.json; default: next to results.json")
    ap.add_argument("--only")
    ap.add_argument("--title", default="Sittings report")
    ap.add_argument("--note", default="")
    a = ap.parse_args()
    docs, truths = load_docs(Path(a.sessions), Path(a.truth), a.only.split(",") if a.only else None)
    results = json.loads(Path(a.results).read_text(encoding="utf-8"))
    md, js = build(results, docs, truths, a.title, a.note)
    out = Path(a.out) if a.out else Path(a.results).parent
    out.mkdir(parents=True, exist_ok=True)
    (out / "report.md").write_text(md, encoding="utf-8")
    (out / "report.json").write_text(json.dumps(js, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"wrote {out / 'report.md'}")


if __name__ == "__main__":
    main()
