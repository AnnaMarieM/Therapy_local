#!/usr/bin/env python3
"""Build repeatable test audio from known-speech WAVs and push it to the phone (debug builds replay it through the real chunker).

Source: the sherpa-onnx Whisper test wavs with reference transcripts,
https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/tree/main/test_wavs (0.wav, 1.wav, 8k.wav and trans.txt).

Usage: make_test_audio.py <dir with 0.wav 1.wav 8k.wav> <out dir>
Writes <out>/long.wav (about 56 s, three sentences with gaps, so ChunkConfig gives 4 overlapping chunks) and short single-sentence
recordings s0.wav, s1.wav, s8k.wav plus voice queries: q0.wav, q8k.wav (identical audio, a trivial match) and qn0.wav, qn8k.wav
(half volume with noise, a fairer test). Pure standard library, no numpy.
"""
import array
import random
import sys
import wave

RATE = 16000


def read(path):
    with wave.open(path, "rb") as w:
        assert w.getsampwidth() == 2 and w.getnchannels() == 1, path
        data = array.array("h", w.readframes(w.getnframes()))
        rate = w.getframerate()
    if rate == RATE:
        return data
    out = array.array("h")
    n = int(len(data) * RATE / rate)
    for i in range(n):
        src = i * rate / RATE
        i0 = min(int(src), len(data) - 1)
        i1 = min(i0 + 1, len(data) - 1)
        f = src - i0
        out.append(int(data[i0] * (1 - f) + data[i1] * f))
    return out


def silence(seconds):
    return array.array("h", [0] * int(seconds * RATE))


def write(path, samples):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(samples.tobytes())
    print(f"{path}  {len(samples) / RATE:.1f}s")


def main(src, out):
    s0, s1, s8 = (read(f"{src}/{n}.wav") for n in ("0", "1", "8k"))
    gap = silence(1.2)
    long = s0 + gap + s1 + gap + s8 + gap + s1 + gap + s0
    write(f"{out}/long.wav", long)
    write(f"{out}/s0.wav", s0)
    write(f"{out}/s1.wav", s1)
    write(f"{out}/s8k.wav", s8)
    write(f"{out}/q0.wav", s0)
    write(f"{out}/q8k.wav", s8)
    # Voice queries whose waveform differs from what was indexed: half volume plus white noise (fixed seed, repeatable).
    write(f"{out}/qn0.wav", noisy(s0))
    write(f"{out}/qn8k.wav", noisy(s8))


def noisy(samples, gain=0.5, sigma=250.0, seed=7):
    rng = random.Random(seed)
    return array.array("h", [max(-32768, min(32767, int(s * gain + rng.gauss(0, sigma)))) for s in samples])


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2])
