"""Canary 1B v2 (sherpa-onnx int8, as the app ships it) per turn, CPU (Colab 2 cores). cn.py --out DIR [--clients ...]"""
import argparse, json, os, sys, time, urllib.request
sys.path.insert(0, "/content/w")
import numpy as np, sherpa_onnx
from g4 import load_wav, split_piece
CANARY = "https://huggingface.co/fussraider/canary-1b-v2-sherpa-onnx-int8/resolve/main/"
ap = argparse.ArgumentParser()
ap.add_argument("--out", required=True); ap.add_argument("--clients", default="jana-n,tomas-b,daniel-r,hannah-w"); ap.add_argument("--threads", type=int, default=2)
a = ap.parse_args()
d = "/content/canary"; os.makedirs(d, exist_ok=True)
for n in ("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"):
    if not os.path.exists(f"{d}/{n}"):
        urllib.request.urlretrieve(CANARY + n, f"{d}/{n}")
import hashlib
sha = {n: hashlib.sha256(open(f"{d}/{n}", "rb").read()).hexdigest() for n in ("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")}
for c in a.clients.split(","):
    tt = json.load(open(f"/content/w/turns-timed/{c}.json"))
    lang = tt["lang"]
    t0 = time.time()
    r = sherpa_onnx.OfflineRecognizer.from_nemo_canary(encoder=f"{d}/encoder.int8.onnx", decoder=f"{d}/decoder.int8.onnx", tokens=f"{d}/tokens.txt",
                                                       src_lang=lang, tgt_lang=lang, num_threads=a.threads, decoding_method="greedy_search")
    load_s = round(time.time() - t0, 1)
    x = load_wav(c)
    rows, ta = [], time.time()
    for t in tt["turns"]:
        seg = x[int(t["cut_start"] * 16000): int(t["cut_end"] * 16000)]
        outs, secs = [], 0.0
        pcs = split_piece(seg)
        for p in pcs:
            s0 = time.time()
            s = r.create_stream(); s.accept_waveform(16000, p.astype("float32") / 32768); r.decode_stream(s)
            outs.append(s.result.text.strip()); secs += time.time() - s0
        rows.append(dict(idx=t["idx"], who=t["who"], cut_start=t["cut_start"], cut_end=t["cut_end"], pieces=len(pcs), audio_s=round(len(seg) / 16000, 2), seconds=round(secs, 2), raw=outs))
    wall = time.time() - ta
    os.makedirs(f"{a.out}/{c}", exist_ok=True)
    with open(f"{a.out}/{c}/turns.jsonl", "w", encoding="utf-8") as f:
        for q in rows:
            f.write(json.dumps(q, ensure_ascii=False) + "\n")
    json.dump(dict(client=c, engine="canary-1b-v2 sherpa-onnx %s int8 greedy" % sherpa_onnx.__version__, sha256=sha, language=lang, threads=a.threads, model_load_s=load_s, listen_wall_s=round(wall, 1),
                   audio_in_pieces_s=round(sum(q["audio_s"] for q in rows), 1), rtf=round(wall / max(1, sum(q["audio_s"] for q in rows)), 3), n_pieces=sum(q["pieces"] for q in rows),
                   started=time.strftime("%Y-%m-%d %H:%M:%S")), open(f"{a.out}/{c}/timing.json", "w"), indent=1)
    print("DONE", c, round(wall, 1), flush=True)
