#!/usr/bin/env python3
"""Host replica of the app's ASR step: the same Whisper int8 models through sherpa-onnx, greedy decoding.

  stt_host.py --size base --language auto|cs|en|per-client --out results/stt_base_auto.json [--clients a,b] [--sessions N]

Chunks are <= 20 s cut at the quietest 200 ms near the boundary (the app cuts 20 s chunks with 5 s overlap and merges them, so a
small boundary effect is expected here; compare runs with each other, not with the device). Output is a results.json with only `sessions`,
which score/scorer.py scores for WER/CER.
"""
import argparse, json, sys, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "models"
HF = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-{size}/resolve/main/{name}"
SR = 16000


def fetch(size):
    d = MODELS / f"whisper-{size}"
    d.mkdir(parents=True, exist_ok=True)
    for name in (f"{size}-encoder.int8.onnx", f"{size}-decoder.int8.onnx", f"{size}-tokens.txt"):
        f = d / name
        if not f.exists():
            print("downloading", name, flush=True)
            urllib.request.urlretrieve(HF.format(size=size, name=name), f)
    return d


def read_wav(path):
    import wave, numpy as np
    with wave.open(str(path)) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype("float32") / 32768


def split(x, max_s=20, win_s=0.2):
    import numpy as np
    n, win = len(x), int(SR * win_s)
    cuts, pos = [], 0
    while n - pos > max_s * SR:
        lo, hi = pos + int((max_s - 5) * SR), pos + max_s * SR
        energies = [(float((x[i:i + win] ** 2).mean()), i) for i in range(lo, hi - win, win // 2)]
        pos = min(energies)[1] + win // 2
        cuts.append(pos)
    bounds = [0] + cuts + [n]
    return [x[a:b] for a, b in zip(bounds, bounds[1:]) if b - a > SR // 4]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", default="base")
    ap.add_argument("--language", default="per-client", help="auto | cs | en | per-client")
    ap.add_argument("--clients")
    ap.add_argument("--sessions", type=int, default=10)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--audio", default=str(ROOT / "audio"))
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    import sherpa_onnx
    d = fetch(a.size)
    index = json.loads((ROOT / "corpus" / "clients.json").read_text(encoding="utf-8"))["clients"]
    if a.clients:
        index = [c for c in index if c["id"] in set(a.clients.split(","))]
    recs = {}

    def rec(lang):
        if lang not in recs:
            recs[lang] = sherpa_onnx.OfflineRecognizer.from_whisper(
                encoder=str(d / f"{a.size}-encoder.int8.onnx"), decoder=str(d / f"{a.size}-decoder.int8.onnx"), tokens=str(d / f"{a.size}-tokens.txt"),
                language="" if lang == "auto" else lang, task="transcribe", num_threads=a.threads, decoding_method="greedy_search",
            )
        return recs[lang]

    sessions, t0, audio_s = [], time.time(), 0.0
    for c in index:
        lang = c["lang"] if a.language == "per-client" else a.language
        r = rec(lang)
        for sid in c["sessions"][: a.sessions]:
            x = read_wav(Path(a.audio) / c["id"] / f"{sid}.wav")
            audio_s += len(x) / SR
            lines = []
            for chunk in split(x):
                s = r.create_stream()
                s.accept_waveform(SR, chunk)
                r.decode_stream(s)
                lines.append(s.result.text.strip())
            sessions.append({"client": c["id"], "session": f"{c['id']}_{sid}", "transcript": lines})
            print(f"{c['id']}/{sid} done ({time.time() - t0:.0f}s)", flush=True)
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    Path(a.out).write_text(json.dumps({"sessions": sessions, "facts": [], "answers": [], "meta": {"size": a.size, "language": a.language, "rtf": (time.time() - t0) / audio_s}}, ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
