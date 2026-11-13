"""WER of an offset chunk: the reference is the script window (start in a search range, length near the hypothesis length) with the lowest WER."""
import sys
sys.path.insert(0, "/home/martin/projects/Therapy_local/.claude/worktrees/frolicking-foraging-aurora/e2e")
from combo.common import wer, script

client, lo, hi = "tomas-b", int(sys.argv[1]), int(sys.argv[2])
ref_words = " ".join(t["text"] for t in script(client)["turns"]).split()
for f in sys.argv[3:]:
    hyp = open(f, encoding="utf-8").read().strip()
    n = len(hyp.split())
    best = min(((wer(" ".join(ref_words[s:s + k]), hyp, "cs"), s, k)
                for s in range(lo, hi) for k in range(max(5, n - 10), n + 11, 2)))
    print(f.split("/")[-1], "hyp_words", n, "ref_start", best[1], "ref_len", best[2], "WER %.3f" % best[0])
