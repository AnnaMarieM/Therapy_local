"""Host tests of qa/stage_cache.py and the resume checks of qa/workflow_run.py (no device).  python3 -m unittest qa/test_stage_cache.py"""
import json, os, sys, tempfile, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stage_cache as sc
import workflow_run as wr


def export(lang="cs", speaker_map=True):
    seg = lambda i, sp, t: {"idx": i, "startMs": i * 1000, "endMs": i * 1000 + 900, "speaker": sp, "role": None, "text": t, "text_en": "en " + t}
    s = {"client": "tomas-b", "session": "tomas-b-quality-cs", "started_at": 1768000000000,
         "segments": [seg(1, "S0", "ahoj"), seg(2, "S1", "dobry den")], "facts": [{"heading": "x"}], "summary": {"state": "ready"}}
    s["speaker_map"] = {"S0": "therapist", "S1": "patient"} if speaker_map else None
    return {"format": "therapy-qa-manifest", "format_version": 1, "processing_language": "english",
            "clients": [{"id": "tomas-b", "name": "Tomas B.", "lang": lang, "created_at": 1}], "sessions": [s]}


def snap(root, stage, exp, workflow="quality-cs"):
    d = sc.snapshot_dir(root, "poco", "quality-cs-beam5-perturn", "tomas-b", stage)
    sc.write_snapshot(d, json.dumps(exp), "{}", '{"settings":[]}', "evt=stage_done\n", {"workflow": workflow, "stage": stage})
    return d


class Layout(unittest.TestCase):
    def test_dir_and_files(self):
        with tempfile.TemporaryDirectory() as r:
            d = snap(r, "translate", export())
            self.assertTrue(d.endswith("poco/quality-cs-beam5-perturn/tomas-b/after-translate"))
            self.assertEqual(sorted(os.listdir(d)), sorted(sc.FILES))
            self.assertRegex(open(os.path.join(d, "captured_at_utc.txt")).read(), r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ\n$")

    def test_never_overwrites(self):
        with tempfile.TemporaryDirectory() as r:
            snap(r, "roles", export())
            with self.assertRaises(FileExistsError):
                snap(r, "roles", export())

    def test_unknown_stage(self):
        with self.assertRaises(ValueError):
            sc.snapshot_dir("/x", "poco", "r", "t", "diarize")

    def test_load_rejects_non_snapshot(self):
        with tempfile.TemporaryDirectory() as r:
            with self.assertRaises(sc.Unsupported):
                sc.load_snapshot(r)


class Resume(unittest.TestCase):
    def test_after_transcribe_runs_translate_onward(self):
        self.assertEqual(sc.check_resume("transcribe", export(), "tomas-b"), ["translate", "roles", "window"])

    def test_after_translate_unsupported_for_czech(self):
        with self.assertRaisesRegex(sc.Unsupported, "text_en"):
            sc.check_resume("translate", export(), "tomas-b")

    def test_roles_window_unsupported_for_czech(self):
        for st in ("roles", "window"):
            with self.assertRaises(sc.Unsupported):
                sc.check_resume(st, export(), "tomas-b")

    def test_english_client_resumes_after_translate_and_window(self):
        self.assertEqual(sc.check_resume("translate", export("en"), "tomas-b"), ["roles", "window"])
        self.assertEqual(sc.check_resume("window", export("en"), "tomas-b"), ["summary"])

    def test_last_stage_and_diarize(self):
        for st in ("summary", "diarize", "nope"):
            with self.assertRaises(sc.Unsupported):
                sc.check_resume(st, export("en"), "tomas-b")

    def test_unknown_recording(self):
        with self.assertRaisesRegex(sc.Unsupported, "no session"):
            sc.check_resume("transcribe", export(), "jana-n")

    def test_manifest_drops_derived_and_does_not_queue(self):
        m = sc.resume_manifest(export(), "tomas-b")
        self.assertIs(m["process"], False)
        self.assertEqual(m["clients"], [{"id": "tomas-b", "name": "Tomas B.", "lang": "cs"}])
        e = m["sessions"][0]
        self.assertEqual(set(e), {"client", "session", "started_at", "segments", "speaker_map"})
        self.assertEqual(set(e["segments"][0]), {"startMs", "endMs", "speaker", "text"})
        self.assertNotIn("speaker_map", sc.resume_manifest(export(speaker_map=False))["sessions"][0])

    def test_resume_plan_checks(self):
        with tempfile.TemporaryDirectory() as r:
            d = snap(r, "transcribe", export())
            jobs, cached = wr.resume_plan("transcribe", d, "quality-cs", ["tomas-b"])
            self.assertEqual(jobs[0], "translate")
            with self.assertRaisesRegex(sc.Unsupported, "workflow"):
                wr.resume_plan("transcribe", d, "quality", ["tomas-b"])
            with self.assertRaisesRegex(sc.Unsupported, "after-roles"):
                wr.resume_plan("roles", d, "quality-cs", ["tomas-b"])
            with self.assertRaisesRegex(sc.Unsupported, "one recording"):
                wr.resume_plan("transcribe", d, "quality-cs", ["a", "b"])


class Waiting(unittest.TestCase):
    def run_wait(self, stage, seq):
        it = iter(seq)
        return sc.wait_stage(stage, lambda: next(it), lambda s: None)

    def J(self, kinds, idle=False):
        return {"open_kinds": kinds, "idle": idle}

    def test_waits_until_kind_gone(self):
        got = self.run_wait("translate", [self.J(["transcribe", "translate"]), self.J(["translate"]), self.J(["roles", "window"])])
        self.assertEqual(got["open_kinds"], ["roles", "window"])

    def test_window_waits_for_extract_and_commit(self):
        got = self.run_wait("window", [self.J(["window"]), self.J(["extract", "commit"]), self.J(["commit", "summary"]), self.J(["summary"])])
        self.assertEqual(got["open_kinds"], ["summary"])

    def test_summary_needs_idle_twice(self):
        got = self.run_wait("summary", [self.J([], True), self.J(["summary"]), self.J([], True), self.J([], True)])
        self.assertTrue(got["idle"])

    def test_blocked_behind_failure_raises(self):
        with self.assertRaisesRegex(RuntimeError, "blocked"):
            self.run_wait("window", [self.J(["window"], True)] * 3)

    def test_timeout(self):
        with self.assertRaisesRegex(RuntimeError, "timeout"):
            sc.wait_stage("roles", lambda: self.J(["roles"]), lambda s: None, poll_s=10, timeout_s=30)


if __name__ == "__main__":
    unittest.main()
