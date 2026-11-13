import json, time, sys
import soundfile as sf
sys.path.insert(0, "/content/w")
from faster_whisper import WhisperModel
import faster_whisper
m = WhisperModel("large-v3", device="cuda", compute_type="int8_float16")
for c, lang in [("jana-n","cs"),("tomas-b","cs"),("daniel-r","en"),("hannah-w","en")]:
    t = time.time()
    segs, info = m.transcribe(sf.read(f"/content/w/{c}_None.wav", dtype="float32")[0], language=lang, beam_size=5, word_timestamps=True, vad_filter=False, condition_on_previous_text=False)
    ws = []
    for s in segs:
        for w in s.words:
            ws.append(dict(w=w.word, s=round(w.start, 2), e=round(w.end, 2), p=round(w.probability, 3)))
    json.dump(dict(client=c, faster_whisper=faster_whisper.__version__, model="large-v3", compute="int8_float16", beam=5, duration=info.duration, seconds=round(time.time()-t,1), words=ws), open(f"/content/out/fwwords__{c}.json", "w"), ensure_ascii=False)
    print(c, len(ws), "words", info.duration, "s audio", round(time.time()-t, 1), "s", flush=True)
