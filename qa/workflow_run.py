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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stage_cache as sc

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


def resume_plan(stage, cache_entry, pipeline, want):
    """Host-only checks of a resume (no device): returns (run_job kinds to queue, the loaded cache entry) or raises stage_cache.Unsupported."""
    if os.path.basename(os.path.normpath(cache_entry)) != f"after-{stage}":
        raise sc.Unsupported(f"--from-cache must be an after-{stage} directory, got {cache_entry}")
    cached = sc.load_snapshot(cache_entry)
    if cached["meta"].get("workflow") != pipeline:
        raise sc.Unsupported(f"cached workflow {cached['meta'].get('workflow')} is not --pipeline {pipeline}")
    if len(want) != 1:
        raise sc.Unsupported("resume takes exactly one recording (--recordings tomas-b)")
    return sc.check_resume(stage, cached["export"], want[0]), cached


def evt_lines(path, kinds=("stage_done", "backend_proven")):
    """The parsed log lines of these events (counts, times and backends; the app never logs text)."""
    out = []
    for line in open(path, errors="replace"):
        kv = {k: v.strip('"') for k, v in KV.findall(line)}
        if kv.get("evt") in kinds:
            out.append(kv)
    return out


def snapshot(a, s, stage, recording, session, logcat_path, resumed=None, carried_log=""):
    """One cache entry (qa/stage_cache.py): export, results, settings, the log so far and META, taken with the queue as it is now."""
    tmp = os.path.join(a.out, "_snap")
    os.makedirs(tmp, exist_ok=True)
    before = json.loads(api(s, "status").stdout)["data"]["jobs"]
    api(s, "export", "--session", session, "-o", os.path.join(tmp, "export.json"))
    api(s, "results", "--session", session, "-o", os.path.join(tmp, "results.json"))
    settings = api(s, "get_settings").stdout
    status = json.loads(api(s, "status").stdout)["data"]
    after = status["jobs"]
    log = "".join(l for l in open(logcat_path, errors="replace") if "evt=" in l)
    shas = json.load(open(a.models_manifest)) if a.models_manifest else {}
    open_now = set(before.get("open_kinds") or []) | set(after.get("open_kinds") or [])
    meta = {
        "platform": a.platform, "run_id": a.run_id, "recording": recording, "stage": stage, "device": a.device, "workflow": a.pipeline,
        "whisper_beam": a.beam, "backend_override": "auto", "build": status["build"],
        "models": [{"id": m["id"], "ready": m["ready"], "sha256": shas.get(m["id"])} for m in status["models"]["files"]],
        "models_note": "the QA API reports model ids and readiness, not hashes; sha256 comes from --models-manifest ({id: sha256}) or is null",
        "stage_done": evt_lines(logcat_path), "open_kinds_before": before.get("open_kinds"), "open_kinds_after": after.get("open_kinds"),
        "queue_idle_at_capture": bool(after.get("idle")), "resumed_from": resumed, "phone_state": a.note,
        # exact: no job of this stage was open while the files were taken, so nothing of it is missing; later stages may have started.
        "exact": not (open_now & sc.STAGE_KINDS[stage]),
    }
    d = sc.snapshot_dir(a.cache_dir, a.platform, a.run_id, recording, stage)
    sc.write_snapshot(d, open(os.path.join(tmp, "export.json"), "rb").read(), open(os.path.join(tmp, "results.json"), "rb").read(), settings, carried_log + log, meta)
    print(f"cache: {d}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--device", required=True)
    ap.add_argument("--pipeline", required=True, choices=["fast", "quality", "quality-cs", "canary"])
    ap.add_argument("--wav-dir", help="the 16 kHz mono WAVs; not needed with --resume-from")
    ap.add_argument("--out", required=True)
    ap.add_argument("--recordings", default=",".join(c[0] for c in CLIENTS))
    ap.add_argument("--beam", type=int, default=5)
    ap.add_argument("--wait-s", type=int, default=14400)
    ap.add_argument("--no-reset", action="store_true")
    ap.add_argument("--cache-dir", help="snapshot after every stage into DIR/<platform>/<run-id>/<recording>/after-<stage>/ (one recording per run)")
    ap.add_argument("--platform", help="cache platform folder (default: --device)")
    ap.add_argument("--run-id", help="cache run folder (default: <pipeline>-beam<beam>-perturn)")
    ap.add_argument("--resume-from", choices=sc.STAGES + ["diarize"], help="restart after this stage; needs --from-cache")
    ap.add_argument("--from-cache", help="a cache entry directory (.../after-<stage>) to import instead of the audio")
    ap.add_argument("--models-manifest", help="JSON {model id: sha256} written into META.json")
    ap.add_argument("--note", action="append", default=[], help="phone state note for META.json (repeatable)")
    a = ap.parse_args()
    a.platform = a.platform or a.device
    a.run_id = a.run_id or f"{a.pipeline}-beam{a.beam}-perturn"
    if bool(a.resume_from) != bool(a.from_cache):
        ap.error("--resume-from and --from-cache go together")
    if not a.resume_from and not a.wav_dir:
        ap.error("--wav-dir is required without --resume-from")
    if a.cache_dir and len(a.recordings.split(",")) != 1:
        ap.error("--cache-dir needs exactly one recording (--recordings tomas-b)")
    resume = None
    if a.resume_from:
        try:
            resume = resume_plan(a.resume_from, a.from_cache, a.pipeline, a.recordings.split(","))
        except sc.Unsupported as e:
            raise SystemExit(f"resume not supported: {e}")
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
    if not resume:
        api(s, "seed_clients", cj)

    logcat_path = os.path.join(a.out, "logcat.txt")
    subprocess.run(["adb", "-s", s, "logcat", "-c"], capture_output=True)
    logcat = subprocess.Popen(["adb", "-s", s, "logcat", "-v", "brief", "-s", "TL:V"], stdout=open(logcat_path, "w"), stderr=subprocess.DEVNULL)
    thermal = subprocess.Popen([os.path.join(HERE, "thermal-log.sh"), "-s", s, "-o", os.path.join(a.out, "thermal.csv")],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, preexec_fn=os.setsid)
    started = time.time()
    try:
        get_jobs = lambda: json.loads(api(s, "status").stdout)["data"]["jobs"]
        if resume:
            jobs, cached = resume
            mf = os.path.join(a.out, "resume-manifest.json")
            json.dump(sc.resume_manifest(cached["export"], want[0]), open(mf, "w"))
            r = api(s, "import_manifest", mf, timeout=900)
            if json.loads(r.stdout)["data"]["failed"]:
                raise SystemExit(f"import_manifest failed: {r.stdout[-400:]}")
            sess = json.load(open(mf))["sessions"][0]["session"]
            old = os.path.join(a.from_cache, "stage-log.txt")
            carried = f"# carried from {a.from_cache}\n" + (open(old).read() if os.path.isfile(old) else "") + "# this run\n"
            for stage in sc.STAGES[sc.STAGES.index(a.resume_from) + 1:]:
                if stage in jobs:
                    api(s, "run_job", stage, sess, "--timeout", str(a.wait_s), timeout=a.wait_s + 120)
                sc.wait_stage(stage, get_jobs, time.sleep)
                if a.cache_dir:
                    snapshot(a, s, stage, want[0], sess, logcat_path, {"dir": a.from_cache, "stage": a.resume_from}, carried)
        else:
            for c, _, lang, date in CLIENTS:
                if c not in want:
                    continue
                wav = os.path.join(a.wav_dir, f"{c}.wav")
                api(s, "import_session", c, wav, "--session", f"{c}-{a.pipeline}", "--date", date, "--language", lang, timeout=900)
            if a.cache_dir:
                for stage in sc.STAGES:
                    sc.wait_stage(stage, get_jobs, time.sleep)
                    snapshot(a, s, stage, want[0], f"{want[0]}-{a.pipeline}", logcat_path)
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
