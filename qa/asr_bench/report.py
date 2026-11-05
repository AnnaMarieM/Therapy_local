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
full = [m for m in models if all(m in by[d] for d in datasets)]  # only a model scored on every dataset gets an average, so the ranking is like for like
avg = {m: sum(by[d][m]["wer"] for d in datasets) / len(datasets) for m in full}
speed = {m: sum(by[d][m]["rtf"] for d in datasets if m in by[d]) / sum(1 for d in datasets if m in by[d]) for m in models}
labels = {"fleurs": "FLEURS (read speech)", "voxpopuli": "VoxPopuli (parliament speeches)"}

print("### Czech speech recognition: word error rate (lower is better)\n")
print("| Model | " + " | ".join(labels.get(d, d) for d in datasets) + " | Average | Speed (CPU, x real time) |")
print("|---|" + "---:|" * (len(datasets) + 2))
for m in sorted(models, key=lambda m: (m not in avg, avg.get(m, 0), m)):
    cells = " | ".join(f"{by[d][m]['wer']:.1f}%" if m in by[d] else "-" for d in datasets)
    print(f"| {m} | {cells} | {f"**{avg[m]:.1f}%**" if m in avg else "-"} | {speed[m]:.2f} |")
for d in datasets:
    sample = next(iter(by[d].values()))
    print(f"\n{labels.get(d, d)}: {sample['clips']} clips, {sample['words']} words.")
best = min(full, key=lambda m: avg[m]) if full else models[0]
for d in datasets:
    if best in by[d]:
        worst = sorted(by[d][best]["details"], key=lambda c: -c["wer"])[:2]
        print(f"\nHardest {d} clips for {best}:\n")
        for c in worst:
            print(f"- `{c['clip']}` {c['wer']}%\n  - ref: {c['ref']}\n  - hyp: {c['hyp']}")
print("\nSpeed is on a shared 4-vCPU runner: compare models with each other, not with the phone.")
