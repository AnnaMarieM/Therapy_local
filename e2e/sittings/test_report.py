#!/usr/bin/env python3
"""report.py without a device: an oracle results.json that echoes the truth must report everything found, every role right, 100% attribution.
Run: python3 e2e/sittings/test_report.py   (needs only the compiled sessions/*.json; turn timings are made up here, audio is not needed)"""
import json, sys, tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import report  # noqa: E402


def fake_truth(doc):
    t, turns = 500, []
    for i, tu in enumerate(doc["turns"]):
        dur = 400 + 330 * len(tu["text"].split())
        turns.append({"idx": i, "speaker": tu["who"], "start_ms": t, "end_ms": t + dur, "text": tu["text"]})
        t += dur + 400
    return {"id": doc["id"], "duration_ms": t, "expect": doc["expect"], "turns": turns, "facts": doc["facts"]}


def oracle(docs, truths, diarized=True):
    sessions, facts, fid = [], [], 0
    stored = {}
    for sid, doc in docs.items():
        tr = truths[sid]
        one = doc["expect"]["speakers"] == 1
        sp = {"therapist": "S0", "patient": "S1"}
        segs = [{"idx": i + 1, "startMs": t["start_ms"], "endMs": t["end_ms"], "speaker": (None if (one or not diarized) else sp[t["speaker"]]), "text": t["text"]}
                for i, t in enumerate(tr["turns"])]
        smap = None if (one or not diarized) else {"version": 1, "map": {"S0": "therapist", "S1": "patient"}, "source": "llm", "confidence": 0.9, "evidence": ["the therapist asks, the patient answers"]}
        sessions.append({"client": doc["client"], "session": sid, "transcript": [s["text"] for s in segs], "segments": segs, "speakerMap": smap})
        for pf in doc["facts"]:
            seg = tr["turns"][pf["turn"]]
            if pf["repeat_of"] is None:
                fid += 1
                f = {"id": fid, "client": doc["client"], "session": sid, "heading": pf["heading"], "content": pf["said"], "evidence": pf["said"],
                     "segIdx": pf["turn"] + 1, "timeMs": seg["start_ms"], "mentionSessions": []}
                facts.append(f); stored[pf["id"]] = f
    for sid, doc in docs.items():
        for pf in doc["facts"]:
            if pf["repeat_of"]:
                stored[pf["repeat_of"]]["mentionSessions"].append(sid)
    return {"sessions": sessions, "facts": facts, "answers": []}


def main():
    docs, _ = report.load_docs(HERE / "sessions", HERE / "nonexistent")
    assert docs, "run build.py first"
    truths = {sid: fake_truth(d) for sid, d in docs.items()}
    md, js = report.build(oracle(docs, truths), docs, truths, "oracle", "")
    for sid, d in docs.items():
        s = js["sittings"][sid]
        new = sum(1 for f in d["facts"] if not f["repeat_of"])
        rep = sum(1 for f in d["facts"] if f["repeat_of"])
        assert s["found"] == new and s["mentions"] == rep and s["extra"] == 0 and not s["missing"], (sid, s)
        assert s["wer"] == 0, (sid, s)
        one = d["expect"]["speakers"] == 1
        if one:
            assert s["speakers_found"] == 0, (sid, s)
        else:
            assert s["speakers_found"] == 2 and s["word_attribution_pct"] == 100.0, (sid, s)
            assert all(r["ok"] for r in s["roles"]) and len(s["roles"]) == 2, (sid, s)
    assert "[00:00:00]\nTherapist:" in md or "[00:00:0" in md
    assert "Patient:" in md and "# What the app remembers, per client" in md
    # a baseline results.json (plain transcript lines, no speakers, no map) must still produce a report
    base = oracle(docs, truths, diarized=False)
    for s in base["sessions"]:
        del s["segments"]; del s["speakerMap"]
    for f in base["facts"]:
        f.pop("timeMs"); f.pop("segIdx")
    md2, js2 = report.build(base, docs, truths, "baseline", "")
    assert "pre-PR-1 baseline" in md2 and all(v["speakers_found"] == 0 for v in js2["sittings"].values())
    # a wrong role and a split speaker are caught; a missing session does not crash
    bad = oracle(docs, truths)
    for s in bad["sessions"]:
        if s["speakerMap"]:
            s["speakerMap"]["map"] = {"S0": "patient", "S1": "therapist"}
    _, js3 = report.build(bad, docs, truths, "swapped", "")
    assert not js3["sittings"]["petr-k_s01"]["roles"][0]["ok"]
    bad["sessions"] = bad["sessions"][1:]
    _, js4 = report.build(bad, docs, truths, "partial", "")
    assert js4["sittings"][bad and sorted(docs)[0]]["present"] is False
    with tempfile.TemporaryDirectory() as d:
        (Path(d) / "r.json").write_text(json.dumps(oracle(docs, truths)))
        sys.argv = ["report.py", "--results", str(Path(d) / "r.json"), "--truth", str(Path(d) / "none")]
        report.main()
        assert (Path(d) / "report.md").exists() and (Path(d) / "report.json").exists()
    print("ok: oracle reports 100%, baseline and partial results handled")


if __name__ == "__main__":
    main()
