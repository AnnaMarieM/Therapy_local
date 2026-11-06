import json, random, sys, unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import scorer as S


def oracle_results(noise=0.0, seed=1):
    """A run in which the app was perfect (noise 0) or the transcripts lost `noise` of their words."""
    truth, clients = S.load_truth()
    rnd = random.Random(seed)
    sessions, facts, answers = [], [], []
    by_orig = {}
    fid = 0
    for key, d in truth.items():
        tr = []
        for t in d["turns"]:
            words = t["text"].split()
            tr.append(" ".join(w for w in words if rnd.random() >= noise))
        sessions.append({"client": d["client"], "session": key, "transcript": tr})
    for key, d in truth.items():
        for f in d["expected"]["facts"]:
            if f["repeat_of"] is None:
                fid += 1
                row = {"id": fid, "client": d["client"], "session": key, "heading": f["heading"], "content": f["said"], "evidence": " ".join(f["said"].split()[:12]), "mentionSessions": []}
                facts.append(row); by_orig[f"{d['client']}.{f['id']}"] = row
    for key, d in truth.items():
        for f in d["expected"]["facts"]:
            if f["repeat_of"] is not None:
                by_orig[f"{d['client']}.{f['repeat_of']}"]["mentionSessions"].append(key)
    said = {f"{d['client']}.{f['id']}": (f["said"], key) for key, d in truth.items() for f in d["expected"]["facts"]}
    for cid, c in clients.items():
        for q in c["queries"]:
            fs = [{"id": 0, "session": said[f"{cid}.{e}"][1], "heading": "h", "content": said[f"{cid}.{e}"][0], "score": 0.8} for e in q["expect"]]
            answers.append({"client": cid, "q": q["q"], "windows": [], "facts": fs})
        for n in c["negatives"]:
            answers.append({"client": cid, "q": n, "windows": [], "facts": [{"id": 0, "session": f"{cid}_s01", "heading": "x", "content": "unrelated", "score": 0.2}]})
    return {"sessions": sessions, "facts": facts, "answers": answers}


class ScoreTests(unittest.TestCase):
    def test_oracle_is_perfect(self):
        rep = S.score(oracle_results())
        for lang in ("cs", "en"):
            m = rep["by_lang"][lang]
            self.assertEqual(m["sessions"], 50)
            self.assertLess(m["wer"], 0.001)
            self.assertEqual(m["fact_recall_pct"], 100.0)
            self.assertEqual(m["repeat_dedupe_pct"], 100.0)
            self.assertEqual(m["query_fact_hit@1_pct"], 100.0)
            self.assertEqual(m["cross_client_leaks"], 0)

    def test_noise_raises_wer(self):
        rep = S.score(oracle_results(noise=0.2))
        self.assertGreater(rep["by_lang"]["en"]["wer"], 0.1)

    def test_leak_is_flagged(self):
        r = oracle_results()
        r["answers"][0]["facts"][0]["session"] = "marie-d_s01" if r["answers"][0]["client"] != "marie-d" else "daniel-r_s01"
        rep = S.score(r)
        self.assertTrue(any(v["cross_client_leaks"] for v in rep["by_lang"].values()))

    def test_diacritics_fold(self):
        self.assertEqual(S.tokens("Špatně jsem spala"), ["spatne", "jsem", "spala"])
        self.assertEqual(S.wer("žena", "zena"), 0.0)

    def test_missing_dedupe_detected(self):
        r = oracle_results()
        for f in r["facts"]:
            f["mentionSessions"] = []
        rep = S.score(r)
        self.assertEqual(rep["by_lang"]["cs"]["repeat_dedupe_pct"], 0.0)


if __name__ == "__main__":
    unittest.main()
