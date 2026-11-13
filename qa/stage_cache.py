"""Per-stage cache of a pipeline run, so later tests reuse a slow stage instead of running it again.

Layout (one layout for the phones and the T4):  <root>/<platform>/<run-id>/<recording>/after-<stage>/
  export.json  results.json  settings.json  stage-log.txt  captured_at_utc.txt  META.json

Stages, in order: transcribe translate roles window summary. `diarize` is a step inside the transcribe job and has no snapshot of its own
(the QA API does not expose the turns). What each snapshot can restart from is in research/asr-llm-combo/final-workflow/cache/README.md.
Pure functions and one polling helper; qa/workflow_run.py does the device calls.
"""
import datetime, hashlib, json, os

STAGES = ["transcribe", "translate", "roles", "window", "summary"]
# Job kinds that must all be gone from the queue before the stage counts as finished (extract and commit are queued by window).
STAGE_KINDS = {"transcribe": {"transcribe"}, "translate": {"translate"}, "roles": {"roles"}, "window": {"window", "extract", "commit"}, "summary": {"summary"}}
# What run_job must queue by hand after an import with process:false; window queues extract, commit and summary itself.
RESUME_JOBS = {"translate": ["translate", "roles", "window"], "roles": ["roles", "window"], "window": ["window"], "summary": ["summary"]}
FILES = ["export.json", "results.json", "settings.json", "stage-log.txt", "captured_at_utc.txt", "META.json"]


class Unsupported(Exception):
    """The resume point cannot be restarted from the public QA API; the message says why and what would change that."""


def snapshot_dir(root, platform, run_id, recording, stage):
    if stage not in STAGES:
        raise ValueError(f"unknown stage {stage!r}; one of {', '.join(STAGES)}")
    return os.path.join(root, platform, run_id, recording, f"after-{stage}")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def write_snapshot(d, export, results, settings, stage_log, meta, now=None):
    """Writes the six files of one snapshot (the raw documents as the API gave them: text or bytes). Refuses to overwrite an existing snapshot."""
    if os.path.exists(os.path.join(d, "export.json")):
        raise FileExistsError(f"{d} already holds a snapshot; a cache entry is never overwritten")
    os.makedirs(d, exist_ok=True)
    now = now or datetime.datetime.now(datetime.timezone.utc)
    for name, body in (("export.json", export), ("results.json", results), ("settings.json", settings), ("stage-log.txt", stage_log)):
        with open(os.path.join(d, name), "wb") as f:
            f.write(body if isinstance(body, bytes) else body.encode())
    with open(os.path.join(d, "captured_at_utc.txt"), "w") as f:
        f.write(now.strftime("%Y-%m-%dT%H:%M:%SZ\n"))
    with open(os.path.join(d, "META.json"), "w") as f:
        json.dump(meta, f, indent=1, sort_keys=True)
    return d


def load_snapshot(d):
    miss = [n for n in ("export.json", "META.json") if not os.path.isfile(os.path.join(d, n))]
    if miss:
        raise Unsupported(f"{d} is not a snapshot (missing {', '.join(miss)})")
    def read(n):
        with open(os.path.join(d, n)) as f:
            return json.load(f)
    return {"export": read("export.json"), "meta": read("META.json"), "settings": read("settings.json") if os.path.isfile(os.path.join(d, "settings.json")) else None}


def check_resume(stage, export, recording=None):
    """Raises Unsupported unless a run can restart after `stage` from this export. Returns the run_job kinds to queue.

    Why (file:line in the app, see the README): import_manifest stores segments and the current speaker map only; it ignores text_en and
    queues no roles job, and the diarization turns are not exported.
    """
    if stage in ("diarize",):
        raise Unsupported("diarize has no snapshot: the turns (diarization_turns) are not in export or results, and import_session with audio always diarizes again")
    if stage not in STAGES:
        raise Unsupported(f"unknown stage {stage!r}; one of {', '.join(STAGES)}")
    sessions = [s for s in export.get("sessions", []) if recording in (None, s.get("session"), s.get("client"))]
    if not sessions:
        raise Unsupported(f"the export has no session for {recording!r}")
    if stage == "summary":
        raise Unsupported("summary is the last stage: nothing is left to run")
    if stage in ("translate", "roles", "window"):
        langs = {c["id"]: c.get("lang") for c in export.get("clients", [])}
        for s in sessions:
            if langs.get(s["client"], "en") != "en" and export.get("processing_language", "english") == "english":
                raise Unsupported(
                    f"cannot resume after {stage} for a {langs.get(s['client'])} client: import_manifest drops text_en (QaApi.kt importOne reads text only), "
                    "so the English translation made by the translate stage is lost and everything after it would read the untranslated text (or translate would run again). Needs the proposed change: import text_en as the "
                    "stored English translation (README, section 'Small change needed')")
    nxt = STAGES[STAGES.index(stage) + 1]
    # after-transcribe restarts at translate; after-translate at roles; after-roles at window; after-window at summary.
    return RESUME_JOBS[nxt] if nxt in RESUME_JOBS else [nxt]


def resume_manifest(export, recording=None):
    """The import_manifest document for a resume: the export's clients and sessions, segments and speaker map, process:false (nothing queued)."""
    sessions = []
    for s in export.get("sessions", []):
        if recording not in (None, s.get("session"), s.get("client")):
            continue
        e = {"client": s["client"], "session": s["session"], "started_at": s["started_at"],
             "segments": [{"startMs": g["startMs"], "endMs": g["endMs"], "speaker": g.get("speaker"), "text": g["text"]} for g in s["segments"]]}
        if s.get("speaker_map"):
            e["speaker_map"] = s["speaker_map"]
        sessions.append(e)
    keep = {s["client"] for s in sessions}
    clients = [{"id": c["id"], "name": c["name"], "lang": c["lang"]} for c in export.get("clients", []) if c["id"] in keep]
    return {"clients": clients, "sessions": sessions, "process": False}


def stage_finished(stage, status_jobs, polls_idle):
    """status_jobs is status.data.jobs. `summary` is queued by the window job, so it is finished only when the queue was idle twice in a row."""
    if stage == "summary":
        return bool(status_jobs.get("idle")) and polls_idle >= 2
    return not (set(status_jobs.get("open_kinds") or []) & STAGE_KINDS[stage])


def wait_stage(stage, get_jobs, sleep, poll_s=3.0, timeout_s=14400.0):
    """Polls get_jobs() (status.data.jobs) until `stage` is finished; returns the jobs dict seen then. Raises RuntimeError when the queue is idle with
    the stage's jobs still open (blocked behind a failed job) or on timeout."""
    waited, idle = 0.0, 0
    while True:
        jobs = get_jobs()
        idle = idle + 1 if jobs.get("idle") else 0
        if stage_finished(stage, jobs, idle):
            return jobs
        if jobs.get("idle") and idle >= 2:
            raise RuntimeError(f"queue idle but {stage} jobs are still open (blocked behind a failed job): {jobs}")
        if waited >= timeout_s:
            raise RuntimeError(f"timeout waiting for {stage}: {jobs}")
        sleep(poll_s)
        waited += poll_s
