package com.therapylocal.app.qaapi

import com.therapylocal.app.data.Job
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.jobs.QueueSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** JVM tests of the QA control API (AD-16): parsing, JSON shape, the shell-user check, the locked-vault rule, refusal on a non-QA id, paths. */
class QaApiTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Fake(override val applicationId: String = "com.therapylocal.app.qa", var state: VaultState = VaultState.UNLOCKED, override val filesRoot: File) : QaBackend {
        val calls = ArrayList<String>()
        var password = "12345678"
        override fun vaultState() = state
        override fun status() = JSONObject().put("vault", state.name).also { calls += "status" }
        override fun setup(password: String) { calls += "setup:$password"; this.password = password; state = VaultState.UNLOCKED }
        override fun unlock(password: String): Boolean = (password == this.password).also { if (it) state = VaultState.UNLOCKED; calls += "unlock" }
        override fun seedClients(clients: List<QaClient>) = JSONObject().put("n", clients.size).also { calls += "seed" }
        override fun importSession(client: String, file: File, session: String?, date: String?, language: String?) = JSONObject().put("session", session ?: "x").also { calls += "import" }
        override fun waitIdle(timeoutMs: Long) = JSONObject().put("timeout_ms", timeoutMs).also { calls += "wait" }
        override fun results(client: String?, session: String?, queries: JSONArray?) = JSONObject().put("client", client ?: JSONObject.NULL).also { calls += "results" }
        override fun swapRoles(session: String) = JSONObject().put("session", session).also { calls += "swap" }
        override fun setSummaryFormat(id: String) = JSONObject().put("format", id).also { calls += "format" }
        override fun importTranscript(client: String, session: String, startedAt: Long?, language: String?, segments: List<QaSegment>, speakerMap: JSONObject?, process: Boolean) =
            JSONObject().put("session", session).put("segments", segments.size).put("map", speakerMap != null).put("process", process).also { calls += "transcript:$client" }
        override fun exportData(client: String?, session: String?) = JSONObject().put("format", "x").also { calls += "export" }
        override fun engineCall(op: String, args: JSONObject, file: File?, choice: BackendChoice) =
            JSONObject().put("op", op).put("backend", choice.backend).put("force", choice.force).put("file", file?.name ?: JSONObject.NULL).also { calls += op }
        override fun runJob(kind: String, session: String, jobId: Long?, choice: BackendChoice, timeoutMs: Long) =
            JSONObject().put("kind", kind).put("timeout_ms", timeoutMs).also { calls += "job:$kind" }
        override fun reset() = JSONObject().put("erased", true).also { calls += "reset" }
    }

    private fun api(f: Fake, uid: Int = SHELL_UID) = QaApi(f) { uid }
    private fun fake(state: VaultState = VaultState.UNLOCKED, id: String = "com.therapylocal.app.qa") = Fake(id, state, tmp.root)
    private fun code(r: JSONObject) = r.getJSONObject("error").getString("code")

    @Test fun successShape() {
        val r = api(fake()).call("status", "")
        assertEquals(API_VERSION, r.getInt("api_version"))
        assertEquals("status", r.getString("command"))
        assertTrue(r.getBoolean("ok"))
        assertTrue(r.has("data"))
    }

    @Test fun errorShape() {
        val r = api(fake()).call("nope", "")
        assertFalse(r.getBoolean("ok"))
        assertEquals(API_VERSION, r.getInt("api_version"))
        assertEquals("unknown_command", code(r))
        assertTrue(r.getJSONObject("error").getString("message").isNotEmpty())
    }

    @Test fun onlyTheShellUserIsAccepted() {
        for (uid in listOf(0, 1000, 10123, 99999)) {
            val f = fake()
            assertEquals("forbidden_caller", code(api(f, uid).call("status", "")))
            assertTrue(f.calls.isEmpty())
        }
        assertTrue(QaGuard.callerAllowed(SHELL_UID))
    }

    @Test fun refusesEveryCommandOnANonQaApplicationId() {
        for (id in listOf("com.therapylocal.app", "com.therapylocal.app.debug", "com.example.qa.x")) {
            val f = fake(id = id)
            val a = api(f)
            for (c in a.commandNames) assertEquals("$id $c", "not_qa_build", code(a.call(c, "{}")))
            assertTrue(f.calls.isEmpty())
        }
        assertTrue(QaGuard.isQaApplicationId("com.therapylocal.app.qa"))
        assertFalse(QaGuard.isQaApplicationId("com.therapylocal.app"))
    }

    @Test fun lockedVaultAllowsOnlyStatusSetupUnlock() {
        val locked = fake(VaultState.LOCKED)
        val a = api(locked)
        for (c in a.commandNames - setOf("status", "setup", "unlock")) assertEquals(c, "vault_locked", code(a.call(c, "{}")))
        assertTrue(a.call("status", "").getBoolean("ok"))
        assertTrue(locked.calls == listOf("status"))
        val fresh = api(fake(VaultState.NOT_SET_UP))
        assertEquals("vault_not_set_up", code(fresh.call("seed_clients", "{}")))
        assertEquals("vault_not_set_up", code(fresh.call("unlock", "{}")))
    }

    @Test fun setupDefaultsToTheStandardPasswordAndUnlockChecksIt() {
        val f = fake(VaultState.NOT_SET_UP)
        assertTrue(api(f).call("setup", "").getBoolean("ok"))
        assertEquals("setup:$STANDARD_QA_PASSWORD", f.calls.last())
        assertEquals("vault_already_set_up", code(api(f).call("setup", "")))
        f.state = VaultState.LOCKED
        assertEquals("bad_password", code(api(f).call("unlock", """{"password":"wrongwrong"}""")))
        assertTrue(api(f).call("unlock", "").getBoolean("ok"))
        assertEquals("12345678", STANDARD_QA_PASSWORD)
        assertEquals("bad_request", code(api(fake(VaultState.NOT_SET_UP)).call("setup", """{"password":"short"}""")))
    }

    @Test fun argumentsAreJsonOrBase64() {
        val a = api(fake())
        assertEquals("a", a.call("swap_roles", """{"session":"a"}""").getJSONObject("data").getString("session"))
        val b64 = "b64:" + Base64.getEncoder().encodeToString("""{"session":"zz"}""".toByteArray())
        assertEquals("zz", a.call("swap_roles", b64).getJSONObject("data").getString("session"))
        assertEquals("bad_request", code(a.call("swap_roles", "not json")))
        assertEquals("bad_request", code(a.call("swap_roles", "b64:***")))
        assertEquals("bad_request", code(a.call("swap_roles", "{}"))) // missing session
    }

    @Test fun seedClientsValidatesTheList() {
        val a = api(fake())
        assertEquals(2, a.call("seed_clients", """{"clients":[{"id":"david-m","name":"David M.","lang":"en"},{"id":"eva-s","name":"Eva S.","lang":"cs"}]}""").getJSONObject("data").getInt("n"))
        assertEquals("bad_request", code(a.call("seed_clients", """{"clients":[{"id":"Bad Id","name":"X"}]}""")))
        assertEquals("bad_request", code(a.call("seed_clients", """{"clients":[]}""")))
        assertEquals("bad_request", code(a.call("seed_clients", "{}")))
    }

    @Test fun waitIsBoundedPerCall() {
        val a = api(fake())
        assertEquals(20_000L, a.call("wait", "{}").getJSONObject("data").getLong("timeout_ms"))
        assertEquals(50_000L, a.call("wait", """{"timeout_s":9999}""").getJSONObject("data").getLong("timeout_ms"))
        assertEquals(1_000L, a.call("wait", """{"timeout_s":0}""").getJSONObject("data").getLong("timeout_ms"))
    }

    @Test fun importOnlyReadsInsideTheFilesFolder() {
        val wav = File(tmp.root, "in/a.wav").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val a = api(fake())
        assertTrue(a.call("import_session", """{"client":"c","file":"in/a.wav"}""").getBoolean("ok"))
        assertTrue(a.call("import_session", """{"client":"c","file":"${wav.path}"}""").getBoolean("ok"))
        assertEquals("bad_path", code(a.call("import_session", """{"client":"c","file":"../x.wav"}""")))
        assertEquals("bad_path", code(a.call("import_session", """{"client":"c","file":"/etc/passwd"}""")))
        assertEquals("not_found", code(a.call("import_session", """{"client":"c","file":"in/missing.wav"}""")))
        assertEquals("bad_request", code(a.call("import_session", """{"client":"c","file":"in/a.wav","date":"12/11/2026"}""")))
        assertEquals("bad_request", code(a.call("import_session", """{"client":"c","file":"in/a.wav","session":"../../x"}""")))
    }

    @Test fun aBackendExceptionNeverLeaksItsMessage() {
        val f = object : QaBackend by fake() {
            override fun swapRoles(session: String): JSONObject = throw IllegalStateException("the patient said something private")
        }
        val r = QaApi(f) { SHELL_UID }.call("swap_roles", """{"session":"s"}""")
        assertEquals("failed", code(r))
        assertFalse(r.toString().contains("private"))
    }

    @Test fun queueIdleIgnoresJobsBlockedByAFailure() {
        fun job(id: Long, status: JobStatus, deps: List<Long> = emptyList()) = Job(id, "k", "s", "c", 0, null, "", status, 0, 3, null, 0, deps)
        assertTrue(QaQueueState.idle(QueueSnapshot.EMPTY))
        val running = QueueSnapshot.of(listOf(job(1, JobStatus.Running)), null, false)
        assertFalse(QaQueueState.idle(running))
        val pending = QueueSnapshot.of(listOf(job(1, JobStatus.Pending)), null, false)
        assertFalse(QaQueueState.idle(pending))
        val blocked = QueueSnapshot.of(listOf(job(1, JobStatus.Failed), job(2, JobStatus.Pending, listOf(1))), null, false)
        assertTrue(QaQueueState.idle(blocked))
        val j = QaQueueState.toJson(blocked)
        assertEquals(1, j.getInt("failed"))
        assertEquals(1, j.getInt("blocked"))
    }

    @Test fun importTakesAFileOrATranscriptNotBoth() {
        File(tmp.root, "a.wav").writeBytes(byteArrayOf(1))
        val a = api(fake())
        val seg = """{"startMs":0,"endMs":1000,"speaker":"S0","text":"hello"}"""
        val ok = a.call("import_session", """{"client":"c","session":"s1","segments":[$seg],"speaker_map":{"S0":"patient"}}""")
        assertTrue(ok.toString(), ok.getBoolean("ok"))
        assertTrue(ok.getJSONObject("data").getBoolean("map"))
        assertEquals("bad_request", code(a.call("import_session", """{"client":"c","session":"s1","file":"a.wav","segments":[$seg]}""")))
        assertEquals("bad_request", code(a.call("import_session", """{"client":"c","session":"s1"}""")))
        assertEquals("bad_request", code(a.call("import_session", """{"client":"c","segments":[$seg]}""")))
        assertEquals("bad_request", code(a.call("import_session", """{"client":"c","session":"s1","segments":[$seg],"speaker_map":{"S0":"boss"}}""")))
        assertEquals("bad_request", code(a.call("import_session", """{"client":"c","session":"s1","segments":[{"text":""}]}""")))
    }

    @Test fun manifestImportsClientsThenSessionsInDateOrderAndKeepsGoingOnAnError() {
        val f = fake()
        val m = """{"clients":[{"id":"c","name":"C X","lang":"en"}],"sessions":[
            {"client":"c","session":"b","date":"2026-02-01","segments":[{"text":"x"}]},
            {"client":"c","session":"a","date":"2026-01-01","segments":[{"text":"x"}]},
            {"client":"c","session":"bad","date":"2026-03-01","file":"../nope.wav"}]}"""
        val r = api(f).call("import_manifest", m).getJSONObject("data")
        assertEquals(2, r.getInt("count"))
        assertEquals(1, r.getJSONArray("failed").length())
        assertEquals("bad_path", r.getJSONArray("failed").getJSONObject(0).getString("code"))
        assertEquals("a", r.getJSONArray("imported").getJSONObject(0).getString("session"))
        assertEquals(listOf("seed", "transcript:c", "transcript:c"), f.calls)
    }

    @Test fun engineCallsTakeABackendPerCall() {
        File(tmp.root, "a.wav").writeBytes(byteArrayOf(1))
        val a = api(fake())
        val t = a.call("transcribe", """{"file":"a.wav","language":"cs","backend":"gpu","force":true}""").getJSONObject("data")
        assertEquals("gpu", t.getString("backend")); assertTrue(t.getBoolean("force")); assertEquals("a.wav", t.getString("file"))
        assertEquals("auto", a.call("diarize", """{"file":"a.wav"}""").getJSONObject("data").getString("backend"))
        assertEquals("npu", a.call("embed", """{"text":"hi","backend":"NPU"}""").getJSONObject("data").getString("backend"))
        assertEquals("cpu", a.call("ask", """{"user":"hi","backend":"cpu"}""").getJSONObject("data").getString("backend"))
        assertEquals("bad_request", code(a.call("ask", """{"user":"hi","backend":"tpu"}""")))
        assertEquals("bad_request", code(a.call("ask", """{"system":"s"}""")))
        assertEquals("bad_request", code(a.call("embed", """{"texts":[]}""")))
        assertEquals("bad_path", code(a.call("transcribe", """{"file":"/etc/passwd"}""")))
    }

    @Test fun runJobKindsAreTheOnesWithoutARef() {
        val a = api(fake())
        assertTrue(a.call("run_job", """{"kind":"window","session":"s","timeout_s":5}""").getBoolean("ok"))
        assertEquals("bad_request", code(a.call("run_job", """{"kind":"extract","session":"s"}""")))
    }

    @Test fun everyNewCommandIsGatedByTheLockedVault() {
        val a = api(fake(VaultState.LOCKED))
        for (c in listOf("import_clients", "import_manifest", "export", "transcribe", "diarize", "embed", "ask", "run_job")) assertEquals(c, "vault_locked", code(a.call(c, "{}")))
    }
}
