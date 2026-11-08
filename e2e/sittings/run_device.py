#!/usr/bin/env python3
"""Push the rendered sittings to the QA build's dev root, start the debug harness, wait for out/DONE, pull results.json and the logcat lines.

  run_device.py --out results/run1 [--only petr-k_s01,tom-b_s01] [--serial R52R...] [--no-reset] [--timeout-min 240]

QA build only (com.therapylocal.app.qa, test data, vault password 12345678): the real app is refused. The vault must be unlocked and the screen
on (the harness starts from the MainActivity `memory_lab` intent and waits on the unlocked vault). Clients are wiped first unless --no-reset.
The app creates clients with one speech language (job.json "clientLanguages"), so Czech and English sittings are run as two jobs in turn and
their results are merged into <out>/results.json. Also written: <out>/logcat.txt (the whole run) and <out>/logcat-key.txt (diarize_done,
diarization_skipped, role guess and speaker map lines).
"""
import argparse, json, re, subprocess, sys, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
KEY_LINES = re.compile(r"diarize_done|diarization_skipped|diarize_|speaker_map|role_guess|roles_|ROLES|asr_transcribe|job_failed|memory_lab_failed|engine_died", re.I)


def adb(serial, *args, check=True, binary=False):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=not binary)
    if check and r.returncode != 0:
        sys.exit(f"adb failed: {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r.stdout


def run_group(a, base, docs, lang, out: Path, first: bool):
    adb(a.serial, "shell", "rm", "-rf", f"{base}/out", f"{base}/in")
    adb(a.serial, "shell", "mkdir", "-p", f"{base}/in", f"{base}/out")
    sessions = []
    for d in docs:
        wav = Path(a.audio) / f"{d['id']}.wav"
        if not wav.exists():
            sys.exit(f"missing {wav}; run render.py first")
        adb(a.serial, "push", str(wav), f"{base}/in/{d['id']}.wav")
        sessions.append({"file": f"{d['id']}.wav", "date": d["date"]})
    job = {"resetProfiles": not a.no_reset, "sessions": sessions, "settings": {"default_language": lang},
           "clientLanguages": {d["client"]: lang for d in docs}}
    (out / f"job-{lang}.json").write_text(json.dumps(job, ensure_ascii=False))
    adb(a.serial, "push", str(out / f"job-{lang}.json"), f"{base}/job.json")
    adb(a.serial, "logcat", "-c")
    adb(a.serial, "shell", "am", "start", "-f", "0x10008000", "-n", f"{a.package}/com.therapylocal.app.MainActivity", "--ez", "memory_lab", "true")
    print(f"[{lang}] started: {len(sessions)} sessions", flush=True)
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
        sys.exit("timeout waiting for out/DONE (is the vault unlocked and the screen on?)")
    adb(a.serial, "pull", f"{base}/out/results.json", str(out / f"results-{lang}.json"))
    log = adb(a.serial, "logcat", "-d", "-v", "threadtime", check=False)
    with open(out / "logcat.txt", "a", encoding="utf-8") as f:
        f.write(f"===== {lang} =====\n{log}")
    print(f"[{lang}] done in {(time.time() - t0) / 60:.1f} min", flush=True)
    return json.loads((out / f"results-{lang}.json").read_text(encoding="utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--package", default="com.therapylocal.app.qa")
    ap.add_argument("--only", help="comma separated sitting ids; default all")
    ap.add_argument("--no-reset", action="store_true", help="keep existing clients and sessions")
    ap.add_argument("--audio", default=str(HERE / "audio"))
    ap.add_argument("--sessions", default=str(HERE / "sessions"))
    ap.add_argument("--timeout-min", type=int, default=240)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    if not a.package.endswith(".qa"):
        sys.exit("refusing to run on anything but the QA build (com.therapylocal.app.qa); never touch the real app")
    if f"package:{a.package}" not in adb(a.serial, "shell", "pm", "list", "packages", a.package).split():
        sys.exit(f"{a.package} is not installed on the device")
    docs = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(Path(a.sessions).glob("*.json"))]
    if a.only:
        docs = [d for d in docs if d["id"] in a.only.split(",")]
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "logcat.txt").write_text("")
    base = f"/sdcard/Android/data/{a.package}/files/memory"
    merged = {"sessions": [], "facts": [], "answers": []}
    for i, lang in enumerate(sorted({d["lang"] for d in docs})):
        res = run_group(a, base, [d for d in docs if d["lang"] == lang], lang, out, i == 0)
        merged["embedModel"] = res.get("embedModel")
        for key in ("sessions", "facts", "answers"):
            seen = {json.dumps(x, sort_keys=True) for x in merged[key]}
            merged[key] += [x for x in res.get(key, []) if json.dumps(x, sort_keys=True) not in seen]
        # one results.json holds the whole database: later groups see earlier clients too, so keep each session once (latest wins)
        merged["sessions"] = list({s["session"]: s for s in merged["sessions"]}.values())
        merged["facts"] = list({f["id"]: f for f in merged["facts"]}.values())
    (out / "results.json").write_text(json.dumps(merged, ensure_ascii=False), encoding="utf-8")
    key = [l for l in (out / "logcat.txt").read_text(encoding="utf-8", errors="replace").splitlines() if KEY_LINES.search(l)]
    (out / "logcat-key.txt").write_text("\n".join(key) + "\n", encoding="utf-8")
    print(f"results: {out / 'results.json'}  key log lines: {len(key)}")


if __name__ == "__main__":
    main()
