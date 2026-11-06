#!/usr/bin/env python3
"""Extracts N random clips of VoxPopuli Czech test (European Parliament speeches, CC0, human-verified transcripts) as 16 kHz mono 16-bit
wav + reference txt, one clip per speaker. Needs pyarrow. Public speech only: this is what the CI benchmark may use besides FLEURS."""
import argparse, os, random, subprocess, sys
import pyarrow.parquet as pq

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fleurs import float_wav_to_pcm16  # noqa: E402  (the converter lives next to the FLEURS fetcher)

URL = "https://huggingface.co/api/datasets/facebook/voxpopuli/parquet/cs/test/0.parquet"
a = argparse.ArgumentParser(); a.add_argument("--count", type=int, required=True); a.add_argument("--out", required=True)
args = a.parse_args()
os.makedirs(args.out, exist_ok=True)
fetch = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "fetch.sh")
subprocess.run([fetch, URL, "voxpopuli-test.parquet"], check=True)
rows = [r for r in pq.read_table("voxpopuli-test.parquet", columns=["audio", "raw_text", "is_gold_transcript", "speaker_id"]).to_pylist() if r["is_gold_transcript"]]
random.Random(5).shuffle(rows)
seen, done = set(), 0
for r in rows:
    if r["speaker_id"] in seen:
        continue
    seen.add(r["speaker_id"])
    dst = os.path.join(args.out, f"cs_vox_{done:03d}")
    float_wav_to_pcm16(r["audio"]["bytes"], dst + ".wav")
    open(dst + ".txt", "w", encoding="utf-8").write(r["raw_text"])
    done += 1
    if done == args.count:
        break
print(f"{done} clips in {args.out}")
