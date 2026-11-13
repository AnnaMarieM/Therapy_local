import json, urllib.request, sys
d = json.load(urllib.request.urlopen("https://huggingface.co/api/models/ggerganov/whisper.cpp/tree/main?recursive=false&expand=true"))
for f in d:
    if f["path"] in ("ggml-large-v3-q8_0.bin", "ggml-large-v3-turbo-q8_0.bin", "ggml-large-v3-q5_0.bin"):
        print(f["path"], f["size"], (f.get("lfs") or {}).get("oid"))
s = json.load(open("/home/martin/projects/Therapy_local/.claude/worktrees/frolicking-foraging-aurora/e2e/corpus/human/tomas-b/s01.json", encoding="utf-8"))
words = 0
tot = sum(len(t["text"].split()) for t in s["turns"])
for i, t in enumerate(s["turns"]):
    n = len(t["text"].split())
    if "ublíž" in t["text"] or "ublíz" in t["text"]:
        print("TURN", i, t["who"], "words before", words, "of", tot, "->", t["text"])
    words += n
print("total words", tot, "turns", len(s["turns"]))
