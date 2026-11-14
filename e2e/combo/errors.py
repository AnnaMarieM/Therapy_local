#!/usr/bin/env python3
"""Reading aid: the words an ASR variant got wrong, in context.

  errors.py --asr DIR --id whisper-large-v3 --client jana-n [--lane 1] [--max 60]
Aligns the hypothesis to the script word by word (diacritics and punctuation folded, like the WER) and prints every substitution/deletion/insertion
with a few words of script context, so a person can see WHAT kind of mistakes it makes (names, diacritics, endings, dropped sentences) instead of one number.
"""
import argparse, difflib, json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from common import script, norm_words


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--asr", required=True)
    ap.add_argument("--id", required=True)
    ap.add_argument("--client", required=True)
    ap.add_argument("--lane", type=int, default=1)
    ap.add_argument("--max", type=int, default=80)
    a = ap.parse_args()
    f = next(Path(a.asr).rglob(f"{a.id}__{a.client}__lane{a.lane}.json"))
    d = json.loads(f.read_text(encoding="utf-8"))
    lang = d["lang"]
    ref = norm_words(" ".join(t["text"] for t in script(a.client)["turns"]), lang)
    hyp = norm_words(" ".join(s["text"] for s in d["segments"]), lang)
    sm = difflib.SequenceMatcher(None, ref, hyp, autojunk=False)
    n = 0
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op == "equal":
            continue
        n += 1
        if n <= a.max:
            ctx = " ".join(ref[max(0, i1 - 4):i1])
            print(f"{op:8} ...{ctx} [{' '.join(ref[i1:i2])}] -> [{' '.join(hyp[j1:j2])}]")
    print(f"-- {n} differences, WER {d['wer']}, {len(ref)} script words, {len(hyp)} heard")


if __name__ == "__main__":
    main()
