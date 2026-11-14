import json, sys, time
sys.path.insert(0, "/content/w")
import soundfile as sf
from faster_whisper import WhisperModel
import common
from pathlib import Path
common.HUMAN = Path("/content/w/repo/e2e/corpus/human")
from common import norm_words, wer
from g4 import load_wav
m = WhisperModel("large-v3", device="cuda", compute_type="int8_float16")
res = {}
for c in ["jana-n", "tomas-b", "daniel-r", "hannah-w"]:
    tt = json.load(open(f"/content/w/turns-timed/{c}.json")); T = tt["turns"]; n = len(T)
    x = load_wav(c); lang = tt["lang"]; rows = []
    for i in sorted({0, n // 6, 2 * n // 6, 3 * n // 6, 4 * n // 6, 5 * n // 6, n - 1}):
        t = T[i]; seg = x[int(t["cut_start"] * 16000): int(t["cut_end"] * 16000)].astype("float32") / 32768
        s, _ = m.transcribe(seg, language=lang, beam_size=5, vad_filter=False, condition_on_previous_text=False)
        h = " ".join(q.text.strip() for q in s)
        rw = norm_words(t["text"]); hw = norm_words(h)
        rows.append(dict(idx=i, who=t["who"], cut=[t["cut_start"], t["cut_end"]], script=t["text"], heard=h, wer=round(wer(t["text"], h, lang), 3), first_word_ok=bool(hw) and hw[0] == rw[0], last_word_ok=bool(hw) and hw[-1] == rw[-1]))
    res[c] = rows
    print(c, [r["wer"] for r in rows], flush=True)
json.dump(res, open("/content/out/spotcheck.json", "w"), ensure_ascii=False, indent=1)
