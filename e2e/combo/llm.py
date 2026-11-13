#!/usr/bin/env python3
"""One LLM cell: (ASR transcripts of one engine | the script as read) x model x lane -> extracted facts for all four recordings.

  llm.py --asr-dir asr/ --engine canary-1b-v2 --model gemma4-e4b --lane 1 [--tag x --opts '{"part_words":300}'] --out out     (--engine script = oracle, perfect ASR)
opts (all optional): part_words (450)  temperature (0.3)  max_tokens (2000)  ctx (8192)
  prompt_dir  folder with extract.system.txt / extract.user.txt, or {"cs": dir, "en": dir}; default demo/prompts (cs/ for Czech)
  pivot       true = Czech transcripts are first translated to English by the same model (AD-17), then the English prompts run
Same extraction prompts as the app (demo/prompts, cs/ for Czech clients), llama.cpp server, Q4_K_M, temperature 0.3 with seed = lane,
so the lanes differ only by sampling and show how flaky a combo is. Roles are not known here, so turns are "Speaker 1:" lines.
"""
import argparse, json, re, sys, time, urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "bench"))
sys.path.insert(0, str(Path(__file__).parent))
import bench  # noqa: E402
from common import CLIENTS, script, norm_words  # noqa: E402

PROMPTS = ROOT / "demo" / "prompts"
PART_WORDS = 450


def lines_for(engine, client, asr_dir, lane):
    if engine == "script":
        return [t["text"] for t in script(client)["turns"]]
    f = Path(asr_dir) / f"{engine}__{client}__lane{lane}.json"
    if not f.exists():  # ASR is deterministic: any lane's transcript is the same
        f = next(Path(asr_dir).glob(f"{engine}__{client}__lane*.json"))
    return [s["text"] for s in json.loads(f.read_text(encoding="utf-8"))["segments"] if s["text"]]


def parts(lines, part_words=PART_WORDS):
    out, cur, n = [], [], 0
    for i, l in enumerate(lines):
        cur.append(f"[00:{i // 60:02d}:{i % 60:02d}]\nSpeaker 1: {l}")
        n += len(l.split())
        if n >= part_words:
            out.append("\n".join(cur)); cur, n = [], 0
    if cur:
        out.append("\n".join(cur))
    return out


def chat(system, user, seed, temperature=0.3, max_tokens=2000):
    body = {"messages": [{"role": "system", "content": system}, {"role": "user", "content": user}], "temperature": temperature, "seed": seed, "max_tokens": max_tokens,
            "chat_template_kwargs": {"enable_thinking": False}}
    req = urllib.request.Request(f"http://127.0.0.1:{bench.PORT}/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=1800) as r:
        return json.loads(r.read())["choices"][0]["message"]["content"] or ""


def translate_lines(lines, seed, o):
    """AD-17 pivot: Czech lines -> English lines, 12 lines per call, numbered so the count can be checked."""
    sysp = ("You translate a Czech therapy-session transcript into English. Translate every numbered line faithfully, keep names, numbers and meaning, "
            "do not summarise or add anything. Output the same numbered lines, nothing else.")
    out = []
    for i in range(0, len(lines), 12):
        chunk = lines[i:i + 12]
        raw = chat(sysp, "\n".join(f"{j + 1}. {l}" for j, l in enumerate(chunk)), seed, 0.0, o.get("max_tokens", 2000))
        got = {}
        for l in raw.splitlines():
            m = re.match(r"\s*(\d+)[.)]\s*(.*)", l)
            if m:
                got[int(m.group(1))] = m.group(2).strip()
        out += [got.get(j + 1, chunk[j]) for j in range(len(chunk))]
    return out


def parse(text):
    facts = []
    for l in text.splitlines():
        l = l.strip().strip("`")
        if l.startswith("{"):
            try:
                d = json.loads(l)
                if d.get("heading") and d.get("content"):
                    facts.append(d)
            except Exception:
                pass
    return facts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--asr-dir", default="asr")
    ap.add_argument("--engine", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--tag", default="", help="label of this prompt/option variant, appended to the model name in the report")
    ap.add_argument("--opts", default="{}")
    ap.add_argument("--lane", type=int, default=1)
    ap.add_argument("--llama-build", default="b11960")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    bindir, _ = bench.fetch_llama(a.llama_build, "ubuntu-x64", out)
    repo, fname = bench.model_source(a.model, "Q4_K_M")
    gguf = out / "model" / fname
    bench.download(f"https://huggingface.co/{repo}/resolve/main/{fname}", gguf)
    o = json.loads(a.opts)
    label = a.model + a.tag
    srv = bench.Server(bindir, gguf, 4, o.get("ctx", 8192))
    srv.wait_ready()
    res = {"engine": a.engine, "model": label, "base_model": a.model, "opts": o, "lane": a.lane, "clients": {}}
    try:
        for c in CLIENTS:
            sc = script(c)
            if a.engine.endswith("-translate") and sc["lang"] != "cs":
                continue  # translating English to English is just transcribing
            pivot = (o.get("pivot") and sc["lang"] == "cs") or a.engine.endswith("-translate")  # translated text gets the English prompts
            lang = "en" if pivot else sc["lang"]
            d = o.get("prompt_dir") or (PROMPTS / ("cs" if lang == "cs" else ""))
            if isinstance(d, dict):
                d = d[lang]
            d = Path(d) if Path(d).is_absolute() else ROOT / d
            sysp, usrp = (d / "extract.system.txt").read_text(encoding="utf-8"), (d / "extract.user.txt").read_text(encoding="utf-8")
            facts, t0 = [], time.time()
            lines = lines_for(a.engine, c, a.asr_dir, a.lane)
            if o.get("pivot") and sc["lang"] == "cs" and not a.engine.endswith("-translate"):
                lines = translate_lines(lines, a.lane, o)
            ps = parts(lines, o.get("part_words", PART_WORDS))
            for i, p in enumerate(ps, 1):
                user = usrp.replace("{{CLIENT}}", c.split("-")[0].capitalize()).replace("{{DATE}}", sc["date"]).replace("{{PART}}", str(i)).replace("{{PARTS}}", str(len(ps))).replace("{{TEXT}}", p)
                raw = chat(sysp, user, a.lane, o.get("temperature", 0.3), o.get("max_tokens", 2000))
                body = " ".join(norm_words(p))
                for f in parse(raw):
                    f["part"] = i
                    f["quote_found"] = " ".join(norm_words(f.get("evidence", ""))) in body and bool(norm_words(f.get("evidence", "")))
                    facts.append(f)
            res["clients"][c] = {"lang": sc["lang"], "seconds": round(time.time() - t0, 1), "facts": facts, "lines": lines}
            print(a.engine, label, c, "lane", a.lane, len(facts), "facts", flush=True)
    finally:
        srv.proc.terminate()
    (out / f"{a.engine}__{label}__lane{a.lane}.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
