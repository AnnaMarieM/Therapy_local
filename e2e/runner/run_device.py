#!/usr/bin/env python3
"""Drive the app's debug harness on a phone with the rendered corpus and pull results.json.

  run_device.py --clients daniel-r,petr-h --sessions 10 --language auto --out results/run1
  run_device.py --package com.therapylocal.app --serial 4a985db2 ...

The app must be a debug build with its vault unlocked (the harness starts from the MainActivity `memory_lab` intent and waits on the
unlocked vault, see docs/MEMORY.md). Existing sessions of the same name are skipped by the app; use --reset to wipe the listed clients first.
"""
import argparse, json, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def adb(serial, *args, check=True, capture=True):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=capture, text=True)
    if check and r.returncode != 0:
        sys.exit(f"adb failed: {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r.stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--package", default="com.therapylocal.app")
    ap.add_argument("--clients", help="comma separated ids; default all")
    ap.add_argument("--sessions", type=int, default=10, help="sessions per client (first N)")
    ap.add_argument("--language", default=None, help="auto|en|cs|sk, written to the app's settings; default: per client language")
    ap.add_argument("--whisper-size", default=None)
    ap.add_argument("--reset", action="store_true", help="delete the listed clients in the app first")
    ap.add_argument("--no-queries", action="store_true")
    ap.add_argument("--prompts", help="folder with extract/review prompt files that override the app's (A/B without a rebuild)")
    ap.add_argument("--audio", default=str(ROOT / "audio"))
    ap.add_argument("--out", required=True)
    ap.add_argument("--timeout-min", type=int, default=360)
    a = ap.parse_args()

    index = json.loads((ROOT / "corpus" / "clients.json").read_text(encoding="utf-8"))["clients"]
    if a.clients:
        want = set(a.clients.split(","))
        index = [c for c in index if c["id"] in want]
    langs = {c["lang"] for c in index}
    if a.language is None and len(langs) > 1:
        sys.exit("mixed-language run: pass --language auto (settings are app-wide)")
    language = a.language or langs.pop()

    base = f"/sdcard/Android/data/{a.package}/files/memory"
    sessions, queries = [], []
    adb(a.serial, "shell", "rm", "-rf", f"{base}/out", f"{base}/in", f"{base}/prompts")
    if a.prompts:
        adb(a.serial, "push", a.prompts.rstrip("/") + "/.", f"{base}/prompts/")
    adb(a.serial, "shell", "mkdir", "-p", f"{base}/in", f"{base}/out")
    for c in index:
        for sid in c["sessions"][: a.sessions]:
            wav = Path(a.audio) / c["id"] / f"{sid}.wav"
            if not wav.exists():
                sys.exit(f"missing {wav}; run tts/render.py first")
            name = f"{c['id']}_{sid}.wav"
            adb(a.serial, "push", str(wav), f"{base}/in/{name}")
            date = json.loads((ROOT / "corpus" / "sessions" / c["id"] / f"{sid}.json").read_text(encoding="utf-8"))["date"]
            sessions.append({"file": name, "date": date})
        if not a.no_queries:
            queries += [{"client": c["id"], "q": q["q"]} for q in c["queries"]]
            queries += [{"client": c["id"], "q": n} for n in c["negatives"]]
    settings = {"language": language, "engine": "whisper"}
    if a.whisper_size:
        settings["whisper_size"] = a.whisper_size
    job = {"resetProfiles": a.reset, "sessions": sessions, "queries": queries, "settings": settings}
    tmp = Path(a.out); tmp.mkdir(parents=True, exist_ok=True)
    (tmp / "job.json").write_text(json.dumps(job, ensure_ascii=False))
    adb(a.serial, "push", str(tmp / "job.json"), f"{base}/job.json")

    adb(a.serial, "shell", "am", "start", "-f", "0x10008000", "-n", f"{a.package}/com.therapylocal.app.MainActivity", "--ez", "memory_lab", "true")
    print(f"started: {len(sessions)} sessions, language={language}", flush=True)
    t0 = time.time()
    while time.time() - t0 < a.timeout_min * 60:
        ls = adb(a.serial, "shell", "ls", f"{base}/out", check=False)
        if "ERROR" in ls:
            print(adb(a.serial, "shell", "cat", f"{base}/out/ERROR"))
            sys.exit("harness reported ERROR")
        if "DONE" in ls:
            break
        time.sleep(20)
    else:
        sys.exit("timeout waiting for out/DONE (is the vault unlocked?)")
    adb(a.serial, "pull", f"{base}/out/results.json", str(tmp / "results.json"))
    print(f"done in {(time.time() - t0) / 60:.1f} min -> {tmp / 'results.json'}")


if __name__ == "__main__":
    main()
