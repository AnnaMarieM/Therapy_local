"""Shared bits of the ASR x LLM combo test: corpus access, text normalisation, WER."""
import json, re, unicodedata
from pathlib import Path

HUMAN = Path(__file__).resolve().parents[1] / "corpus" / "human"
CLIENTS = ["jana-n", "tomas-b", "daniel-r", "hannah-w"]


def script(client):
    return json.loads((HUMAN / client / "s01.json").read_text(encoding="utf-8"))


def norm_words(text):
    t = unicodedata.normalize("NFD", text.lower())
    t = "".join(c for c in t if unicodedata.category(c) != "Mn")
    t = re.sub(r"<[^>]*>|\[[^\]]*\]", " ", t)  # special tokens
    return re.findall(r"[a-z0-9]+", t)


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def wer(ref, hyp):
    r, h = norm_words(ref), norm_words(hyp)
    return edit_distance(r, h) / max(1, len(r))


def cer(ref, hyp):
    r, h = list("".join(norm_words(ref))), list("".join(norm_words(hyp)))
    return edit_distance(r, h) / max(1, len(r))
