#!/usr/bin/env python3
"""Merges the result JSON files of bench.py into one markdown table, best (lowest) word error rate first."""
import glob, json, sys

rows = [json.load(open(f, encoding="utf-8")) for f in glob.glob(sys.argv[1] + "/*.json")]
if not rows:
    sys.exit("no results: every model failed, see the bench jobs")
rows.sort(key=lambda r: r["wer"])
print("### Czech speech recognition (Google FLEURS cs_cz)\n")
print("| Model | WER | Speed (CPU, x real time) | Clips | Words |")
print("|---|---:|---:|---:|---:|")
for r in rows:
    print(f"| {r['model']} | **{r['wer']:.1f}%** | {r['rtf']:.2f} | {r['clips']} | {r['words']} |")
if rows:
    worst = sorted(rows[0]["details"], key=lambda c: -c["wer"])[:3]
    print(f"\nHardest clips for {rows[0]['model']}:\n")
    for c in worst:
        print(f"- `{c['clip']}` {c['wer']}%\n  - ref: {c['ref']}\n  - hyp: {c['hyp']}")
print("\nSpeed is on a shared 4-vCPU runner: compare models with each other, not with the phone.")
