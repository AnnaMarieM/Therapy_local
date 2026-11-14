import time
from faster_whisper import WhisperModel
t=time.time()
try:
    m = WhisperModel("large-v3", device="cuda", compute_type="int8_float16")
    print("cuda ok", time.time()-t)
except Exception as e:
    print("ERR", repr(e)[:600])
