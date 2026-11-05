#!/usr/bin/env python3
"""Summarises experiment artifacts (results.json with a 'name') into markdown tables."""
import json, sys
from collections import defaultdict
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else "results")
rows = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(root.rglob("results.json"))]
rows = [r for r in rows if "writing" in r]


def mean(xs):
    xs = [x for x in xs if x is not None]
    return round(sum(xs) / len(xs), 3) if xs else None


print("## Experiments (ubuntu x64, llama.cpp)\n")
print("| experiment | prompts | Belebele | verdicts | facts | facts in English | extract unknown-word rate | write unknown-word rate (per temp) | loops | cut off | decode tok/s |")
print("|---|---|---|---|---|---|---|---|---|---|---|")
for r in sorted(rows, key=lambda r: r["name"]):
    ex, wr = r["extraction"], r["writing"]
    by = defaultdict(list)
    for w in wr:
        by[w["temp"]].append(w["unknown_word_rate"])
    temps = ", ".join(f"{t}: {mean(v)}" for t, v in sorted(by.items()))
    print(f"| {r['name']} | {r['prompts'].split('/')[-1]} | {r['belebele']['accuracy']} | {r['review']['correct_verdict']}/{r['review']['n']} | "
          f"{mean([e['facts'] for e in ex])} | {mean([e['facts_in_english_share'] for e in ex])} | {mean([e['content_unknown_word_rate'] for e in ex])} | {temps} | "
          f"{sum(w['looping'] for w in wr)}/{len(wr)} | {sum(w['finish'] == 'length' for w in wr)}/{len(wr)} | {r['llama_bench'].get('decode_tok_s')} |")
