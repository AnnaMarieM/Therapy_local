import sys
sys.path.insert(0, "/home/martin/projects/Therapy_local/.claude/worktrees/frolicking-foraging-aurora/research/asr-llm-combo/final-workflow")
import score as S, report_t4 as R
from common import script, wer
for p in sys.argv[1:]:
    for c in S.CLIENTS:
        cs, en = R.texts(p, c)
        if en is None and cs is None:
            continue
        if en:
            ch = S.check(c, en, "en")
            sc = script(c)
            if sc["lang"] == "cs":
                r = " ".join(S.reference_en(c)); w = f"WERref {wer(r, en, 'en'):.3f} chrF {S.chrf(en, r):.1f}"
            else:
                w = f"WER {wer(' '.join(t['text'] for t in sc['turns']), en, 'en'):.3f}"
            print(p, c, "EN", S.summarize(ch), w, {k: v for k, v in ch.items() if k in ("T10", "J13", "D4", "T9", "T8")})
        if cs:
            ch = S.check(c, cs, "cs")
            sc = script(c); ref = " ".join(t["text"] for t in sc["turns"])
            print(p, c, "CS", S.summarize(ch), f"WER {wer(ref, cs, 'cs'):.3f}", {k: v for k, v in ch.items() if k in ("T10", "J13", "T9", "T8")})
