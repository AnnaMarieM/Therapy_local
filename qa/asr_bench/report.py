#!/usr/bin/env python3
"""Merges the result JSON files of bench.py into markdown: one table per dataset and an overall ranking by average WER."""
import collections, glob, json, sys

rows = [json.load(open(f, encoding="utf-8")) for f in glob.glob(sys.argv[1] + "/*.json")]
if not rows:
    sys.exit("no results: every model failed, see the bench jobs")
by = collections.defaultdict(dict)
for r in rows:
    by[r.get("dataset") or "clips"][r["model"]] = r

datasets = sorted(by)
models = sorted({m for d in by.values() for m in d})
avg = {m: sum(by[d][m]["wer"] for d in datasets if m in by[d]) / max(1, sum(1 for d in datasets if m in by[d])) for m in models}
speed = {m: sum(by[d][m]["rtf"] for d in datasets if m in by[d]) / max(1, sum(1 for d in datasets if m in by[d])) for m in models}
labels = {"fleurs": "FLEURS (read speech)", "voxpopuli": "VoxPopuli (parliament speeches)"}

print("### Czech speech recognition: word error rate (lower is better)\n")
print("| Model | " + " | ".join(labels.get(d, d) for d in datasets) + " | Average | Speed (CPU, x real time) |")
print("|---|" + "---:|" * (len(datasets) + 2))
for m in sorted(models, key=lambda m: avg[m]):
    cells = " | ".join(f"{by[d][m]['wer']:.1f}%" if m in by[d] else "-" for d in datasets)
    print(f"| {m} | {cells} | **{avg[m]:.1f}%** | {speed[m]:.2f} |")
for d in datasets:
    sample = next(iter(by[d].values()))
    print(f"\n{labels.get(d, d)}: {sample['clips']} clips, {sample['words']} words.")
best = min(models, key=lambda m: avg[m])
for d in datasets:
    if best in by[d]:
        worst = sorted(by[d][best]["details"], key=lambda c: -c["wer"])[:2]
        print(f"\nHardest {d} clips for {best}:\n")
        for c in worst:
            print(f"- `{c['clip']}` {c['wer']}%\n  - ref: {c['ref']}\n  - hyp: {c['hyp']}")
print("\nSpeed is on a shared 4-vCPU runner: compare models with each other, not with the phone.")
