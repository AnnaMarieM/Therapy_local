#!/usr/bin/env python3
"""Downloads N random clips of FLEURS cs_cz test into OUT as 16 kHz mono wav + reference txt. Public speech only.
FLEURS clips are 16 kHz mono 32-bit float wav; they are converted to 16-bit with the standard library only (no ffmpeg on the runner).
Resumable and retrying like qa/fetch.sh."""
import argparse, array, csv, os, random, struct, subprocess, sys, tarfile, wave


def float_wav_to_pcm16(raw, dst):
    """16 kHz mono float32 (or already 16-bit) RIFF bytes -> 16-bit PCM wav file."""
    pos, fmt, data = 12, None, None
    while pos + 8 <= len(raw):
        cid, size = raw[pos:pos + 4], struct.unpack("<I", raw[pos + 4:pos + 8])[0]
        if cid == b"fmt ":
            fmt = struct.unpack("<HHIIHH", raw[pos + 8:pos + 24])
        elif cid == b"data":
            data = raw[pos + 8:pos + 8 + size]
            break
        pos += 8 + size + (size & 1)
    tag, channels, rate, _, _, bits = fmt
    assert (channels, rate) == (1, 16000), f"expected 16 kHz mono, got {channels} ch {rate} Hz"
    if tag == 3 and bits == 32:
        f = array.array("f"); f.frombytes(data[: len(data) // 4 * 4])
        if sys.byteorder == "big": f.byteswap()
        pcm = array.array("h", (max(-32768, min(32767, int(round(v * 32767)))) for v in f))
    elif tag == 1 and bits == 16:
        pcm = array.array("h"); pcm.frombytes(data[: len(data) // 2 * 2])
        if sys.byteorder == "big": pcm.byteswap()
    else:
        raise SystemExit(f"unsupported wav format tag {tag}, {bits} bits")
    if sys.byteorder == "big": pcm.byteswap()
    with wave.open(dst, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(pcm.tobytes())


BASE = "https://huggingface.co/datasets/google/fleurs/resolve/main/data/cs_cz"
def main():
    a = argparse.ArgumentParser(); a.add_argument("--count", type=int, required=True); a.add_argument("--out", required=True)
    args = a.parse_args()
    os.makedirs(args.out, exist_ok=True)
    here = os.path.dirname(os.path.abspath(__file__))
    fetch = os.path.join(here, "..", "fetch.sh")
    for url, out in ((f"{BASE}/test.tsv", "test.tsv"), (f"{BASE}/audio/test.tar.gz", "test.tar.gz")):
        subprocess.run([fetch, url, out], check=True)
    rows = list(csv.reader(open("test.tsv", encoding="utf-8"), delimiter="\t"))
    random.Random(11).shuffle(rows)
    want, seen = {}, set()
    for r in rows:
        if r[1] not in seen:
            seen.add(r[1]); want[r[1]] = r[2]
        if len(want) == args.count:
            break
    done = 0
    with tarfile.open("test.tar.gz", "r:gz") as t:
        for m in t:
            b = os.path.basename(m.name)
            if b in want:
                dst = os.path.join(args.out, f"cs_fleurs_{done:03d}")
                float_wav_to_pcm16(t.extractfile(m).read(), dst + ".wav")
                open(dst + ".txt", "w", encoding="utf-8").write(want[b]); done += 1
                if done == len(want):
                    break
    print(f"{done} clips in {args.out}")


if __name__ == "__main__":
    main()
