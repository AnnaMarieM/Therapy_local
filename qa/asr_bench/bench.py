#!/usr/bin/env python3
"""Word error rate and speed of one speech model on a folder of clips, using the same sherpa-onnx runtime as the app.

  bench.py --engine whisper --model-dir DIR --name small --language cs --clips CLIPS --out result.json
  bench.py --engine parakeet|canary|qwen3|omnilingual --name LABEL --model-dir DIR --clips CLIPS --out result.json

CLIPS holds NAME.wav (16 kHz mono PCM16) with NAME.txt, the reference. Clips are cut like the app does (20 s windows, 15 s apart).
"""
import argparse, glob, json, os, re, time, wave
import numpy as np
import sherpa_onnx

SR = 16000
WINDOW, STEP = 20 * SR, 15 * SR


def words(text):
    return [w for w in re.sub(r"[^\w\s]|_", " ", text.lower()).split() if w]


def word_errors(ref, hyp):
    r, h = words(ref), words(hyp)
    prev = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        cur = [i] + [0] * len(h)
        for j in range(1, len(h) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r[i - 1] != h[j - 1]))
        prev = cur
    return prev[len(h)], len(r)


def read_wav(path):
    with wave.open(path) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, f"{path}: need 16 kHz mono PCM16"
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0


def chunks(x):
    out, at = [], 0
    while at < len(x):
        out.append(x[at:at + WINDOW])
        if at + WINDOW >= len(x):
            break
        at += STEP
    return out


def build(args):
    d = args.model_dir
    if args.engine == "whisper":
        n = args.name
        return sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=f"{d}/{n}-encoder.int8.onnx", decoder=f"{d}/{n}-decoder.int8.onnx", tokens=f"{d}/{n}-tokens.txt",
            language="" if n.endswith(".en") else args.language, task="transcribe", num_threads=args.threads, provider="cpu",
        )
    if args.engine == "canary":   # Canary-1B-v2: 25 European languages; source and target language the same means plain transcription
        return sherpa_onnx.OfflineRecognizer.from_nemo_canary(
            encoder=f"{d}/encoder.int8.onnx", decoder=f"{d}/decoder.int8.onnx", tokens=f"{d}/tokens.txt",
            src_lang=args.language, tgt_lang=args.language, num_threads=args.threads, provider="cpu",
        )
    if args.engine == "qwen3":
        return sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
            conv_frontend=f"{d}/conv_frontend.onnx", encoder=f"{d}/encoder.int8.onnx", decoder=f"{d}/decoder.int8.onnx",
            tokenizer=f"{d}/tokenizer", num_threads=args.threads, provider="cpu",
        )
    if args.engine == "omnilingual":   # CTC over 1600 languages, no language setting
        return sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
            model=f"{d}/model.int8.onnx", tokens=f"{d}/tokens.txt", num_threads=args.threads, provider="cpu",
        )
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{d}/encoder.int8.onnx", decoder=f"{d}/decoder.int8.onnx", joiner=f"{d}/joiner.int8.onnx", tokens=f"{d}/tokens.txt",
        model_type="nemo_transducer", num_threads=args.threads, provider="cpu", feature_dim=128,
    )


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--engine", choices=["whisper", "parakeet", "canary", "qwen3", "omnilingual"], required=True)
    a.add_argument("--model-dir", required=True)
    a.add_argument("--name", default="")
    a.add_argument("--language", default="cs")
    a.add_argument("--clips", required=True)
    a.add_argument("--out", required=True)
    a.add_argument("--threads", type=int, default=4)
    a.add_argument("--limit", type=int, default=0, help="only the first N clips (0 = all)")
    args = a.parse_args()

    rec = build(args)
    files = sorted(glob.glob(os.path.join(args.clips, "*.wav")))
    if args.limit:
        files = files[:args.limit]
    errors = total = 0
    decode = audio = 0.0
    clips = []
    for f in files:
        x = read_wav(f)
        t0 = time.time()
        text = []
        for c in chunks(x):
            s = rec.create_stream()
            s.accept_waveform(SR, c)
            rec.decode_stream(s)
            text.append(s.result.text.strip())
        decode += time.time() - t0
        audio += len(x) / SR
        hyp = " ".join(text).strip()
        ref = open(f[:-4] + ".txt", encoding="utf-8").read().strip()
        e, n = word_errors(ref, hyp)
        errors += e
        total += n
        clips.append({"clip": os.path.basename(f), "wer": round(100 * e / max(n, 1), 1), "ref": ref, "hyp": hyp})
    label = args.name or "parakeet-tdt-0.6b-v3"
    result = {"model": f"whisper-{label}" if args.engine == "whisper" else label, "engine": args.engine, "language": args.language,
              "wer": round(100 * errors / max(total, 1), 2), "words": total, "clips": len(files),
              "rtf": round(decode / max(audio, 1e-9), 3), "audio_sec": round(audio, 1), "details": clips}
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    json.dump(result, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"{result['model']}: WER {result['wer']}% over {len(files)} clips ({total} words), rtf {result['rtf']}")


if __name__ == "__main__":
    main()
