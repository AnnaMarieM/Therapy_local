#!/usr/bin/env python3
"""Expands a round spec (e2e/combo/rounds/<name>.json) into the two job matrices of bench-combo.yml (written to $GITHUB_OUTPUT as asr= and llm=).

Spec:
  {"round": "r2", "lanes": 4,
   "asr": [{"id": "large-v3-beam5", "engine": "whisper-large-v3", "opts": {"beam_size": 5}, "clients": ["jana-n", ...]}],   # clients optional
   "llm": [{"asr": "large-v3-beam5", "model": "gemma4-e4b", "tag": "", "opts": {"part_words": 300}}]}                  # asr "script" = oracle
`opts` are passed through as JSON; see asr.py / llm.py for the keys.
"""
import json, os, sys
from pathlib import Path
CLIENTS = ["jana-n", "tomas-b", "daniel-r", "hannah-w"]


def expand(spec):
    lanes = list(range(1, spec.get("lanes", 4) + 1))
    asr = [{"id": a["id"], "engine": a["engine"], "opts": json.dumps(a.get("opts", {})), "client": c, "lane": l}
           for a in spec.get("asr", []) for c in a.get("clients", CLIENTS) for l in lanes]
    llm = [{"asr": m["asr"], "model": m["model"], "tag": m.get("tag", ""), "opts": json.dumps(m.get("opts", {})), "lane": l, "round": spec["round"]}
           for m in spec.get("llm", []) for l in lanes]
    return {"include": asr}, {"include": llm}


if __name__ == "__main__":
    a, l = expand(json.loads(Path(sys.argv[1]).read_text()))
    line = f"asr={json.dumps(a)}\nllm={json.dumps(l)}\n"
    if os.environ.get("GITHUB_OUTPUT"):
        open(os.environ["GITHUB_OUTPUT"], "a").write(line)
    else:
        print(len(a["include"]), "asr jobs,", len(l["include"]), "llm jobs")
