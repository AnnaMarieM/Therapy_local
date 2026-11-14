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


def _key(token):
    t = unicodedata.normalize("NFD", token.lower())
    return re.sub(r"[^a-z0-9]", "", "".join(c for c in t if unicodedata.category(c) != "Mn"))


def oracle_turns(client, hyp_text):
    """Speaker turns of an ASR transcript with perfect diarization: every heard word takes the speaker of the script word it aligns to.

    Returns [(who, text)], who = therapist | client, runs of one speaker merged into one turn. Used in CI where there is no diarizer, so that the roles
    and extraction steps can be tested on realistic dialogue structure. Single words that align to nothing and sit between two words of the other speaker are folded in.
    """
    import difflib
    ref = [(_key(w), t["who"]) for t in script(client)["turns"] for w in t["text"].split() if _key(w)]
    hyp = [(w, _key(w)) for w in hyp_text.split() if _key(w)]
    sm = difflib.SequenceMatcher(None, [k for k, _ in ref], [k for _, k in hyp], autojunk=False)
    who, exact = [None] * len(hyp), [False] * len(hyp)
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op == "equal":
            for k in range(j2 - j1):
                who[j1 + k], exact[j1 + k] = ref[i1 + k][1], True
        elif op == "replace":
            for k in range(j2 - j1):
                who[j1 + k] = ref[i1 + (k * (i2 - i1)) // (j2 - j1)][1]
    last = None
    for k in range(len(hyp)):  # inserted words take the previous speaker
        if who[k] is None:
            who[k] = last
        last = who[k]
    nxt = None
    for k in range(len(hyp) - 1, -1, -1):
        if who[k] is None:
            who[k] = nxt
        nxt = who[k]
    for k in range(1, len(hyp) - 1):  # one stray word between two words of the other speaker
        if not exact[k] and who[k - 1] == who[k + 1] != who[k]:
            who[k] = who[k - 1]
    turns = []
    for (w, _), s in zip(hyp, who):
        if turns and turns[-1][0] == s:
            turns[-1][1].append(w)
        else:
            turns.append((s, [w]))
    return [(s, " ".join(ws)) for s, ws in turns]
