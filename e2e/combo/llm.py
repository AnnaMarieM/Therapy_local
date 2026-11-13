#!/usr/bin/env python3
"""One LLM cell: (ASR transcripts of one variant | the script as read) x model x lane -> extracted facts for all four recordings.

  llm.py --asr-dir asr/ --engine canary-1b-v2 --model gemma4-e4b --lane 1 [--tag x --opts '{"part_words":300}'] --out out     (--engine script = oracle, perfect ASR)
Pipeline per recording, like the app: (translate = pivot, optional) -> roles (the app's roles prompt decides who is the therapist) -> extraction with
Therapist:/Patient: lines, in parts. llama.cpp server, Q4_K_M, temperature 0.3 with seed = lane, so the lanes differ only by sampling.
There is no diarizer in CI: speaker turns of an ASR transcript come from an ORACLE alignment of its words to the script (common.oracle_turns), i.e. perfect
diarization; what is tested is the text, the roles step and the extraction. Transcripts that are in another language than the script (Whisper translate) have no
alignment and run without roles ("Speaker 1").

opts (all optional): part_words (450)  temperature (0.3)  max_tokens (2000)  ctx (8192)
  roles       "llm" (default) | "none" = every line "Speaker 1" (how round 1 ran)
  prompt_dir  folder with extract.system.txt / extract.user.txt, or {"cs": dir, "en": dir}; default demo/prompts (cs/ for Czech)
  pivot       true = Czech turns are first translated to English by the same model (AD-17), then the English prompts run
"""
import argparse, json, re, sys, time, urllib.request, zlib
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "bench"))
sys.path.insert(0, str(Path(__file__).parent))
import bench  # noqa: E402
from common import CLIENTS, script, norm_words, oracle_turns  # noqa: E402

PROMPTS = ROOT / "demo" / "prompts"
PART_WORDS = 450
LABEL = {"therapist": "Therapist", "client": "Patient", None: "Speaker 1"}


def turns_for(engine, client, asr_dir, lane, roles):
    """[(who, text)] with who in therapist | client | None (unknown)."""
    if engine == "script":
        return [(t["who"], t["text"]) for t in script(client)["turns"]]
    f = Path(asr_dir) / f"{engine}__{client}__lane{lane}.json"
    if not f.exists():  # ASR is (nearly) deterministic: any lane's transcript will do
        f = next(Path(asr_dir).glob(f"{engine}__{client}__lane*.json"))
    segs = [s["text"] for s in json.loads(f.read_text(encoding="utf-8"))["segments"] if s["text"]]
    if roles != "none" and not engine.endswith("-translate"):
        return oracle_turns(client, " ".join(segs))
    return [(None, s) for s in segs]


def chat(system, user, seed, temperature=0.3, max_tokens=2000):
    body = {"messages": [{"role": "system", "content": system}, {"role": "user", "content": user}], "temperature": temperature, "seed": seed, "max_tokens": max_tokens,
            "chat_template_kwargs": {"enable_thinking": False}}
    req = urllib.request.Request(f"http://127.0.0.1:{bench.PORT}/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=1800) as r:
        return json.loads(r.read())["choices"][0]["message"]["content"] or ""


def translate_turns(turns, seed, o):
    """AD-17 pivot: Czech turn texts -> English, 12 per call, numbered so the count can be checked."""
    sysp = ("You translate a Czech therapy-session transcript into English. Translate every numbered line faithfully, keep names, numbers and meaning, "
            "do not summarise or add anything. Output the same numbered lines, nothing else.")
    out = []
    for i in range(0, len(turns), 12):
        chunk = turns[i:i + 12]
        raw = chat(sysp, "\n".join(f"{j + 1}. {t}" for j, (_, t) in enumerate(chunk)), seed, 0.0, o.get("max_tokens", 2000))
        got = {}
        for l in raw.splitlines():
            m = re.match(r"\s*(\d+)[.)]\s*(.*)", l)
            if m:
                got[int(m.group(1))] = m.group(2).strip()
        out += [(w, got.get(j + 1, t)) for j, (w, t) in enumerate(chunk)]
    return out


def assign_roles(turns, client, lang, seed, o):
    """The app's roles step: speaker ids S0/S1 (by oracle diarization), the model says which is the therapist. Returns (labelled turns, info)."""
    flip = zlib.crc32(client.encode()) % 2  # which of S0/S1 is the therapist in this recording
    sid = {"therapist": f"S{flip}", "client": f"S{1 - flip}"}
    if any(w is None for w, _ in turns):
        return [(None, t) for _, t in turns], {"roles": "skipped"}
    head = "\n".join(f"[00:{i // 60:02d}:{i % 60:02d}]\n{sid[w]}: {t}" for i, (w, t) in enumerate(turns[:30]))
    d = PROMPTS / ("cs" if lang == "cs" else "")
    raw = chat((d / "roles.system.txt").read_text(encoding="utf-8"), head, seed, 0.0, 400)
    m = re.search(r"\{.*\}", raw, re.S)
    guess = None
    try:
        guess = json.loads(m.group(0)).get("therapist") if m else None
    except Exception:
        pass
    ok = guess == sid["therapist"]
    if guess not in ("S0", "S1"):
        return [(None, t) for _, t in turns], {"roles": "unparsed", "raw": raw[:200]}
    swap = {"therapist": "therapist", "client": "client"} if ok else {"therapist": "client", "client": "therapist"}  # a wrong guess swaps the labels, as in the app
    return [(swap[w], t) for w, t in turns], {"roles": "right" if ok else "WRONG", "raw": raw[:200]}


def parts(turns, part_words=PART_WORDS):
    out, cur, n = [], [], 0
    for i, (who, l) in enumerate(turns):
        cur.append(f"[00:{i // 60:02d}:{i % 60:02d}]\n{LABEL[who]}: {l}")
        n += len(l.split())
        if n >= part_words:
            out.append("\n".join(cur))
            cur, n = [], 0
    if cur:
        out.append("\n".join(cur))
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
    roles = o.get("roles", "llm")
    try:
        for c in CLIENTS:
            sc = script(c)
            translated = a.engine.endswith("-translate")
            if translated and sc["lang"] != "cs":
                continue  # translating English to English is just transcribing
            pivot = (o.get("pivot") and sc["lang"] == "cs") or translated  # translated text gets the English prompts
            lang = "en" if pivot else sc["lang"]
            d = o.get("prompt_dir") or (PROMPTS / ("cs" if lang == "cs" else ""))
            if isinstance(d, dict):
                d = d[lang]
            d = Path(d) if Path(d).is_absolute() else ROOT / d
            sysp, usrp = (d / "extract.system.txt").read_text(encoding="utf-8"), (d / "extract.user.txt").read_text(encoding="utf-8")
            facts, t0 = [], time.time()
            turns = turns_for(a.engine, c, a.asr_dir, a.lane, roles)
            if o.get("pivot") and sc["lang"] == "cs" and not translated:
                turns = translate_turns(turns, a.lane, o)
            info = {"roles": "none"}
            if roles != "none":
                turns, info = assign_roles(turns, c, lang, a.lane, o)
            ps = parts(turns, o.get("part_words", PART_WORDS))
            for i, p in enumerate(ps, 1):
                user = usrp.replace("{{CLIENT}}", c.split("-")[0].capitalize()).replace("{{DATE}}", sc["date"]).replace("{{PART}}", str(i)).replace("{{PARTS}}", str(len(ps))).replace("{{TEXT}}", p)
                raw = chat(sysp, user, a.lane, o.get("temperature", 0.3), o.get("max_tokens", 2000))
                body = " ".join(norm_words(p))
                for f in parse(raw):
                    f["part"] = i
                    f["quote_found"] = bool(norm_words(f.get("evidence", ""))) and " ".join(norm_words(f.get("evidence", ""))) in body
                    facts.append(f)
            res["clients"][c] = {"lang": sc["lang"], "seconds": round(time.time() - t0, 1), "facts": facts, "turns": [{"who": w, "text": t} for w, t in turns], "roles": info}
            print(a.engine, label, c, "lane", a.lane, len(facts), "facts, roles:", info["roles"], flush=True)
    finally:
        srv.proc.terminate()
    (out / f"{a.engine}__{label}__lane{a.lane}.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
