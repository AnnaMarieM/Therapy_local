#!/usr/bin/env python3
"""Aggregates results.json files from downloaded bench artifacts into markdown tables on stdout."""
import json, sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else "results")
rows = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(root.rglob("results.json"))]
rows.sort(key=lambda r: (r["model"], -(r.get("llama_bench", {}).get("decode_tok_s") or 0)))

print("## Speed (CPU, llama.cpp, Gemma 4 GGUF)\n")
print("| runner | model | quant | cpus/RAM | model download (s / MB/s) | load (s) | prefill tok/s | decode tok/s | peak RSS MB | status |")
print("|---|---|---|---|---|---|---|---|---|---|")
for r in rows:
    d, b, m = r.get("model_download", {}), r.get("llama_bench", {}), r["machine"]
    print(f"| {r['label']} | {r['model']} | {r['quant']} | {m['cpus']}/{m['ram_gb']} GB | {d.get('seconds')} / {d.get('mb_per_s')} | {r.get('load_seconds')} | "
          f"{b.get('prefill_tok_s')} | {b.get('decode_tok_s')} | {r.get('peak_rss_mb')} | {r.get('error') or b.get('error') or 'ok'} |")

q = [r for r in rows if "belebele" in r]
if q:
    print("\n## Czech quality\n")
    print("| runner | model | quant | Belebele-cs acc | review verdicts ok | extract facts (avg) | gold recall (avg) | evidence verbatim (unicode) | evidence passes app filter | writing loops | writing finish=length |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for r in q:
        ex, wr = r["extraction"], r["writing"]
        facts = sum(e["facts"] for e in ex) / len(ex)
        rec = sum(e["gold_recall"] for e in ex) / len(ex)
        vu = "/".join(str(sum(int(e["evidence_verbatim_unicode"].split("/")[i]) for e in ex)) for i in (0, 1))
        va = "/".join(str(sum(int(e["evidence_passes_app_filter"].split("/")[i]) for e in ex)) for i in (0, 1))
        print(f"| {r['label']} | {r['model']} | {r['quant']} | {r['belebele']['accuracy']} (letter {r['belebele']['answered_letter']}) | "
              f"{r['review']['correct_verdict']}/{r['review']['n']} | {facts:.1f} | {rec:.2f} | {vu} | {va} | "
              f"{sum(w['looping'] for w in wr)}/{len(wr)} | {sum(w['finish'] == 'length' for w in wr)}/{len(wr)} |")
