#!/usr/bin/env python3
"""Runs Gemma 4 E2B through the real LiteRT-LM runtime (the app's .litertlm file) on CPU: Czech writing prompts + one extraction.

Best effort: logs CLI help and every command so a failing flag is visible in the artifact.
"""
import json, subprocess, sys, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
out = Path(sys.argv[1] if len(sys.argv) > 1 else "out")
out.mkdir(parents=True, exist_ok=True)
cases = json.loads((ROOT / "bench" / "data" / "cases_cs.json").read_text(encoding="utf-8"))
V = sys.argv[2] if len(sys.argv) > 2 else "E2B"
REPO, FILE = f"litert-community/gemma-4-{V}-it-litert-lm", f"gemma-4-{V}-it.litertlm"
import tempfile
model = Path(tempfile.mkdtemp()) / FILE  # kept out of `out` so the artifact stays small

t0 = time.perf_counter()
urllib.request.urlretrieve(f"https://huggingface.co/{REPO}/resolve/main/{FILE}", model)
res = {"download_s": round(time.perf_counter() - t0, 1), "bytes": model.stat().st_size, "runs": []}


def sh(cmd, timeout=900):
    t = time.perf_counter()
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    return p.returncode, p.stdout, p.stderr, round(time.perf_counter() - t, 1)


for h in (["litert-lm", "--help"], ["litert-lm", "run", "--help"]):
    rc, o, e, _ = sh(h)
    (out / ("help_" + "_".join(h[1:]).replace("-", "") + ".txt")).write_text(o + "\n" + e, encoding="utf-8")

sys_p = (ROOT / "demo/prompts/extract.system.txt").read_text(encoding="utf-8").strip()
usr_t = (ROOT / "demo/prompts/extract.user.txt").read_text(encoding="utf-8").strip()
t = cases["transcripts"][0]
usr = usr_t.replace("{{CLIENT}}", t["client"]).replace("{{DATE}}", t["date"]).replace("{{PART}}", "1").replace("{{PARTS}}", "1").replace("{{TEXT}}", t["text"])
prompts = [("write%d" % i, p) for i, p in enumerate(cases["writing_prompts"])] + [("extract_petra", sys_p + "\n\n" + usr)]

for name, prompt in prompts:
    rc, o, e, secs = sh(["litert-lm", "run", str(model), "--backend=cpu", "--prompt=" + prompt])
    (out / f"{name}.txt").write_text(f"rc={rc} wall={secs}s\n--- stdout\n{o}\n--- stderr\n{e[-3000:]}", encoding="utf-8")
    res["runs"].append({"name": name, "rc": rc, "wall_s": secs, "chars": len(o)})
    print(name, rc, secs, flush=True)
(out / "litert_results.json").write_text(json.dumps(res, indent=1), encoding="utf-8")
