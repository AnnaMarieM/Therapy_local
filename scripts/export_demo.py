#!/usr/bin/env python3
"""Build demo_memory.json (format: see memory/DemoMemory.kt) for the app's demo mode.

From a pulled memory.db (after a full pipeline run on the phone):
  adb pull /data/data/com.therapylocal.app/databases/memory.db   (debug build; or run-as)
  python3 scripts/export_demo.py --db memory.db --sessions demo/sessions --out app/src/debug/assets/demo_memory.json

Placeholder from the ground-truth facts only (no pipeline run, no evidence, no vectors):
  python3 scripts/export_demo.py --from-facts --sessions demo/sessions --out app/src/debug/assets/demo_memory.json --max-facts 12

Note: memory.db is SQLCipher-encrypted since the patient-data encryption change, so `--db` needs a decrypted export of it (e.g. the sqlcipher CLI
with the vault key, `PRAGMA key = "x'<hex>'"; ATTACH ... KEY ''; SELECT sqlcipher_export('plain');`). `--from-facts` needs no database.

Session dates come from the first line of demo/sessions/<session>.md ("# George — Session 1 — 2026-01-14").
Vectors are the float32 little-endian blobs of memory.db, base64 encoded.
"""
import argparse
import base64
import json
import re
import sqlite3
from pathlib import Path

DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
# Client display data for the two mock clients (from demo/profiles). Other clients get a capitalised id.
PROFILES = {"george": ("George", 41, "Senior auditor"), "marta": ("Marta", 29, "UX researcher")}


def session_dates(sessions_dir):
    out = {}
    for md in sorted(Path(sessions_dir).glob("*_s[0-9]*.md")):
        if md.name.endswith(".facts.md"):
            continue
        m = DATE.search(md.read_text(encoding="utf-8").splitlines()[0])
        if m:
            out[md.stem] = m.group(0)
    return out


def client_meta(cid):
    name, age, occ = PROFILES.get(cid, (cid.capitalize(), None, ""))
    c = {"id": cid, "name": name, "occupation": occ}
    if age:
        c["age"] = age
    return c


def b64(blob):
    return base64.b64encode(blob).decode("ascii") if blob else None


def from_db(db_path, dates):
    db = sqlite3.connect(db_path)
    db.row_factory = sqlite3.Row
    clients = {}
    for r in db.execute("SELECT client FROM chunks UNION SELECT client FROM facts ORDER BY 1"):
        clients[r[0]] = {**client_meta(r[0]), "sessions": [], "facts": [], "supports": []}
    for cid, c in clients.items():
        sessions = {}
        for r in db.execute("SELECT session, idx, text, embedding FROM chunks WHERE client=? ORDER BY session, idx", (cid,)):
            s = sessions.setdefault(r["session"], {"id": r["session"], "date": dates.get(r["session"], ""), "chunks": []})
            ch = {"idx": r["idx"], "text": r["text"]}
            if b64(r["embedding"]):
                ch["vector"] = b64(r["embedding"])
            s["chunks"].append(ch)
        for r in db.execute("SELECT DISTINCT session FROM facts WHERE client=?", (cid,)):
            sessions.setdefault(r[0], {"id": r[0], "date": dates.get(r[0], ""), "chunks": []})
        c["sessions"] = sorted(sessions.values(), key=lambda s: (s["date"] or "~", s["id"]))
        for r in db.execute("SELECT * FROM facts WHERE client=? ORDER BY created_at, rowid", (cid,)):
            f = {k: r[k] for k in ("id", "category", "subject", "fact", "evidence", "chunk", "session")}
            if b64(r["embedding"]):
                f["vector"] = b64(r["embedding"])
            c["facts"].append(f)
        for r in db.execute("SELECT fact_id, session, evidence, chunk FROM fact_support WHERE client=? ORDER BY rowid", (cid,)):
            c["supports"].append(dict(r))
    return list(clients.values())


def from_facts(sessions_dir, dates, max_facts):
    clients = {}
    for fm in sorted(Path(sessions_dir).glob("*_s[0-9]*.facts.md")):
        sid = fm.name[: -len(".facts.md")]
        cid = sid.split("_")[0]
        c = clients.setdefault(cid, {**client_meta(cid), "sessions": [], "facts": [], "supports": []})
        c["sessions"].append({"id": sid, "date": dates.get(sid, ""), "chunks": []})
        lines = [l[2:].strip() for l in fm.read_text(encoding="utf-8").splitlines() if l.startswith("- ")]
        for text in lines[:max_facts]:
            c["facts"].append({
                "id": "F%d" % (len(c["facts"]) + 1), "category": "other", "subject": c["name"],
                "fact": text, "evidence": "", "chunk": 0, "session": sid,
            })
    return list(clients.values())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", help="pulled memory.db")
    ap.add_argument("--from-facts", action="store_true", help="build a placeholder from demo/sessions/*.facts.md")
    ap.add_argument("--sessions", default="demo/sessions")
    ap.add_argument("--out", default="app/src/debug/assets/demo_memory.json")
    ap.add_argument("--max-facts", type=int, default=12, help="facts per session in --from-facts mode")
    a = ap.parse_args()
    if bool(a.db) == a.from_facts:
        ap.error("give exactly one of --db or --from-facts")
    dates = session_dates(a.sessions)
    clients = from_db(a.db, dates) if a.db else from_facts(a.sessions, dates, a.max_facts)
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"version": 1, "clients": clients}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print("wrote %s: %d clients, %d sessions, %d facts" % (
        out, len(clients), sum(len(c["sessions"]) for c in clients), sum(len(c["facts"]) for c in clients)))


if __name__ == "__main__":
    main()
