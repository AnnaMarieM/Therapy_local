#!/usr/bin/env python3
"""One ASR cell: engine x recording x lane -> transcript + WER/CER against the script as read.

  asr.py --engine whisper-small|whisper-medium|whisper-large-v3|whisper-large-v3-turbo|canary-1b-v2 --client jana-n --lane 1 --out out
Whisper: faster-whisper int8, greedy, language forced to the client's. Canary: the app's model (sherpa-onnx int8), 20 s chunks.
"""
import argparse, json, subprocess, sys, time, urllib.request, wave
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from common import HUMAN, script, wer, cer

SR = 16000
CANARY = "https://huggingface.co/fussraider/canary-1b-v2-sherpa-onnx-int8/resolve/main/"


def to_wav(client, work):
    wav = work / f"{client}.wav"
    if not wav.exists():
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(HUMAN / client / "s01.m4a"), "-ac", "1", "-ar", str(SR), "-c:a", "pcm_s16le", str(wav)], check=True)
    return wav


def whisper(engine, wav, lang):
    from faster_whisper import WhisperModel
    task = "translate" if engine.endswith("-translate") else "transcribe"  # translate: speech in any language -> English text
    name = engine.removeprefix("whisper-").removesuffix("-translate")
    m = WhisperModel(name, device="cpu", compute_type="int8", cpu_threads=4)
    import numpy as np
    with wave.open(str(wav)) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype("float32") / 32768
    segs, _ = m.transcribe(x, language=lang, task=task, beam_size=1, temperature=0.0, vad_filter=True, condition_on_previous_text=False)
    return [{"start": round(s.start, 2), "end": round(s.end, 2), "text": s.text.strip()} for s in segs]


def canary(wav, lang, work):
    import numpy as np, sherpa_onnx
    d = work / "canary"
    d.mkdir(exist_ok=True)
    for n in ("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"):
        if not (d / n).exists():
            urllib.request.urlretrieve(CANARY + n, d / n)
    r = sherpa_onnx.OfflineRecognizer.from_nemo_canary(
        encoder=str(d / "encoder.int8.onnx"), decoder=str(d / "decoder.int8.onnx"), tokens=str(d / "tokens.txt"),
        src_lang=lang, tgt_lang=lang, use_pnc=True, num_threads=4, decoding_method="greedy_search")
    with wave.open(str(wav)) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype("float32") / 32768
    step, win, pos, n = 20 * SR, int(0.2 * SR), 0, len(x)
    cuts = []
    while n - pos > step:  # cut at the quietest 200 ms in the last 5 s of the window
        e = [(float((x[i:i + win] ** 2).mean()), i) for i in range(pos + 15 * SR, pos + step - win, win // 2)]
        pos = min(e)[1] + win // 2
        cuts.append(pos)
    bounds = [0] + cuts + [n]
    out = []
    for a, c in zip(bounds, bounds[1:]):
        if c - a < SR // 4:
            continue
        s = r.create_stream()
        s.accept_waveform(SR, x[a:c])
        r.decode_stream(s)
        out.append({"start": round(a / SR, 2), "end": round(c / SR, 2), "text": s.result.text.strip()})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", required=True)
    ap.add_argument("--client", required=True)
    ap.add_argument("--lane", type=int, default=1)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    sc = script(a.client)
    lang = sc["lang"]
    wav = to_wav(a.client, out)
    t0 = time.time()
    segs = canary(wav, lang, out) if a.engine == "canary-1b-v2" else whisper(a.engine, wav, lang)
    secs = time.time() - t0
    with wave.open(str(wav)) as w:
        dur = w.getnframes() / SR
    ref = " ".join(t["text"] for t in sc["turns"])
    hyp = " ".join(s["text"] for s in segs)
    translated = a.engine.endswith("-translate")  # English output: WER against the Czech script is meaningless, read it instead
    res = {"engine": a.engine, "client": a.client, "lang": lang, "lane": a.lane, "audio_s": round(dur, 1), "seconds": round(secs, 1),
           "rtf": round(secs / dur, 3), "wer": None if translated else round(wer(ref, hyp), 4), "cer": None if translated else round(cer(ref, hyp), 4), "segments": segs}
    (out / f"{a.engine}__{a.client}__lane{a.lane}.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
    print(a.engine, a.client, "lane", a.lane, "WER", res["wer"], "CER", res["cer"], "RTF", res["rtf"])


if __name__ == "__main__":
    main()
