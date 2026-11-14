import sys
sys.path.insert(0, "/home/martin/projects/Therapy_local/.claude/worktrees/frolicking-foraging-aurora/e2e")
from combo.common import wer, script

client = {"cs": "tomas-b", "en": "daniel-r"}[sys.argv[1]]
ref_words = " ".join(t["text"] for t in script(client)["turns"]).split()
for f in sys.argv[2:]:
    hyp = open(f, encoding="utf-8").read().strip()
    n = len(hyp.split())
    best = min(range(max(5, n - 12), n + 13), key=lambda k: wer(" ".join(ref_words[:k]), hyp, sys.argv[1]))
    print(f.split("/")[-1], "hyp_words", n, "ref_prefix_words", best, "WER %.3f" % wer(" ".join(ref_words[:best]), hyp, sys.argv[1]))
