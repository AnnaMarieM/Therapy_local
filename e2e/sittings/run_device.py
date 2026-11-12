#!/usr/bin/env python3
"""Run the rendered sittings on the QA build through its control API (qa/qa-api.sh, docs/QA_API.md) and pull the results.

  run_device.py --out results/run1 [--only petr-k_s01,tom-b_s01] [--serial R52R...] [--reset] [--timeout-min 240]

QA build only (com.therapylocal.app.qa, test data, vault password 12345678): the real app is refused. The vault is set up or unlocked through
the API as needed; --reset first wipes the QA vault data (never the downloaded models) and sets it up again. Clients are created with their own
speech language, the sittings are imported as audio in client and date order (the normal pipeline runs), then the script waits for the job queue
and writes <out>/results.json (the DevResults document: segments with speaker ids and roles, speaker maps, facts, summaries), the export
<out>/export.json (re-importable, see docs/QA_API.md), <out>/logcat.txt and <out>/logcat-key.txt (diarize_done, diarization_skipped, role guess
and speaker map lines). The screen should be on: the client brings the app forward so the queue runner may start.
"""
import argparse, json, re, subprocess, sys, tempfile, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
QA_API = HERE.parent.parent / "qa" / "qa-api.sh"
KEY_LINES = re.compile(r"diarize_done|diarization_skipped|diarize_|speaker_map|role_guess|roles_|ROLES|asr_transcribe|job_failed|memory_lab_failed|engine_died", re.I)


class Api:
    def __init__(self, serial, package):
        self.base = [str(QA_API)] + (["-s", serial] if serial else []) + ["-p", package]
        self.serial, self.package = serial, package

    def call(self, *args, check=True):
        r = subprocess.run(self.base + [str(a) for a in args], capture_output=True, text=True)
        try:
            res = json.loads(r.stdout.strip().splitlines()[-1])
        except (ValueError, IndexError):
            res = {"ok": False, "error": {"code": "transport", "message": (r.stderr or r.stdout).strip()[:300]}}
        if check and not res.get("ok"):
            sys.exit(f"qa-api {' '.join(map(str, args[:1]))} failed: {json.dumps(res.get('error'))}")
        return res.get("data", res), r.returncode

    def adb(self, *args):
        cmd = ["adb"] + (["-s", self.serial] if self.serial else []) + list(args)
        return subprocess.run(cmd, capture_output=True, text=True).stdout


def ensure_vault(api, reset):
    state = api.call("status")[0]["vault"]["state"]
    if reset:
        if state == "locked":
            api.call("unlock")
        if state != "not_set_up":
            api.call("reset")
        api.call("setup")
    elif state == "not_set_up":
        api.call("setup")
    elif state == "locked":
        api.call("unlock")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--package", default="com.therapylocal.app.qa")
    ap.add_argument("--only", help="comma separated sitting ids; default all")
    ap.add_argument("--reset", action="store_true", help="wipe the QA vault data first (models are kept)")
    ap.add_argument("--gemma", choices=["e2b", "e4b"], help="memory LLM build (default: the app picks by RAM)")
    ap.add_argument("--audio", default=str(HERE / "audio"))
    ap.add_argument("--sessions", default=str(HERE / "sessions"))
    ap.add_argument("--timeout-min", type=int, default=240)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    if not a.package.endswith(".qa"):
        sys.exit("refusing to run on anything but the QA build (com.therapylocal.app.qa); never touch the real app")
    api = Api(a.serial, a.package)
    docs = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(Path(a.sessions).glob("*.json"))]
    if a.only:
        docs = [d for d in docs if d["id"] in a.only.split(",")]
    for d in docs:
        if not (Path(a.audio) / f"{d['id']}.wav").exists():
            sys.exit(f"missing {Path(a.audio) / (d['id'] + '.wav')}; run render.py first")
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    ensure_vault(api, a.reset)
    if a.gemma:
        api.call("set_setting", "gemma_variant", a.gemma)
    st = api.call("status")[0]
    if not st["models"]["ready"]:
        sys.exit("the models are not on the phone yet (status.models); open the QA app once and let the download finish")
    api.adb("logcat", "-c")
    known = {c["id"]: c for c in json.loads((HERE / "clients.json").read_text(encoding="utf-8"))["clients"]}
    need = sorted({d["client"] for d in docs})
    wanted = {"clients": [{"id": c, "name": known.get(c, {}).get("name", c), "lang": next(d["lang"] for d in docs if d["client"] == c)} for c in need]}
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(wanted, f, ensure_ascii=False)
    api.call("seed_clients", f.name)
    t0 = time.time()
    # client and date order, as the pipeline wants earlier sessions of a client first
    for d in sorted(docs, key=lambda d: (d["client"], d["date"])):
        r, _ = api.call("import_session", d["client"], Path(a.audio) / f"{d['id']}.wav", "--session", d["id"], "--date", d["date"], "--language", d["lang"])
        print(f"imported {d['id']}" + (" (already there)" if r.get("already_imported") else ""), flush=True)
    res, rc = api.call("wait", a.timeout_min * 60, check=False)
    if rc == 4:
        sys.exit("timeout waiting for the job queue (is the screen on? status shows the queue and models)")
    if res.get("failed"):
        print(f"warning: {res['failed']} job(s) failed; see logcat-key.txt")
    print(f"pipeline done in {(time.time() - t0) / 60:.1f} min", flush=True)
    (out / "results.json").write_text(json.dumps(api.call("results")[0], ensure_ascii=False), encoding="utf-8")
    (out / "export.json").write_text(json.dumps(api.call("export")[0], ensure_ascii=False), encoding="utf-8")
    log = api.adb("logcat", "-d", "-v", "threadtime")
    (out / "logcat.txt").write_text(log, encoding="utf-8")
    key = [l for l in log.splitlines() if KEY_LINES.search(l)]
    (out / "logcat-key.txt").write_text("\n".join(key) + "\n", encoding="utf-8")
    print(f"results: {out / 'results.json'}  key log lines: {len(key)}")


if __name__ == "__main__":
    main()
