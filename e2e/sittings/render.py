#!/usr/bin/env python3
"""Render sittings (sessions/<id>.json) to 16 kHz mono PCM16 WAV with Edge TTS, plus <id>.truth.json (turn timings and expected roles).

  render.py [--only id,id] [--out DIR] [--force] [--check]

Each turn is synthesised by its speaker's Edge voice (online, Microsoft neural voices; the scripts are synthetic, no patient data), decoded and
resampled to 16 kHz, trimmed, and placed on a timeline with seeded random gaps. Per turn options: pause, overlap (interruption), at (back-channel
inside the previous turn). Per sitting effects (fx): <who>.gain_db, <who>.reverb_rt60, <who>.reverb_mix, <who>.rate, <who>.pitch,
gap_min / gap_max (s), noise_snr_db (pink noise, generated locally with numpy). Single turns are cached by content hash in cache/;
unchanged sittings are skipped. --check prints per-turn duration and level and flags empty or silent turns. Audio and cache are not committed.
"""
import argparse, asyncio, hashlib, json, random, sys, wave
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
SR = 16000
LEAD_S, TAIL_S = 0.6, 1.0
GAP = (0.35, 0.9)
RENDER_VERSION = "1"


def turn_key(voice, rate, pitch, text):
    return hashlib.sha256(json.dumps([RENDER_VERSION, voice, rate, pitch, text], ensure_ascii=False).encode()).hexdigest()[:20]


def read_wav(p: Path) -> np.ndarray:
    with wave.open(str(p), "rb") as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768


def write_wav(p: Path, x: np.ndarray):
    p.parent.mkdir(parents=True, exist_ok=True)
    pcm = np.clip(np.round(x * 32768), -32768, 32767).astype(np.int16)
    with wave.open(str(p), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(pcm.tobytes())


async def synth_one(voice, rate, pitch, text, dest: Path, sem):
    import edge_tts, miniaudio
    async with sem:
        for attempt in range(6):
            try:
                mp3 = b""
                async for c in edge_tts.Communicate(text, voice, rate=rate, pitch=pitch).stream():
                    if c["type"] == "audio":
                        mp3 += c["data"]
                if not mp3:
                    raise RuntimeError("no audio")
                d = miniaudio.decode(mp3, output_format=miniaudio.SampleFormat.SIGNED16, nchannels=1, sample_rate=SR)
                pcm = np.frombuffer(d.samples, dtype=np.int16).astype(np.float32) / 32768
                write_wav(dest, pcm)
                return
            except Exception as e:  # network hiccups, throttling
                if attempt == 5:
                    raise
                await asyncio.sleep(1.5 * (attempt + 1))


async def synth_all(doc, cache: Path):
    sem = asyncio.Semaphore(4)
    jobs, paths = [], []
    fx = doc["fx"]
    for t in doc["turns"]:
        who = t["who"]
        rate, pitch = fx.get(f"{who}.rate", "+0%"), fx.get(f"{who}.pitch", "+0Hz")
        p = cache / f"{turn_key(doc['voices'][who], rate, pitch, t['text'])}.wav"
        paths.append(p)
        if not p.exists():
            jobs.append(synth_one(doc["voices"][who], rate, pitch, t["text"], p, sem))
    if jobs:
        await asyncio.gather(*jobs)
    return paths


def trim(x: np.ndarray, pad_s=0.05) -> np.ndarray:
    thr = max(0.006, 0.02 * float(np.abs(x).max() or 1))
    idx = np.nonzero(np.abs(x) > thr)[0]
    if len(idx) == 0:
        return x[:0]
    pad = int(pad_s * SR)
    return x[max(0, idx[0] - pad): idx[-1] + pad]


def reverb(x: np.ndarray, rt60: float, mix: float, rng: np.random.Generator) -> np.ndarray:
    n = int(rt60 * SR)
    t = np.arange(n) / SR
    ir = rng.standard_normal(n).astype(np.float32) * np.exp(-6.9 * t / rt60).astype(np.float32)
    ir[: int(0.012 * SR)] = 0
    ir[int(0.012 * SR)] += 6 * ir.std()  # a first reflection
    ir /= np.sqrt((ir ** 2).sum()) + 1e-9
    n_fft = 1 << int(np.ceil(np.log2(len(x) + n)))
    wet = np.fft.irfft(np.fft.rfft(x, n_fft) * np.fft.rfft(ir, n_fft), n_fft)[: len(x) + n].astype(np.float32)
    dry = np.concatenate([x, np.zeros(n, np.float32)])
    wet *= np.sqrt((dry ** 2).sum() / ((wet ** 2).sum() + 1e-12))  # same energy as dry
    return (1 - mix) * dry + mix * wet


def pink_noise(n: int, rng: np.random.Generator) -> np.ndarray:
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.arange(len(spec)); f[0] = 1
    x = np.fft.irfft(spec / np.sqrt(f), n)
    return (x / x.std()).astype(np.float32)


def rms_db(x: np.ndarray) -> float:
    return round(20 * np.log10(float(np.sqrt((x.astype(np.float64) ** 2).mean())) + 1e-9), 1) if len(x) else -120.0


def assemble(doc, paths):
    fx = doc["fx"]
    rng = random.Random(doc["id"])
    nrng = np.random.default_rng(int.from_bytes(hashlib.sha256(doc["id"].encode()).digest()[:4], "big"))
    gmin, gmax = float(fx.get("gap_min", GAP[0])), float(fx.get("gap_max", GAP[1]))
    placed, cursor, prev = [], LEAD_S, None
    for i, (t, p) in enumerate(zip(doc["turns"], paths)):
        who = t["who"]
        dry = trim(read_wav(p))
        wav = dry * (10 ** (float(fx.get(f"{who}.gain_db", 0)) / 20))
        if f"{who}.reverb_rt60" in fx:
            wav = reverb(wav, float(fx[f"{who}.reverb_rt60"]), float(fx.get(f"{who}.reverb_mix", 0.4)), nrng)
        dur = len(dry) / SR
        if prev is not None and "at" in t:
            start = prev["start"] + t["at"] * prev["dur"]
        elif prev is not None and "overlap" in t:
            start = prev["start"] + prev["dur"] - t["overlap"]
        else:
            start = cursor + rng.uniform(gmin, gmax) + t.get("pause", 0)
        prev = {"start": start, "dur": dur}
        cursor = max(cursor, start + dur)
        placed.append({"i": i, "who": who, "start": start, "dur": dur, "wav": wav, "dry_rms": rms_db(dry)})
    total = int((cursor + TAIL_S) * SR)
    mix = np.zeros(total + SR, np.float32)
    for pl in placed:
        s = int(pl["start"] * SR)
        mix[s: s + len(pl["wav"])] += pl["wav"]
    mix = mix[:total]
    snr = fx.get("noise_snr_db")
    if snr is not None:
        win = SR // 5
        frames = mix[: len(mix) // win * win].reshape(-1, win)
        lvl = np.sqrt((frames ** 2).mean(1))
        active = lvl > 0.1 * lvl.max()
        speech_rms = float(np.sqrt((frames[active] ** 2).mean()))
        noise = pink_noise(len(mix), nrng)
        mix = mix + noise * (speech_rms / (10 ** (float(snr) / 20)))
    peak = float(np.abs(mix).max())
    scale = 0.9 / peak if peak > 0.9 else 1.0  # one global scale: level ratios between speakers are kept
    mix *= scale
    return mix, placed, scale


def render(doc, out: Path, cache: Path, force=False):
    wav_path, truth_path = out / f"{doc['id']}.wav", out / f"{doc['id']}.truth.json"
    paths = asyncio.run(synth_all(doc, cache))
    digest = hashlib.sha256(json.dumps([RENDER_VERSION, doc, [p.name for p in paths]], sort_keys=True, ensure_ascii=False).encode()).hexdigest()[:16]
    if not force and wav_path.exists() and truth_path.exists() and json.loads(truth_path.read_text())["hash"] == digest:
        return json.loads(truth_path.read_text()), False
    mix, placed, scale = assemble(doc, paths)
    write_wav(wav_path, mix)
    turns = []
    for pl, t in zip(placed, doc["turns"]):
        s0 = int(pl["start"] * SR)
        seg = mix[s0: s0 + int(pl["dur"] * SR)]
        turns.append({"idx": pl["i"], "speaker": t["who"], "start_ms": int(round(pl["start"] * 1000)), "end_ms": int(round((pl["start"] + pl["dur"]) * 1000)),
                      "text": t["text"], "dry_dbfs": pl["dry_rms"], "mixed_dbfs": rms_db(seg)})
    truth = {"hash": digest, "id": doc["id"], "client": doc["client"], "lang": doc["lang"], "date": doc["date"], "tag": doc["tag"], "what": doc["what"],
             "voices": doc["voices"], "fx": doc["fx"], "expect": doc["expect"], "duration_ms": int(len(mix) / SR * 1000), "scale": round(scale, 3),
             "turns": turns, "facts": doc["facts"]}
    truth_path.write_text(json.dumps(truth, ensure_ascii=False, indent=1), encoding="utf-8")
    return truth, True


def check(truth) -> list:
    problems = []
    for t in truth["turns"]:
        d = (t["end_ms"] - t["start_ms"]) / 1000
        words = len(t["text"].split())
        if d < 0.15 or t["mixed_dbfs"] < -60:
            problems.append(f"turn {t['idx']} ({t['speaker']}) empty or silent: {d:.2f}s {t['mixed_dbfs']} dBFS")
        elif d > 0.7 * words + 3 or d < 0.12 * words:
            problems.append(f"turn {t['idx']} ({t['speaker']}) odd length: {words} words in {d:.2f}s")
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sessions", default=str(HERE / "sessions"))
    ap.add_argument("--out", default=str(HERE / "audio"))
    ap.add_argument("--cache", default=str(HERE / "cache"))
    ap.add_argument("--only")
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    files = sorted(Path(a.sessions).glob("*.json"))
    if a.only:
        files = [f for f in files if f.stem in a.only.split(",")]
    total, bad = 0, 0
    for f in files:
        doc = json.loads(f.read_text(encoding="utf-8"))
        truth, did = render(doc, Path(a.out), Path(a.cache), a.force)
        total += truth["duration_ms"]
        by = {}
        for t in truth["turns"]:
            by.setdefault(t["speaker"], []).append(t["mixed_dbfs"])
        levels = " ".join(f"{k}:{sum(v) / len(v):.1f}dBFS" for k, v in sorted(by.items()))
        probs = check(truth)
        bad += len(probs)
        print(f"{'rendered' if did else 'cached  '} {doc['id']:13s} {truth['duration_ms'] / 60000:5.1f} min  {len(truth['turns']):3d} turns  {levels}", flush=True)
        for p in probs:
            print("   PROBLEM", p)
    print(f"{len(files)} sittings, {total / 60000:.1f} min total, {bad} problems")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
