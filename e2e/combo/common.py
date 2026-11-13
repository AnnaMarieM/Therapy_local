"""Shared bits of the ASR x LLM combo test: corpus access, text normalisation, WER."""
import json, re, unicodedata
from pathlib import Path

HUMAN = Path(__file__).resolve().parents[1] / "corpus" / "human"
CLIENTS = ["jana-n", "tomas-b", "daniel-r", "hannah-w"]


def script(client):
    return json.loads((HUMAN / client / "s01.json").read_text(encoding="utf-8"))


EN_ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
EN_TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()
CS_ONES = "nula jedna dva tri ctyri pet sest sedm osm devet deset jedenact dvanact trinact ctrnact patnact sestnact sedmnact osmnact devatenact".split()
CS_TENS = "_ _ dvacet tricet ctyricet padesat sedesat sedmdesat osmdesat devadesat".split()


def number_words(n, lang):
    """0-999 as words (Czech in the nominative, diacritics folded) so '7' and 'seven' compare equal."""
    ones, tens = (CS_ONES, CS_TENS) if lang == "cs" else (EN_ONES, EN_TENS)
    if n < 20:
        return [ones[n]]
    if n < 100:
        return [tens[n // 10]] + ([ones[n % 10]] if n % 10 else [])
    if n < 1000:
        if lang == "en":
            head = [ones[n // 100], "hundred"]
        else:
            head = ([] if n // 100 == 1 else [ones[n // 100]]) + ["sto"]
        return head + (number_words(n % 100, lang) if n % 100 else [])
    return [str(n)]


def norm_words(text, lang=None):
    t = unicodedata.normalize("NFD", text.lower())
    t = "".join(c for c in t if unicodedata.category(c) != "Mn")
    t = re.sub(r"<[^>]*>|\[[^\]]*\]", " ", t)  # special tokens
    out = []
    for w in re.findall(r"[a-z]+|[0-9]+", t):
        out += number_words(int(w), lang) if lang and w.isdigit() else [w]
    return out


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def wer(ref, hyp, lang=None):
    r, h = norm_words(ref, lang), norm_words(hyp, lang)
    return edit_distance(r, h) / max(1, len(r))


def cer(ref, hyp, lang=None):
    r, h = list("".join(norm_words(ref, lang))), list("".join(norm_words(hyp, lang)))
    return edit_distance(r, h) / max(1, len(r))
