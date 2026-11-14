"""Whisper large-v3 (faster-whisper, CTranslate2 int8_float16, T4) per turn.
  wh.py --task transcribe|translate --beam 1|5 --prompt 0|1 --out DIR [--clients ...] [--limit N]
Pieces from /content/w/turns-timed/<client>.json (cut_start..cut_end; >30 s split at the quietest point, as g4.py).
Output DIR/<client>/turns.jsonl (raw text per piece, untouched) + timing.json."""
import argparse, json, os, sys, time
sys.path.insert(0, "/content/w")
import numpy as np
from g4 import load_wav, split_piece
from faster_whisper import WhisperModel
import faster_whisper

PROMPT = {"cs": "Rozhovor v psychoterapii mezi terapeutem a klientem. Klient mluví o rodině, práci, spánku, úzkosti a pocitech.",
          "en": "A psychotherapy session between a therapist and a client. The client talks about family, work, sleep, anxiety and feelings."}
ap = argparse.ArgumentParser()
ap.add_argument("--task", required=True); ap.add_argument("--beam", type=int, default=5); ap.add_argument("--prompt", type=int, default=0)
ap.add_argument("--out", required=True); ap.add_argument("--clients", default="jana-n,tomas-b,daniel-r,hannah-w"); ap.add_argument("--limit", type=int, default=0)
a = ap.parse_args()
t0 = time.time()
m = WhisperModel("large-v3", device="cuda", compute_type="int8_float16")
load_s = round(time.time() - t0, 1)
for c in a.clients.split(","):
    tt = json.load(open(f"/content/w/turns-timed/{c}.json"))
    lang = tt["lang"]
    task = a.task
    if lang == "en":
        task = "transcribe"  # English audio: nothing to translate
    # the translate task produces English, so the English domain prompt is used for it; the transcribe task uses the language's own prompt
    pr = (PROMPT["en"] if task == "translate" else PROMPT[lang]) if a.prompt else None
    x = load_wav(c)
    rows, ta = [], time.time()
    for t in tt["turns"][: a.limit or None]:
        seg = x[int(t["cut_start"] * 16000): int(t["cut_end"] * 16000)]
        outs, secs = [], 0.0
        pcs = split_piece(seg)
        for p in pcs:
            s0 = time.time()
            segs, _ = m.transcribe(p.astype("float32") / 32768, language=lang, task=task, beam_size=a.beam, initial_prompt=pr, vad_filter=False,
                                   condition_on_previous_text=False, temperature=0.0)
            outs.append(" ".join(s.text.strip() for s in segs).strip())
            secs += time.time() - s0
        rows.append(dict(idx=t["idx"], who=t["who"], cut_start=t["cut_start"], cut_end=t["cut_end"], pieces=len(pcs), audio_s=round(len(seg) / 16000, 2), seconds=round(secs, 2), raw=outs))
    wall = time.time() - ta
    os.makedirs(f"{a.out}/{c}", exist_ok=True)
    with open(f"{a.out}/{c}/turns.jsonl", "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    json.dump(dict(client=c, engine="whisper large-v3 faster-whisper %s CTranslate2 int8_float16" % faster_whisper.__version__, task=task, beam=a.beam, prompt=pr, language=lang,
                   model_load_s=load_s, listen_wall_s=round(wall, 1), audio_in_pieces_s=round(sum(r["audio_s"] for r in rows), 1), rtf=round(wall / max(1, sum(r["audio_s"] for r in rows)), 3),
                   n_pieces=sum(r["pieces"] for r in rows), started=time.strftime("%Y-%m-%d %H:%M:%S")), open(f"{a.out}/{c}/timing.json", "w"), indent=1)
    print("DONE", c, a.task, a.beam, a.prompt, round(wall, 1), "s", flush=True)
