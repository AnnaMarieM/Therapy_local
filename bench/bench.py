#!/usr/bin/env python3
"""CPU inference + Czech quality benchmark for Gemma 4 on GitHub-hosted runners (llama.cpp runtime).

One invocation = one (runner, model, quant) cell. Writes results.json plus raw model outputs to --out.
Stdlib only (psutil optional, for peak memory).
"""
import argparse, json, os, platform, re, shutil, stat, subprocess, sys, tarfile, time, unicodedata, urllib.request, zipfile, zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = Path(__file__).resolve().parent / "data" / "cases_cs.json"
PROMPTS = ROOT / "demo" / "prompts"
EXE = ".exe" if os.name == "nt" else ""
PORT = 8089

# key -> (HF repo, GGUF file). e2b/e4b are the ggml-org Q4_0/Q8_0 builds (file depends on --quant); the rest are Q4_K_M.
MODELS = {
    "gemma4-e2b": ("unsloth/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_K_M.gguf"),
    "gemma4-e4b": ("unsloth/gemma-4-E4B-it-GGUF", "gemma-4-E4B-it-Q4_K_M.gguf"),
    "qwen3.5-0.8b": ("unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_K_M.gguf"),
    "qwen3.5-2b": ("unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B-Q4_K_M.gguf"),
    "qwen3.5-4b": ("unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_K_M.gguf"),
    "qwen3-4b-2507": ("unsloth/Qwen3-4B-Instruct-2507-GGUF", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf"),
    "ministral3-3b": ("mistralai/Ministral-3-3B-Instruct-2512-GGUF", "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf"),
    "phi4-mini": ("unsloth/Phi-4-mini-instruct-GGUF", "Phi-4-mini-instruct-Q4_K_M.gguf"),
    "granite4-micro": ("unsloth/granite-4.0-micro-GGUF", "granite-4.0-micro-Q4_K_M.gguf"),
    "llama3.2-3b": ("unsloth/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
    "eurollm-1.7b": ("mradermacher/EuroLLM-1.7B-Instruct-GGUF", "EuroLLM-1.7B-Instruct.Q4_K_M.gguf"),
}


def model_source(key, quant):
    if key in ("e2b", "e4b"):
        M = key.upper()
        return f"ggml-org/gemma-4-{M}-it-GGUF", f"gemma-4-{M}-it-{quant}.gguf"
    return MODELS[key]

try:
    import psutil
except ImportError:
    psutil = None


for _s in (sys.stdout, sys.stderr):
    _s.reconfigure(encoding="utf-8", errors="replace")


def log(*a):
    print(*a, flush=True)


# ---------------------------------------------------------------- downloads

def download(url, dest: Path):
    """Streams url to dest, returns (seconds, bytes)."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    t0 = time.perf_counter()
    n = 0
    req = urllib.request.Request(url, headers={"User-Agent": "therapylocal-bench"})
    with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            n += len(chunk)
    return time.perf_counter() - t0, n


def fetch_llama(build, asset, work: Path):
    ext = "zip" if asset.startswith("win") else "tar.gz"
    url = f"https://github.com/ggml-org/llama.cpp/releases/download/{build}/llama-{build}-bin-{asset}.{ext}"
    archive = work / f"llama.{ext}"
    secs, size = download(url, archive)
    dest = work / "llama"
    shutil.rmtree(dest, ignore_errors=True)
    dest.mkdir(parents=True)
    if ext == "zip":
        zipfile.ZipFile(archive).extractall(dest)
    else:
        with tarfile.open(archive) as t:
            t.extractall(dest)
    server = next(dest.rglob("llama-server" + EXE))
    bench = next(dest.rglob("llama-bench" + EXE))
    for p in (server, bench):
        p.chmod(p.stat().st_mode | stat.S_IEXEC)
    return server.parent, {"url": url, "seconds": round(secs, 2), "bytes": size}


def env_for(bindir: Path):
    env = dict(os.environ)
    key = {"Darwin": "DYLD_LIBRARY_PATH", "Linux": "LD_LIBRARY_PATH"}.get(platform.system())
    if key:
        env[key] = f"{bindir}{os.pathsep}{env.get(key, '')}"
    return env


# ---------------------------------------------------------------- speed

def llama_bench(bindir, model, threads):
    cmd = [str(bindir / ("llama-bench" + EXE)), "-m", str(model), "-p", "512", "-n", "128", "-t", str(threads), "-r", "2", "-o", "json"]
    log("$", " ".join(cmd))
    p = subprocess.run(cmd, capture_output=True, text=True, env=env_for(bindir), timeout=1800)
    if p.returncode != 0:
        return {"error": (p.stderr or p.stdout)[-1500:]}
    rows = json.loads(p.stdout[p.stdout.index("["):])
    out = {}
    for r in rows:
        if r.get("n_prompt") and not r.get("n_gen"):
            out["prefill_tok_s"] = round(r["avg_ts"], 2)
        elif r.get("n_gen") and not r.get("n_prompt"):
            out["decode_tok_s"] = round(r["avg_ts"], 2)
    return out


class Server:
    def __init__(self, bindir, model, threads, ctx):
        cmd = [str(bindir / ("llama-server" + EXE)), "-m", str(model), "-t", str(threads), "-c", str(ctx), "-np", "1",
               "--host", "127.0.0.1", "--port", str(PORT), "--jinja"]
        log("$", " ".join(cmd))
        self.logf = open(model.parent / "server.log", "w")
        self.t0 = time.perf_counter()
        self.proc = subprocess.Popen(cmd, stdout=self.logf, stderr=subprocess.STDOUT, env=env_for(bindir))
        self.peak_rss = 0
        self.ps = psutil.Process(self.proc.pid) if psutil else None
        self.load_s = None

    def wait_ready(self, timeout=600):
        while time.perf_counter() - self.t0 < timeout:
            if self.proc.poll() is not None:
                raise RuntimeError("llama-server exited: " + Path(self.logf.name).read_text()[-1500:])
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2) as r:
                    if r.status == 200:
                        self.load_s = round(time.perf_counter() - self.t0, 2)
                        return
            except Exception:
                time.sleep(0.5)
        raise TimeoutError("llama-server not ready")

    def sample(self):
        if self.ps:
            try:
                self.peak_rss = max(self.peak_rss, self.ps.memory_info().rss)
            except Exception:
                pass

    def chat(self, system, user, max_tokens, temperature=0.1, top_k=20):
        body = {"messages": ([{"role": "system", "content": system}] if system else []) + [{"role": "user", "content": user}],
                "max_tokens": max_tokens, "temperature": temperature, "top_k": top_k, "top_p": 0.9, "seed": 1,
                "chat_template_kwargs": {"enable_thinking": False}}
        req = urllib.request.Request(f"http://127.0.0.1:{PORT}/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
        t0 = time.perf_counter()
        with urllib.request.urlopen(req, timeout=1800) as r:
            d = json.load(r)
        self.sample()
        ch = d["choices"][0]
        text = re.sub(r"<think>.*?</think>", "", ch["message"].get("content") or "", flags=re.S).strip()
        tm = d.get("timings", {})
        return {"text": text, "finish": ch.get("finish_reason"), "wall_s": round(time.perf_counter() - t0, 2),
                "prompt_n": tm.get("prompt_n"), "prompt_tok_s": tm.get("prompt_per_second"),
                "gen_n": tm.get("predicted_n"), "gen_tok_s": tm.get("predicted_per_second")}

    def stop(self):
        self.proc.terminate()
        try:
            self.proc.wait(30)
        except Exception:
            self.proc.kill()
        self.logf.close()


# ---------------------------------------------------------------- Czech checks

CZ_DIACRITICS = set("áčďéěíňóřšťúůýžÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ")
EN_WORDS = {"the", "and", "you", "that", "this", "with", "for", "are", "was", "have", "about", "your", "from", "they", "feeling"}


def letters(s):
    return [c for c in s if c.isalpha()]


def czech_stats(text):
    ls = letters(text)
    words = re.findall(r"[^\W\d_]+", text.lower())
    non_latin = sum(1 for c in ls if "LATIN" not in unicodedata.name(c, ""))
    comp = len(zlib.compress(text.encode())) / max(1, len(text.encode()))
    return {
        "chars": len(text), "diacritic_ratio": round(sum(c in CZ_DIACRITICS for c in ls) / max(1, len(ls)), 4),
        "english_word_ratio": round(sum(w in EN_WORDS for w in words) / max(1, len(words)), 4),
        "non_latin_letters": non_latin, "compress_ratio": round(comp, 3),
        "looping": len(text) > 300 and comp < 0.2,
    }


def app_norm(s):  # exactly FactParser.norm: drops every non a-z0-9 char, including all Czech letters
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", s.lower())).strip()


def uni_norm(s):
    return re.sub(r"\s+", " ", re.sub(r"[^\w ]|_", " ", s.lower())).strip()


def parse_facts(reply):
    facts, bad = [], 0
    for line in reply.splitlines():
        line = line.strip().strip("`").strip()
        if not line.startswith("{"):
            continue
        try:
            o = json.loads(line[line.index("{"):line.rindex("}") + 1])
        except Exception:
            bad += 1
            continue
        if all(k in o for k in ("heading", "content", "evidence")):
            facts.append({k: str(o[k]) for k in ("heading", "content", "evidence")})
        else:
            bad += 1
    return facts, bad


def read_prompt(name):
    return (PROMPTS / name).read_text(encoding="utf-8").strip()


def fill(t, **v):
    for k, x in v.items():
        t = t.replace("{{%s}}" % k, x)
    return t


# ---------------------------------------------------------------- evals

def eval_belebele(srv, n, out):
    url = "https://huggingface.co/datasets/facebook/belebele/resolve/main/data/ces_Latn.jsonl"
    f = out / "belebele_ces.jsonl"
    download(url, f)
    rows = [json.loads(l) for l in f.read_text(encoding="utf-8").splitlines() if l.strip()]
    step = max(1, len(rows) // n)
    sample = rows[::step][:n]
    ok, answered, outs = 0, 0, []
    for r in sample:
        q = (f"Read the Czech passage and answer the multiple-choice question.\n\nPassage:\n{r['flores_passage']}\n\nQuestion: {r['question']}\n"
             f"A) {r['mc_answer1']}\nB) {r['mc_answer2']}\nC) {r['mc_answer3']}\nD) {r['mc_answer4']}\n\nAnswer with only the letter A, B, C or D.")
        res = srv.chat(None, q, 8, temperature=0.0, top_k=1)
        m = re.search(r"\b([ABCD])\b", res["text"].upper())
        gold = "ABCD"[int(r["correct_answer_num"]) - 1]
        answered += bool(m)
        ok += bool(m and m.group(1) == gold)
        outs.append({"q": r["question_number"], "gold": gold, "reply": res["text"]})
    (out / "belebele_outputs.json").write_text(json.dumps(outs, ensure_ascii=False, indent=1), encoding="utf-8")
    return {"n": len(sample), "accuracy": round(ok / len(sample), 3), "answered_letter": round(answered / len(sample), 3)}


def eval_extraction(srv, cases, out):
    system, template = read_prompt("extract.system.txt"), read_prompt("extract.user.txt")
    per, raw = [], {}
    for t in cases["transcripts"]:
        user = fill(template, CLIENT=t["client"], DATE=t["date"], PART="1", PARTS="1", TEXT=t["text"])
        res = srv.chat(system, user, 1600, temperature=0.1, top_k=20)
        facts, bad = parse_facts(res["text"])
        src_app, src_uni = app_norm(t["text"]), uni_norm(t["text"])
        verb_app = sum(app_norm(f["evidence"]) in src_app and len(app_norm(f["evidence"])) > 0 for f in facts)
        verb_uni = sum(uni_norm(f["evidence"]) in src_uni and len(uni_norm(f["evidence"])) > 0 for f in facts)
        joined = " ".join(f["heading"] + " " + f["content"] for f in facts).lower()
        hits = [g for g in t["gold"] if g.lower() in joined or g.lower() in " ".join(f["evidence"] for f in facts).lower()]
        stats = czech_stats(joined) if joined else {}
        per.append({"id": t["id"], "facts": len(facts), "unparsable_lines": bad, "finish": res["finish"], "gen_n": res["gen_n"], "gen_tok_s": res["gen_tok_s"],
                    "evidence_verbatim_unicode": f"{verb_uni}/{len(facts)}", "evidence_passes_app_filter": f"{verb_app}/{len(facts)}",
                    "gold_recall": round(len(hits) / len(t["gold"]), 2), "gold_missed": [g for g in t["gold"] if g not in hits],
                    "content_english_ratio": stats.get("english_word_ratio"), "content_diacritic_ratio": stats.get("diacritic_ratio")})
        raw[t["id"]] = res["text"]
    (out / "extraction_outputs.json").write_text(json.dumps(raw, ensure_ascii=False, indent=1), encoding="utf-8")
    return per


def eval_review(srv, cases, out):
    system, template = read_prompt("review.system.txt"), read_prompt("review.user.txt")
    ok, parsed, raw = 0, 0, []
    for c in cases["review_cases"]:
        known = "\n".join(f"K{i + 1}: {k}" for i, k in enumerate(c["known"]))
        res = srv.chat(system, fill(template, KNOWN=known, FACT=c["fact"]), 40, temperature=0.1, top_k=1)
        m = re.search(r"\{.*\}", res["text"], re.S)
        verdict = None
        try:
            verdict = json.loads(m.group(0)) if m else None
        except Exception:
            pass
        if isinstance(verdict, dict) and "duplicate" in verdict:
            parsed += 1
            dup = verdict["duplicate"] in (True, "true", "True")
            ok += dup == c["duplicate"]
        raw.append({"fact": c["fact"], "expected": c["duplicate"], "reply": res["text"]})
    (out / "review_outputs.json").write_text(json.dumps(raw, ensure_ascii=False, indent=1), encoding="utf-8")
    n = len(cases["review_cases"])
    return {"n": n, "json_parsed": parsed, "correct_verdict": ok}


def eval_writing(srv, cases, out):
    per, raw = [], []
    for i, p in enumerate(cases["writing_prompts"]):
        res = srv.chat(None, p, 600, temperature=0.7, top_k=40)
        s = czech_stats(res["text"])
        s.update({"prompt": i, "finish": res["finish"], "gen_n": res["gen_n"], "gen_tok_s": res["gen_tok_s"]})
        per.append(s)
        raw.append({"prompt": p, "reply": res["text"]})
    (out / "writing_outputs.json").write_text(json.dumps(raw, ensure_ascii=False, indent=1), encoding="utf-8")
    return per


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True)
    ap.add_argument("--model", choices=["e2b", "e4b", *MODELS], required=True)
    ap.add_argument("--quant", default="Q4_0")
    ap.add_argument("--llama-build", required=True)
    ap.add_argument("--llama-asset", required=True)
    ap.add_argument("--threads", type=int, default=os.cpu_count() or 2)
    ap.add_argument("--quality", action="store_true")
    ap.add_argument("--belebele-n", type=int, default=100)
    ap.add_argument("--out", default="out")
    a = ap.parse_args()

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    work = out / "work"
    work.mkdir(exist_ok=True)
    res = {"label": a.label, "model": a.model, "quant": a.quant, "llama_build": a.llama_build, "threads": a.threads,
           "machine": {"platform": platform.platform(), "machine": platform.machine(), "cpus": os.cpu_count(),
                       "ram_gb": round(psutil.virtual_memory().total / 2**30, 1) if psutil else None}}

    bindir, res["llama_download"] = fetch_llama(a.llama_build, a.llama_asset, work)
    repo, fname = model_source(a.model, a.quant)
    url = f"https://huggingface.co/{repo}/resolve/main/{fname}"
    model = work / fname
    secs, size = download(url, model)
    res["model_download"] = {"url": url, "seconds": round(secs, 1), "bytes": size, "mb_per_s": round(size / 2**20 / secs, 1)}
    log("model download", res["model_download"])

    res["llama_bench"] = llama_bench(bindir, model, a.threads)
    log("llama-bench", res["llama_bench"])

    srv = Server(bindir, model, a.threads, 8192)
    try:
        srv.wait_ready()
        res["load_seconds"] = srv.load_s
        cases = json.loads(DATA.read_text(encoding="utf-8"))
        warm = srv.chat(None, "Řekni jednou větou česky, jaké je hlavní město České republiky.", 40, temperature=0.0, top_k=1)
        res["warm_reply"] = warm["text"]
        res["czech_hello_tok_s"] = warm["gen_tok_s"]
        if a.quality:
            res["belebele"] = eval_belebele(srv, a.belebele_n, out)
            log("belebele", res["belebele"])
            res["extraction"] = eval_extraction(srv, cases, out)
            res["review"] = eval_review(srv, cases, out)
            res["writing"] = eval_writing(srv, cases, out)
        srv.sample()
        res["peak_rss_mb"] = round(srv.peak_rss / 2**20) if srv.peak_rss else None
    except Exception as e:
        res["error"] = repr(e)
        log("ERROR", e)
    finally:
        srv.stop()

    shutil.rmtree(work, ignore_errors=True)
    (out / "results.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
    log(json.dumps({k: v for k, v in res.items() if k not in ("extraction", "writing")}, ensure_ascii=False, indent=1))
    return 1 if "error" in res else 0


if __name__ == "__main__":
    sys.exit(main())
