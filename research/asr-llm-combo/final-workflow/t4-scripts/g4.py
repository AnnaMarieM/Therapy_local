"""Gemma 4 listens to per-turn audio pieces via llama.cpp llama-server (mtmd, audio). Runs on the Colab T4.
  g4.py --size e2b|e4b --mode onepass|translate|transcribe --clients a,b --lane N --port P --out DIR [--pieces-only]
Pieces: turns-timed cut_start..cut_end; a piece over 30 s is split at the quietest 100 ms window of its middle half (recursively).
Output: DIR/<client>/turns__lane<N>.jsonl (raw model text per piece, untouched) and DIR/<client>/timing__lane<N>.json."""
import argparse, base64, io, json, os, subprocess, sys, time, urllib.request, wave
import numpy as np

BIN = "/content/llama.cpp/build/bin/llama-server"
M = json.load(open("/content/models.json"))
MODELS = {"e2b": ("unsloth/gemma-4-E2B-it-GGUF/gemma-4-E2B-it-Q4_K_M.gguf", "unsloth/gemma-4-E2B-it-GGUF/mmproj-F16.gguf"),
          "e4b": ("unsloth/gemma-4-E4B-it-GGUF/gemma-4-E4B-it-Q4_K_M.gguf", "unsloth/gemma-4-E4B-it-GGUF/mmproj-F16.gguf")}
PROMPTS = {
    "onepass": ("Listen to this audio. It is a short piece of a therapy session spoken in Czech. First write exactly what is said in Czech, then translate it into English. "
                "Answer with exactly two lines and nothing else:\nCzech: <the Czech transcript>\nEnglish: <the English translation>"),
    "translate": ("Listen to this audio. It is a short piece of a therapy session spoken in Czech. Translate what is said into English. "
                  "Keep names, numbers and meaning, do not summarise, add nothing. Answer with the English translation only."),
    "transcribe_cs": "Transcribe the following speech segment in Czech into Czech text. Only output the transcription, with no extra comments.",
    "transcribe_en": "Transcribe the following speech segment in English into English text. Only output the transcription, with no extra comments.",
}


def load_wav(client):
    with wave.open(f"/content/w/{client}_None.wav") as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)


def split_piece(x, sr=16000, maxs=30.0):
    if len(x) / sr <= maxs:
        return [x]
    n = len(x)
    a, b = n // 4, 3 * n // 4
    win = sr // 10
    e = np.abs(x[a:b].astype("float32"))
    c = np.convolve(e, np.ones(win) / win, mode="valid")
    cut = a + int(np.argmin(c)) + win // 2
    return split_piece(x[:cut]) + split_piece(x[cut:])


def wav_b64(x):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(x.astype(np.int16).tobytes())
    return base64.b64encode(buf.getvalue()).decode()


def ask(port, prompt, x, seed, max_tokens=700):
    body = {"messages": [{"role": "user", "content": [{"type": "text", "text": prompt}, {"type": "input_audio", "input_audio": {"data": wav_b64(x), "format": "wav"}}]}],
            "temperature": 0.0, "seed": seed, "max_tokens": max_tokens, "chat_template_kwargs": {"enable_thinking": False}}
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        d = json.loads(r.read())
    m = d["choices"][0]["message"]; u = d.get("usage", {}); u["reasoning_chars"] = len(m.get("reasoning_content") or ""); return m.get("content") or "", u


def start(size, port, ctx=4096):
    gg, mm = MODELS[size]
    cmd = [BIN, "-m", M[gg]["path"], "--mmproj", M[mm]["path"], "-ngl", "99", "-c", str(ctx), "--host", "127.0.0.1", "--port", str(port), "--jinja", "-fa", "off", "--no-webui", "-np", "1"]
    log = open(f"/content/logs/server_{size}_{port}.log", "w")
    p = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
    t = time.time()
    while time.time() - t < 600:
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2).read()
            return p, round(time.time() - t, 1)
        except Exception:
            if p.poll() is not None:
                raise RuntimeError("server exited, see log")
            time.sleep(1)
    raise RuntimeError("server timeout")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", required=True); ap.add_argument("--mode", required=True)
    ap.add_argument("--clients", default="jana-n,tomas-b,daniel-r,hannah-w")
    ap.add_argument("--lane", type=int, default=1); ap.add_argument("--port", type=int, default=8101)
    ap.add_argument("--out", required=True); ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    proc, load_s = start(a.size, a.port)
    print("server ready in", load_s, "s", flush=True)
    try:
        for c in a.clients.split(","):
            tt = json.load(open(f"/content/w/turns-timed/{c}.json"))
            cs = tt["lang"] == "cs"
            mode = a.mode
            prompt = PROMPTS[mode] if mode in ("onepass", "translate") else PROMPTS["transcribe_cs" if cs else "transcribe_en"]
            if not cs and mode in ("onepass", "translate"):
                prompt = PROMPTS["transcribe_en"]  # English recordings: the audio is already English
            x = load_wav(c)
            od = f"{a.out}/{c}"
            os.makedirs(od, exist_ok=True)
            rows, t_all = [], time.time()
            for t in tt["turns"][: a.limit or None]:
                seg = x[int(t["cut_start"] * 16000): int(t["cut_end"] * 16000)]
                pieces = split_piece(seg)
                outs, secs, toks, rch = [], 0.0, 0, 0
                for p in pieces:
                    t0 = time.time()
                    txt, u = ask(a.port, prompt, p, a.lane)
                    secs += time.time() - t0
                    outs.append(txt)
                    toks += u.get("completion_tokens", 0); rch = u.get("reasoning_chars", 0)
                rows.append(dict(idx=t["idx"], who=t["who"], cut_start=t["cut_start"], cut_end=t["cut_end"], pieces=len(pieces), audio_s=round(len(seg) / 16000, 2),
                                 seconds=round(secs, 2), completion_tokens=toks, reasoning_chars=rch, raw=outs))
                print(c, t["idx"], round(secs, 1), flush=True)
            wall = time.time() - t_all
            with open(f"{od}/turns__lane{a.lane}.jsonl", "w", encoding="utf-8") as f:
                for r in rows:
                    f.write(json.dumps(r, ensure_ascii=False) + "\n")
            json.dump(dict(client=c, size=a.size, mode=a.mode, lane=a.lane, prompt=prompt, server_load_s=load_s, listen_wall_s=round(wall, 1), audio_total_s=tt["duration"],
                           audio_in_pieces_s=round(sum(r["audio_s"] for r in rows), 1), rtf=round(wall / max(1, sum(r["audio_s"] for r in rows)), 3),
                           n_pieces=sum(r["pieces"] for r in rows), model=MODELS[a.size][0], mmproj=MODELS[a.size][1], llama_cpp="fe14cad51bbfb3f1cc8d9cfd9d70e98a123743d3",
                           temperature=0.0, started=time.strftime("%Y-%m-%d %H:%M:%S")), open(f"{od}/timing__lane{a.lane}.json", "w"), indent=1)
            print("DONE", c, round(wall, 1), "s", flush=True)
    finally:
        proc.terminate()


if __name__ == "__main__":
    main()
