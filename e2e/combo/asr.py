#!/usr/bin/env python3
"""One ASR cell: engine x recording x lane -> transcript + WER/CER against the script as read.

  asr.py --id large-v3-beam5 --engine whisper-small|whisper-medium|whisper-large-v3|whisper-large-v3-turbo|canary-1b-v2 [--opts '{"beam_size":5}'] --client jana-n --lane 1 --out out
An engine name ending in -translate runs Whisper's translate task (speech -> English text; WER is then n/a, read the text).
Whisper: faster-whisper int8, language forced to the client's. Canary: the app's model (sherpa-onnx int8), cut into chunks.

opts (all optional):
  any engine : ffmpeg_filter  audio filter chain before ASR, e.g. "highpass=f=80,loudnorm"
  whisper    : beam_size (1) vad (true) vad_min_silence_ms (2000) initial_prompt (str or {"cs": .., "en": ..}) condition_on_previous_text (false)
               temperature (0.0) compute_type ("int8") no_speech_threshold (0.6) repetition_penalty (1.0) hotwords (str) length_penalty (1.0) patience (1.0)
  canary     : chunk_s (20) search_s (5) = how far back from the chunk end to look for the quietest 200 ms to cut at
"""
import argparse, json, subprocess, sys, time, urllib.request, wave
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from common import HUMAN, script, wer, cer

SR = 16000
CANARY = "https://huggingface.co/fussraider/canary-1b-v2-sherpa-onnx-int8/resolve/main/"


def to_wav(client, work, ffmpeg_filter=""):
    wav = work / f"{client}.wav"
    if not wav.exists():
        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", str(HUMAN / client / "s01.m4a"), "-ac", "1", "-ar", str(SR)]
        if ffmpeg_filter:
            cmd += ["-af", ffmpeg_filter]
        subprocess.run(cmd + ["-c:a", "pcm_s16le", str(wav)], check=True)
    return wav


def read(wav):
    import numpy as np
    with wave.open(str(wav)) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype("float32") / 32768


def whisper(engine, wav, lang, o):
    from faster_whisper import WhisperModel
    task = "translate" if engine.endswith("-translate") else "transcribe"  # translate: speech in any language -> English text
    name = engine.removeprefix("whisper-").removesuffix("-translate")
    m = WhisperModel(name, device="cpu", compute_type=o.get("compute_type", "int8"), cpu_threads=4)
    prompt = o.get("initial_prompt")
    if isinstance(prompt, dict):
        prompt = prompt.get(lang)
    kw = dict(language=lang, task=task, beam_size=o.get("beam_size", 1), temperature=o.get("temperature", 0.0),
              vad_filter=o.get("vad", True), vad_parameters={"min_silence_duration_ms": o.get("vad_min_silence_ms", 2000)},
              condition_on_previous_text=o.get("condition_on_previous_text", False), initial_prompt=prompt,
              no_speech_threshold=o.get("no_speech_threshold", 0.6), repetition_penalty=o.get("repetition_penalty", 1.0),
              length_penalty=o.get("length_penalty", 1.0), patience=o.get("patience", 1.0), hotwords=o.get("hotwords"))
    segs, _ = m.transcribe(read(wav), **kw)
    return [{"start": round(s.start, 2), "end": round(s.end, 2), "text": s.text.strip()} for s in segs]


def canary(wav, lang, work, o):
    import numpy as np, sherpa_onnx
    d = work / "canary"
    d.mkdir(exist_ok=True)
    for n in ("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"):
        if not (d / n).exists():
            urllib.request.urlretrieve(CANARY + n, d / n)
    r = sherpa_onnx.OfflineRecognizer.from_nemo_canary(
        encoder=str(d / "encoder.int8.onnx"), decoder=str(d / "decoder.int8.onnx"), tokens=str(d / "tokens.txt"),
        src_lang=lang, tgt_lang=lang, num_threads=4, decoding_method="greedy_search")
    x = read(wav)
    step, back, win, pos, n = int(o.get("chunk_s", 20) * SR), int(o.get("search_s", 5) * SR), int(0.2 * SR), 0, len(x)
    cuts = []
    while n - pos > step:  # cut at the quietest 200 ms in the last search_s seconds of the window
        e = [(float((x[i:i + win] ** 2).mean()), i) for i in range(pos + step - back, pos + step - win, win // 2)]
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
    ap.add_argument("--id", default="", help="label of this variant (default: the engine)")
    ap.add_argument("--opts", default="{}")
    ap.add_argument("--client", required=True)
    ap.add_argument("--lane", type=int, default=1)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    vid, o = a.id or a.engine, json.loads(a.opts)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    sc = script(a.client)
    lang = sc["lang"]
    wav = to_wav(a.client, out, o.get("ffmpeg_filter", ""))
    t0 = time.time()
    segs = canary(wav, lang, out, o) if a.engine == "canary-1b-v2" else whisper(a.engine, wav, lang, o)
    secs = time.time() - t0
    with wave.open(str(wav)) as w:
        dur = w.getnframes() / SR
    ref = " ".join(t["text"] for t in sc["turns"])
    hyp = " ".join(s["text"] for s in segs)
    translated = a.engine.endswith("-translate")  # English output: WER against the Czech script is meaningless, read it instead
    res = {"engine": vid, "base": a.engine, "opts": o, "client": a.client, "lang": lang, "lane": a.lane, "audio_s": round(dur, 1), "seconds": round(secs, 1),
           "rtf": round(secs / dur, 3), "wer": None if translated else round(wer(ref, hyp, lang), 4), "cer": None if translated else round(cer(ref, hyp, lang), 4), "segments": segs}
    (out / f"{vid}__{a.client}__lane{a.lane}.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
    print(vid, a.client, "lane", a.lane, "WER", res["wer"], "CER", res["cer"], "RTF", res["rtf"])


if __name__ == "__main__":
    main()
