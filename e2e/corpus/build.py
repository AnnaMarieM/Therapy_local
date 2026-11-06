#!/usr/bin/env python3
"""Compile clients/*.txt ground-truth sheets into per-session scripts and a client index.

Sheet format (one file per client, UTF-8):
  client: <id> | <Display Name> | <cs|en>
  s01 2026-01-07
   f1 | <heading> | <spoken sentence(s) by the client>
   f4 = s01.f1 | <heading> | <re-statement in different words of an earlier fact>
  q | <recall query> | <fact ids this should retrieve, e.g. s01.f1 s05.f2>
  neg | <query that must NOT match anything strongly>

Output (deterministic, seeded by client+session):
  sessions/<client>/sNN.json  {client, session, lang, date, turns[], expected{facts[]}}
  clients.json                {clients:[{id,name,lang,sessions,queries,negatives}]}
"""
import json, random, re, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

FILLER = {
    "en": {
        "open": ["Hello, come in. How have you been since last time?", "Good to see you again. Where would you like to start today?"],
        "prompt": ["Tell me more about that.", "How did that feel for you?", "What happened next?", "I see. Can you say a bit more?",
                   "Mm-hm. Go on.", "And how was that for you?", "What went through your mind then?", "Take your time."],
        "bridge": ["Yes, it was like that.", "I think so, yes.", "I do not know, it is hard to explain.", "That is how it was.",
                   "I keep coming back to it.", "It is not easy to talk about."],
        "close": ["We are nearly out of time. Is there anything you want to add?", "Let us stop here for today."],
        "bye": ["No, I think that is everything. Thank you.", "Thank you, see you next week."],
    },
    "cs": {
        "open": ["Dobrý den, pojďte dál. Jak se vám vedlo od minula?", "Rád vás zase vidím. Kde byste dnes chtěla začít?"],
        "prompt": ["Řekněte mi o tom víc.", "Jaké to pro vás bylo?", "A co se stalo potom?", "Rozumím. Můžete to trochu rozvést?",
                   "Hm. Pokračujte.", "A jak vám z toho bylo?", "Co se vám v tu chvíli honilo hlavou?", "Nespěchejte."],
        "bridge": ["Ano, přesně tak to bylo.", "Asi ano.", "Nevím, těžko se to vysvětluje.", "Tak to prostě je.",
                   "Pořád se k tomu vracím.", "Není snadné o tom mluvit."],
        "close": ["Za chvíli nám končí čas. Chcete ještě něco dodat?", "Pro dnešek tady skončíme."],
        "bye": ["Ne, myslím, že je to všechno. Děkuji.", "Děkuji, uvidíme se příští týden."],
    },
}

CHATTER = {
    "en": [
        "It has been a grey week, and the weather does not help my mood much, to be honest.",
        "The train was late again this morning, so I almost did not make it on time today.",
        "I am not sure where to begin, there are so many things going round in my head at the moment.",
        "I had a long day yesterday and I was so tired in the evening that I just sat in front of the television.",
        "I wrote a few notes before coming here, but now I cannot remember half of what I wanted to say.",
        "It is strange to sit here and say all of this out loud, but it does help a little.",
        "Some days are better than others, and I try to remind myself of that when things get heavy.",
        "I made myself a cup of tea this morning and just stood by the window for a while.",
        "The journey here took a while, but at least I had time to gather my thoughts.",
    ],
    "cs": [
        "Byl to šedivý týden a to počasí mi na náladě moc nepřidává, po pravdě řečeno.",
        "Dnes ráno mi ujel autobus, takže to bylo na poslední chvíli.",
        "Nevím, kde začít, v hlavě mi teď běží spousta věcí najednou.",
        "Včerejšek byl dlouhý den a večer už mi nezbyly žádné síly, byla jen televize a gauč.",
        "Něco mám poznamenané na papíře, ale teď si polovinu z toho nepamatuju.",
        "Je zvláštní sedět tady a říkat to všechno nahlas, ale trochu to pomáhá.",
        "Některé dny jsou lepší než jiné a snažím se si to připomínat, když je těžko.",
        "Dnes ráno byl jen čaj a chvíle u okna.",
        "Cesta sem trvá dlouho, ale aspoň je čas si trochu srovnat myšlenky.",
    ],
}

SENT_SPLIT = re.compile(r"(?<=[.!?])\s+")


def parse_sheet(path: Path):
    client = None
    sessions, queries, negatives = [], [], []
    cur = None
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line.startswith("client:"):
            cid, name, lang = [x.strip() for x in line[len("client:"):].split("|")]
            client = {"id": cid, "name": name, "lang": lang}
        elif re.match(r"^s\d\d\s", line):
            sid, date = line.split()
            cur = {"id": sid, "date": date, "facts": []}
            sessions.append(cur)
        elif line.startswith("q |"):
            _, q, ids = [x.strip() for x in line.split("|")]
            queries.append({"q": q, "expect": ids.split()})
        elif line.startswith("neg |"):
            negatives.append(line.split("|", 1)[1].strip())
        else:
            parts = [x.strip() for x in line.split("|")]
            head = parts[0].split()
            fid = head[0]
            repeat = head[2] if len(head) == 3 and head[1] == "=" else None
            cur["facts"].append({"id": f"{cur['id']}.{fid}", "heading": parts[1], "said": parts[2], "repeat_of": repeat})
    assert client, f"{path}: missing client line"
    # A repeat of a repeat points at the original statement: the fact lives in the first session that said it.
    by_id = {f["id"]: f for s in sessions for f in s["facts"]}
    for f in by_id.values():
        while f["repeat_of"] and by_id[f["repeat_of"]]["repeat_of"]:
            f["repeat_of"] = by_id[f["repeat_of"]]["repeat_of"]
    return client, sessions, queries, negatives


def make_turns(client, sess, rng):
    lang = client["lang"]
    f = FILLER[lang]
    turns = [{"who": "therapist", "text": rng.choice(f["open"])}]
    chatter = list(CHATTER[lang]); rng.shuffle(chatter)
    turns.append({"who": "client", "text": chatter.pop()})
    facts = list(sess["facts"])
    rng.shuffle(facts)
    for fact in facts:
        turns.append({"who": "therapist", "text": rng.choice(f["prompt"])})
        sents = SENT_SPLIT.split(fact["said"])
        turns.append({"who": "client", "text": " ".join(sents)})
        turns.append({"who": "therapist", "text": rng.choice(f["prompt"])})
        turns.append({"who": "client", "text": chatter.pop() + " " + rng.choice(f["bridge"])})
        if rng.random() < 0.5:
            turns.append({"who": "therapist", "text": rng.choice(f["prompt"])})
            turns.append({"who": "client", "text": chatter.pop()})
    turns.append({"who": "therapist", "text": rng.choice(f["close"])})
    turns.append({"who": "client", "text": rng.choice(f["bye"])})
    return turns


def main():
    out = HERE / "sessions"
    index = []
    for sheet in sorted((HERE / "clients").glob("*.txt")):
        client, sessions, queries, negatives = parse_sheet(sheet)
        known = {f["id"] for s in sessions for f in s["facts"]}
        for s in sessions:
            for fa in s["facts"]:
                if fa["repeat_of"] and fa["repeat_of"] not in known:
                    sys.exit(f"{sheet.name}: {fa['id']} repeats unknown {fa['repeat_of']}")
        for q in queries:
            for e in q["expect"]:
                if e not in known:
                    sys.exit(f"{sheet.name}: query '{q['q']}' expects unknown {e}")
        for s in sessions:
            rng = random.Random(f"{client['id']}/{s['id']}")
            doc = {
                "client": client["id"], "session": s["id"], "lang": client["lang"], "date": s["date"],
                "turns": make_turns(client, s, rng),
                "expected": {"facts": s["facts"]},
            }
            p = out / client["id"] / f"{s['id']}.json"
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
        index.append({**client, "sessions": [s["id"] for s in sessions], "queries": queries, "negatives": negatives})
    (HERE / "clients.json").write_text(json.dumps({"clients": index}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{len(index)} clients, {sum(len(c['sessions']) for c in index)} sessions")


if __name__ == "__main__":
    main()
