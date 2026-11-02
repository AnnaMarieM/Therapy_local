#!/usr/bin/env python3
"""Downloads N random clips of FLEURS cs_cz test into OUT as 16 kHz mono wav + reference txt. Public speech only.
Needs ffmpeg (present on GitHub's Ubuntu runners). Resumable and retrying like qa/fetch.sh."""
import argparse, csv, os, random, subprocess, tarfile
BASE = "https://huggingface.co/datasets/google/fleurs/resolve/main/data/cs_cz"
a = argparse.ArgumentParser(); a.add_argument("--count", type=int, required=True); a.add_argument("--out", required=True)
args = a.parse_args()
os.makedirs(args.out, exist_ok=True)
here = os.path.dirname(os.path.abspath(__file__))
fetch = os.path.join(here, "..", "fetch.sh")
for url, out in ((f"{BASE}/test.tsv", "test.tsv"), (f"{BASE}/audio/test.tar.gz", "test.tar.gz")):
    subprocess.run([fetch, url, out], check=True)
rows = list(csv.reader(open("test.tsv", encoding="utf-8"), delimiter="\t"))
random.Random(11).shuffle(rows)
want, seen = {}, set()
for r in rows:
    if r[1] not in seen:
        seen.add(r[1]); want[r[1]] = r[2]
    if len(want) == args.count:
        break
done = 0
with tarfile.open("test.tar.gz", "r:gz") as t:
    for m in t:
        b = os.path.basename(m.name)
        if b in want:
            src = os.path.join(args.out, b)
            open(src, "wb").write(t.extractfile(m).read())
            dst = os.path.join(args.out, f"cs_fleurs_{done:03d}")
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", src, "-ar", "16000", "-ac", "1", "-sample_fmt", "s16", dst + ".wav"], check=True)
            open(dst + ".txt", "w", encoding="utf-8").write(want[b]); os.remove(src); done += 1
            if done == len(want):
                break
print(f"{done} clips in {args.out}")
