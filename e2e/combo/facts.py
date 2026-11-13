#!/usr/bin/env python3
"""Reading aid: print the facts one combo extracted for one recording.

  facts.py --llm DIR --asr whisper-large-v3 --model gemma4-e4b --client jana-n [--lane 1]
"""
import argparse, json
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--llm", required=True)
    ap.add_argument("--asr", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--client", required=True)
    ap.add_argument("--lane", type=int, default=1)
    a = ap.parse_args()
    f = next(Path(a.llm).rglob(f"{a.asr}__{a.model}__lane{a.lane}.json"))
    r = json.loads(f.read_text(encoding="utf-8"))["clients"][a.client]
    print(f"{len(r['facts'])} facts, {r['seconds']} s")
    for i, x in enumerate(r["facts"], 1):
        print(f"{i:2}. {'' if x['quote_found'] else '[QUOTE NOT IN TEXT] '}{x['heading']} ({x.get('scope','')}, {x.get('category','')}): {x['content']}\n      \"{x.get('evidence','')}\"")


if __name__ == "__main__":
    main()
