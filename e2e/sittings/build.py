#!/usr/bin/env python3
"""Compile sittings/scripts/*.txt into sittings/sessions/<id>.json (committed, deterministic).

Script format (UTF-8, one file per sitting):
  # comment
  id: <client>_<sNN>                     the session id in the app (the .wav is <id>.wav)
  tag / what: short name and one-line description
  lang: cs|en
  client: <client id> | <display name>
  date: yyyy-MM-dd
  voice: therapist=<edge voice> patient=<edge voice>     (dictation: therapist only)
  fx: patient.gain_db=-13 patient.reverb_rt60=0.5 patient.reverb_mix=0.45 noise_snr_db=12 patient.rate=-5% ...   (optional)
  expect: therapist=therapist patient=patient     truth speaker -> expected role
                         dictation: limitation=AD-07 (one real voice; the app always asks for two speakers, so an invented second one is expected)
  ---
  T{p=0.4}: text        T therapist, P patient, D therapist dictating (single voice)
                        turn options: p=<pause s before the turn>, o=<s the turn starts before the previous one ends (interruption)>,
                                      at=<0..1: the turn starts at that fraction of the previous turn (back-channel inside it)>
  inline fact marker:   [[f3|Heading|the spoken sentence]]   or   [[f5=<client>_<sNN>.<fN>|Heading|sentence]] for a repeat of an earlier fact
Only therapist and patient exist. Each planted fact records who said it.
"""
import json, re, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
WHO = {"T": "therapist", "P": "patient", "D": "therapist"}
FACT = re.compile(r"\[\[(f\d+)(?:=([\w.-]+))?\|([^|\]]+)\|([^\]]+)\]\]")
TURN = re.compile(r"^([TPD])(?:\{([^}]*)\})?:\s*(.*)$")


def parse(path: Path):
    head, body, in_body = {}, [], False
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line == "---":
            in_body = True
        elif in_body:
            body.append(line)
        else:
            k, v = line.split(":", 1)
            head[k.strip()] = v.strip()
    sid = head["id"]
    turns, facts = [], []
    for line in body:
        m = TURN.match(line)
        if not m:
            sys.exit(f"{path.name}: bad line: {line[:60]}")
        who = WHO[m.group(1)]
        opts = {}
        for kv in (m.group(2) or "").split(","):
            if kv.strip():
                k, v = kv.split("=")
                opts[{"p": "pause", "o": "overlap", "at": "at"}[k.strip()]] = float(v)
        text = m.group(3)
        for fm in FACT.finditer(text):
            facts.append({"id": f"{sid}.{fm.group(1)}", "heading": fm.group(3).strip(), "said": fm.group(4).strip(),
                          "who": who, "turn": len(turns), "repeat_of": fm.group(2)})
        text = FACT.sub(lambda fm: fm.group(4).strip(), text)
        turns.append({"who": who, **opts, "text": " ".join(text.split())})
    ids = [f["id"] for f in facts]
    assert len(ids) == len(set(ids)), f"{sid}: duplicate fact ids"
    for f in facts:
        assert f["said"] in turns[f["turn"]]["text"], f"{sid}.{f['id']}: sentence not in its turn"
    client_id, client_name = [x.strip() for x in head["client"].split("|")]
    voices = dict(kv.split("=") for kv in head["voice"].split())
    fx = dict(kv.split("=") for kv in head.get("fx", "").split())
    expect_raw = dict(kv.split("=") for kv in head["expect"].split())
    limitation = expect_raw.pop("limitation", None)  # AD-07: the diarizer is always asked for exactly two speakers
    for who in {t["who"] for t in turns}:
        assert who in voices, f"{sid}: no voice for {who}"
    return {
        "id": sid, "tag": head["tag"], "what": head["what"], "lang": head["lang"], "client": client_id, "client_name": client_name,
        "date": head["date"], "voices": voices, "fx": fx,
        "expect": {"speakers": 2, "voices": len(voices), "limitation": limitation, "roles": expect_raw},
        "turns": turns, "facts": facts,
    }


def main():
    out = HERE / "sessions"
    out.mkdir(exist_ok=True)
    docs = [parse(p) for p in sorted((HERE / "scripts").glob("*.txt"))]
    known = {f["id"] for d in docs for f in d["facts"]}
    for d in docs:
        for f in d["facts"]:
            if f["repeat_of"] and f["repeat_of"] not in known:
                sys.exit(f"{d['id']}: {f['id']} repeats unknown {f['repeat_of']}")
        (out / f"{d['id']}.json").write_text(json.dumps(d, ensure_ascii=False, indent=1), encoding="utf-8")
    clients = {}
    for d in docs:
        c = clients.setdefault(d["client"], {"id": d["client"], "name": d["client_name"], "lang": d["lang"], "sessions": []})
        c["sessions"].append(d["id"])
    (HERE / "clients.json").write_text(json.dumps({"clients": list(clients.values())}, ensure_ascii=False, indent=1), encoding="utf-8")
    words = {d["id"]: sum(len(t["text"].split()) for t in d["turns"]) for d in docs}
    print(f"{len(docs)} sittings, {len(known)} planted facts; words: {words}")


if __name__ == "__main__":
    main()
