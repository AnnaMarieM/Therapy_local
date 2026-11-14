"""faster-whisper word timestamps -> per-turn start/end by aligning heard words to the script words (same difflib logic as common.oracle_turns,
but keeping the script TURN INDEX instead of only the speaker). Usage: align.py <words.json> <out.json>"""
import difflib, json, sys
sys.path.insert(0, "/home/martin/projects/Therapy_local/.claude/worktrees/frolicking-foraging-aurora/e2e/combo")
from common import script, _key

d = json.load(open(sys.argv[1]))
c = d["client"]
turns = script(c)["turns"]
ref = [(_key(w), i) for i, t in enumerate(turns) for w in t["text"].split() if _key(w)]
hyp = [(w["w"], _key(w["w"]), w) for w in d["words"] if _key(w["w"])]
sm = difflib.SequenceMatcher(None, [k for k, _ in ref], [k for _, k, _ in hyp], autojunk=False)
idx = [None] * len(hyp)
exact = [False] * len(hyp)
for op, i1, i2, j1, j2 in sm.get_opcodes():
    if op == "equal":
        for k in range(j2 - j1):
            idx[j1 + k], exact[j1 + k] = ref[i1 + k][1], True
    elif op == "replace":
        for k in range(j2 - j1):
            idx[j1 + k] = ref[i1 + (k * (i2 - i1)) // (j2 - j1)][1]
last = 0
for k in range(len(hyp)):
    if idx[k] is None:
        idx[k] = last
    last = idx[k]
# only exactly matched words define the boundaries (substituted words are only used for the text)
out = []
for i, t in enumerate(turns):
    ws = [hyp[k][2] for k in range(len(hyp)) if idx[k] == i and exact[k]]
    allw = [hyp[k][2] for k in range(len(hyp)) if idx[k] == i]
    out.append(dict(idx=i, who=t["who"], text=t["text"], start=ws[0]["s"] if ws else None, end=ws[-1]["e"] if ws else None, n_script_words=len(t["text"].split()),
                    n_exact=len(ws), heard=" ".join(w["w"].strip() for w in allw)))
# fill turns with no exact words from neighbours
for i, o in enumerate(out):
    if o["start"] is None:
        prev = next((out[j]["end"] for j in range(i - 1, -1, -1) if out[j]["end"] is not None), 0.0)
        nxt = next((out[j]["start"] for j in range(i + 1, len(out)) if out[j]["start"] is not None), prev)
        o["start"], o["end"], o["interpolated"] = prev, nxt, True
dur = d["duration"]
# cut points: the middle of the gap between turns, but never more than 0.35 s from either word edge
for i, o in enumerate(out):
    p = out[i - 1]["end"] if i else 0.0
    n = out[i + 1]["start"] if i + 1 < len(out) else dur
    o["cut_start"] = round(max(0.0, o["start"] - min(0.35, max(0.0, o["start"] - p) / 2)), 2)
    o["cut_end"] = round(min(dur, o["end"] + min(0.35, max(0.0, n - o["end"]) / 2)), 2)
    o["start"], o["end"] = round(o["start"], 2), round(o["end"], 2)
res = dict(client=c, lang=script(c)["lang"], duration=dur, method="faster-whisper large-v3 int8_float16 beam 5 word timestamps, words aligned to the script (difflib on folded keys, exact matches set the boundaries)",
           n_turns=len(out), n_interpolated=sum(1 for o in out if o.get("interpolated")), turns=out)
json.dump(res, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=1)
over = [o for o in out if o["cut_end"] - o["cut_start"] > 30]
print(c, len(out), "turns", res["n_interpolated"], "interpolated", len(over), "pieces >30 s", "max", max(o["cut_end"] - o["cut_start"] for o in out))
