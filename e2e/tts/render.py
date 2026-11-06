#!/usr/bin/env python3
"""Render session scripts (e2e/corpus/**/sNN.yaml-as-json) to 16 kHz mono WAV with Piper.

Usage: render.py [--corpus DIR] [--out DIR] [--only client/sNN[,client/sNN...]] [--force]
Audio is cached by sha256 of (voice, params, text); unchanged scripts are skipped.
"""
import argparse, hashlib, json, sys, wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VOICES = ROOT / "voices"

# role -> voice settings per language. Czech has a single Piper voice, so roles differ by pacing.
VOICE_MAP = {
    "en": {"therapist": ("en_US-lessac-medium", 1.0), "client": ("en_US-ryan-medium", 1.05)},
    "cs": {"therapist": ("cs_CZ-jirka-medium", 1.0), "client": ("cs_CZ-jirka-medium", 1.12)},
}
SAMPLE_RATE = 16000
PAUSE_S = 0.6
LEAD_S = 0.5


def resample(pcm16: bytes, src: int, dst: int) -> bytes:
    import array
    a = array.array("h")
    a.frombytes(pcm16)
    if src == dst:
        return a.tobytes()
    n_out = int(len(a) * dst / src)
    out = array.array("h", [0]) * n_out
    for i in range(n_out):
        pos = i * src / dst
        j = int(pos)
        f = pos - j
        s0 = a[j]
        s1 = a[j + 1] if j + 1 < len(a) else s0
        out[i] = int(s0 + (s1 - s0) * f)
    return out.tobytes()


def load_voice(cache, name):
    if name not in cache:
        from piper import PiperVoice
        cache[name] = PiperVoice.load(str(VOICES / f"{name}.onnx"), config_path=str(VOICES / f"{name}.onnx.json"))
    return cache[name]


def synth(voice, text, length_scale):
    from piper import SynthesisConfig
    chunks = voice.synthesize(text, syn_config=SynthesisConfig(length_scale=length_scale))
    pcm = b"".join(c.audio_int16_bytes for c in chunks)
    return resample(pcm, voice.config.sample_rate, SAMPLE_RATE)


def script_hash(lang, turns):
    h = hashlib.sha256()
    h.update(json.dumps([lang, VOICE_MAP[lang], PAUSE_S, turns], sort_keys=True, ensure_ascii=False).encode())
    return h.hexdigest()[:16]


def render_script(path: Path, out_dir: Path, cache, force=False):
    s = json.loads(path.read_text(encoding="utf-8"))
    lang, turns = s["lang"], s["turns"]
    wav_path = out_dir / s["client"] / f"{s['session']}.wav"
    meta_path = wav_path.with_suffix(".json")
    digest = script_hash(lang, turns)
    if not force and wav_path.exists() and meta_path.exists() and json.loads(meta_path.read_text())["hash"] == digest:
        return wav_path, False
    wav_path.parent.mkdir(parents=True, exist_ok=True)
    silence = b"\x00\x00" * int(SAMPLE_RATE * PAUSE_S)
    pcm = b"\x00\x00" * int(SAMPLE_RATE * LEAD_S)
    for t in turns:
        vname, scale = VOICE_MAP[lang][t["who"]]
        pcm += synth(load_voice(cache, vname), t["text"], scale) + silence
    with wave.open(str(wav_path), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SAMPLE_RATE)
        w.writeframes(pcm)
    meta_path.write_text(json.dumps({"hash": digest, "seconds": len(pcm) / 2 / SAMPLE_RATE}))
    return wav_path, True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default=str(ROOT / "corpus" / "sessions"))
    ap.add_argument("--out", default=str(ROOT / "audio"))
    ap.add_argument("--only")
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    files = sorted(Path(a.corpus).glob("*/s*.json"))
    if a.only:
        wanted = a.only.split(",")
        files = [f for f in files if f"{f.parent.name}/{f.stem}" in wanted]
    cache = {}
    for f in files:
        wav, did = render_script(f, Path(a.out), cache, a.force)
        print(("rendered " if did else "cached   ") + str(wav.relative_to(Path(a.out))), flush=True)


if __name__ == "__main__":
    sys.exit(main())
