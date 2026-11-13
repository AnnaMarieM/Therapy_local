#!/usr/bin/env python3
"""Runs the four human recordings through one workflow on one QA device and saves everything raw (docs/AD/AD-22.md, plan section 4).

  qa/workflow_run.py --serial 192.168.1.15:5555 --device poco --pipeline fast --wav-dir DIR --out research/.../device/poco/fast

For one pipeline: unlock (or set up) the QA vault, reset it (the models stay), set the workflow, create the four clients, import the four
recordings (16 kHz mono WAVs of e2e/corpus/human/<client>/s01.m4a, which this script does not convert), wait for the job queue, then write
into --out: results.json and export.json as the QA API gave them, logcat.txt (tag TL, the whole run), thermal.csv (qa/thermal-log.sh),
stages.json (the stage_done / turn_done lines of the log, parsed) and run.json (what was set, when, which build). Nothing is edited after.
QA build only: qa/qa-api.sh refuses any other package. The real app is never touched.
"""
import argparse, json, os, re, signal, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
API = os.path.join(HERE, "qa-api.sh")
CLIENTS = [  # id, display name, language, session date (from e2e/corpus/human/<id>/s01.json)
    ("jana-n", "Jana N.", "cs", "2026-01-08"),
    ("tomas-b", "Tomas B.", "cs", "2026-01-10"),
    ("daniel-r", "Daniel R.", "en", "2026-01-12"),
    ("hannah-w", "Hannah W.", "en", "2026-01-14"),
]
KV = re.compile(r'(\w+)=("(?:[^"\\]|\\.)*"|\S+)')


def api(serial, *args, check=True, timeout=300):
    r = subprocess.run([API, "-s", serial, *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise SystemExit(f"qa-api {' '.join(args)[:80]} failed ({r.returncode}): {r.stdout[-400:]} {r.stderr[-400:]}")
    return r


def parse_lines(path):
    stages, turns, mem = [], [], []
    for line in open(path, errors="replace"):
        if "evt=" not in line:
            continue
        kv = {k: v.strip('"') for k, v in KV.findall(line)}
        e = kv.get("evt")
        if e == "stage_done":
            stages.append(kv)
        elif e == "turn_done":
            turns.append(kv)
        elif e == "host_mem":
            mem.append(kv)
    return stages, turns, mem


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--device", required=True)
    ap.add_argument("--pipeline", required=True, choices=["fast", "quality", "canary"])
    ap.add_argument("--wav-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--recordings", default=",".join(c[0] for c in CLIENTS))
    ap.add_argument("--beam", type=int, default=5)
    ap.add_argument("--wait-s", type=int, default=14400)
    ap.add_argument("--no-reset", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    want = a.recordings.split(",")
    s = a.serial
    t_start = time.time()

    st = json.loads(api(s, "status").stdout)["data"]
    vault = st["vault"]["state"]
    if vault == "not_set_up":
        api(s, "setup")
    elif vault == "locked":
        api(s, "unlock")
    if not a.no_reset:
        api(s, "reset"); api(s, "setup")
    api(s, "set_setting", "workflow", a.pipeline)
    api(s, "set_setting", "whisper_beam", str(a.beam))
    api(s, "set_setting", "backend_override", "auto")
    status = json.loads(api(s, "status").stdout)["data"]
    clients = {"clients": [{"id": c, "name": n, "lang": l} for c, n, l, _ in CLIENTS if c in want]}
    cj = os.path.join(a.out, "clients.json")
    json.dump(clients, open(cj, "w"))
    api(s, "seed_clients", cj)

    logcat_path = os.path.join(a.out, "logcat.txt")
    subprocess.run(["adb", "-s", s, "logcat", "-c"], capture_output=True)
    logcat = subprocess.Popen(["adb", "-s", s, "logcat", "-v", "brief", "-s", "TL:V"], stdout=open(logcat_path, "w"), stderr=subprocess.DEVNULL)
    thermal = subprocess.Popen([os.path.join(HERE, "thermal-log.sh"), "-s", s, "-o", os.path.join(a.out, "thermal.csv")],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, preexec_fn=os.setsid)
    started = time.time()
    try:
        for c, _, lang, date in CLIENTS:
            if c not in want:
                continue
            wav = os.path.join(a.wav_dir, f"{c}.wav")
            api(s, "import_session", c, wav, "--session", f"{c}-{a.pipeline}", "--date", date, "--language", lang, timeout=900)
        r = api(s, "wait", str(a.wait_s), check=False, timeout=a.wait_s + 120)
        done_status = r.returncode
    finally:
        ended = time.time()
        time.sleep(3)
        logcat.terminate()
        try:
            os.killpg(os.getpgid(thermal.pid), signal.SIGINT)
        except ProcessLookupError:
            pass
        time.sleep(2)
    api(s, "results", "-o", os.path.join(a.out, "results.json"))
    api(s, "export", "-o", os.path.join(a.out, "export.json"))
    final = json.loads(api(s, "status").stdout)["data"]
    stages, turns, mem = parse_lines(logcat_path)
    json.dump({"stages": stages, "turns": turns, "host_mem": mem}, open(os.path.join(a.out, "stages.json"), "w"), indent=1)
    json.dump({
        "device": a.device, "serial": s, "pipeline": a.pipeline, "whisper_beam": a.beam, "recordings": want,
        "build": status["build"], "started_epoch": started, "ended_epoch": ended, "wall_s": round(ended - started, 1),
        "wait_exit": done_status, "jobs_final": final.get("jobs"), "script_start_epoch": t_start,
    }, open(os.path.join(a.out, "run.json"), "w"), indent=1)
    print(f"{a.device} {a.pipeline}: wall {ended - started:.0f}s, wait exit {done_status}, stages {len(stages)}, turns {len(turns)}")


if __name__ == "__main__":
    main()
