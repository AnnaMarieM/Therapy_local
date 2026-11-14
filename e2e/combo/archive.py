#!/usr/bin/env python3
"""Files raw experiment output into research/asr-llm-combo/ in a fixed nested layout, so nothing lives only in a scratchpad or a CI artifact.

  archive.py --kind asr  --platform ci-cpu|t4-gpu --src DIR [--round r2a]      DIR holds ASR result jsons (CI: asr.py output; T4: {client, variant, text, wer, ...})
  archive.py --kind llm  --platform ci-cpu       --src DIR [--round r2b]      DIR holds llm.py result jsons

Layout (everything is the RAW output; WER is recomputed by report.py, never trusted from here):
  transcripts/<platform>/<lang>/<variant>/<client>[__laneN].txt     the raw transcript text as produced
  transcripts/<platform>/<lang>/<variant>/<client>[__laneN].json    the result json (segments, wer, options)
  facts/<platform>/<lang>/<asr>__<model+tag>/<client>__laneN.md      readable facts (heading, content, evidence, quote found)
  facts/<platform>/<lang>/<asr>__<model+tag>/<client>__laneN.turns.txt   the labelled turns the LLM was given (after roles/pivot)
  facts/<platform>/<lang>/<asr>__<model+tag>/<client>__laneN.json   result json without the turns
"""
import argparse, json, shutil, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from common import script

ROOT = Path(__file__).resolve().parents[2] / "research" / "asr-llm-combo"


def put(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def asr(src, platform, rnd):
    n = 0
    for f in sorted(Path(src).rglob("*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        client = d.get("client")
        if not client:
            continue
        lang = d.get("lang") or script(client)["lang"]
        variant = d.get("engine") or d.get("variant")
        lane = d.get("lane")
        stem = f"{client}" + (f"__lane{lane}" if lane else "")
        text = d["text"] if "text" in d else " ".join(s["text"] for s in d["segments"])
        base = ROOT / "transcripts" / platform / lang / variant
        put(base / f"{stem}.txt", text.strip() + "\n")
        d["round"] = rnd or d.get("round", "")
        put(base / f"{stem}.json", json.dumps(d, ensure_ascii=False, indent=1))
        n += 1
    print(n, "asr results filed under", ROOT / "transcripts" / platform)


def llm(src, platform, rnd):
    n = 0
    for f in sorted(Path(src).rglob("*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        if "clients" not in d:
            continue
        for client, c in d["clients"].items():
            lang = c.get("lang") or script(client)["lang"]
            base = ROOT / "facts" / platform / lang / f"{d['engine']}__{d['model']}"
            stem = f"{client}__lane{d['lane']}"
            md = [f"# {d['engine']} + {d['model']}, {client}, lane {d['lane']} ({len(c['facts'])} facts, roles: {c.get('roles', {}).get('roles')})", ""]
            for i, x in enumerate(c["facts"], 1):
                md += [f"{i}. **{x.get('heading', '')}**" + ("" if x.get("quote_found", True) else "  [quote NOT in text]"), f"   {x.get('content', '')}", f"   > {x.get('evidence', '')}", ""]
            put(base / f"{stem}.md", "\n".join(md))
            put(base / f"{stem}.turns.txt", "\n".join(f"{t['who'] or 'Speaker 1'}: {t['text']}" for t in c.get("turns", [])) + "\n")
            slim = {k: v for k, v in c.items() if k != "turns"}
            put(base / f"{stem}.json", json.dumps({"round": rnd, **{k: v for k, v in d.items() if k != "clients"}, "client": client, **slim}, ensure_ascii=False, indent=1))
            n += 1
    print(n, "llm results filed under", ROOT / "facts" / platform)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--kind", required=True, choices=["asr", "llm"])
    ap.add_argument("--platform", required=True)
    ap.add_argument("--src", required=True)
    ap.add_argument("--round", default="")
    a = ap.parse_args()
    (asr if a.kind == "asr" else llm)(a.src, a.platform, a.round)
