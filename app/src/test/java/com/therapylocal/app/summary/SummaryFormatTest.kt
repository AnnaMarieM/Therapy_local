package com.therapylocal.app.summary

import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.jobs.JobQueue
import com.therapylocal.app.testsupport.FakeJobRepo
import com.therapylocal.app.ui.data.SessionSummaryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The summary format setting: the registry, its prompt files, the stale-on-change rule and the example copy. */
class SummaryFormatTest {
    private val prompts = File("../demo/prompts")
    private val placeholder = Regex("\\{\\{([A-Z_]+)}}")

    @Test
    fun theRegistryHasTheFourFormatsAndSoapIsTheDefault() {
        assertEquals(listOf("soap", "dap", "birp", "narrative"), SummaryConfig.FORMATS.keys.toList())
        assertEquals("soap", SummaryConfig.DEFAULT_FORMAT_ID)
        assertEquals("soap", SummaryConfig.format(null).id)
        assertEquals("soap", SummaryConfig.format("not-a-format").id)
        assertEquals(listOf("Subjective", "Objective", "Assessment", "Plan"), SummaryConfig.format("soap").sections.map { it.label })
        assertEquals(listOf("Data", "Assessment", "Plan"), SummaryConfig.format("dap").sections.map { it.label })
        assertEquals(listOf("Behavior", "Intervention", "Response", "Plan"), SummaryConfig.format("birp").sections.map { it.label })
        assertTrue(SummaryConfig.format("narrative").sections.isEmpty())
    }

    @Test
    fun idsAndPromptFilesAreUniqueAndEveryIdIsKeyedByItself() {
        SummaryConfig.FORMATS.forEach { (key, f) -> assertEquals(key, f.id) }
        assertEquals(SummaryConfig.FORMATS.size, SummaryConfig.FORMATS.values.map { it.systemPrompt }.toSet().size)
        assertEquals(SummaryConfig.FORMATS.size, SummaryConfig.FORMATS.values.map { it.instruction() }.toSet().size)
    }

    @Test
    fun everyFormatPromptExistsInBothLanguagesWithTheSoapPlaceholdersAndNoHeadingText() {
        val soap = placeholder.findAll(File(prompts, SummaryConfig.PROMPT_FINAL_SYSTEM).readText()).map { it.groupValues[1] }.toSet()
        for (f in SummaryConfig.FORMATS.values) for (dir in listOf("", "cs/")) {
            val text = File(prompts, dir + f.systemPrompt).readText()
            assertEquals("${dir}${f.systemPrompt}", soap, placeholder.findAll(text).map { it.groupValues[1] }.toSet())
            for (label in SummaryConfig.ALL_LABELS) assertFalse("${dir}${f.systemPrompt} writes the heading $label itself", label in text)
            // The rules every format keeps.
            assertTrue(text.contains("{{MAX_SENTENCES}}") && text.contains("{{MAX_WORDS}}"))
        }
        for (dir in listOf("", "cs/")) for (f in SummaryConfig.FORMATS.values) {
            val text = File(prompts, dir + f.systemPrompt).readText()
            assertTrue("${dir}${f.systemPrompt} lost the rule not to invent", "invent" in text || "nevymýšlej" in text)
            assertTrue("${dir}${f.systemPrompt} lost the rule not to diagnose", "diagnose" in text || "diagnóz" in text)
            assertTrue("${dir}${f.systemPrompt} lost the rule not to advise", "advice" in text || "rady" in text)
        }
    }

    @Test
    fun theLoadedFinalPromptCarriesTheChosenFormatOnly() {
        for (lang in listOf("en", "cs")) for (f in SummaryConfig.FORMATS.values) {
            val loaded = SummaryPrompts.load({ File(prompts, it).readText() }, lang, f.id)
            assertEquals(f.id, loaded.formatId)
            assertTrue(f.instruction() in loaded.finalSystem)
            assertTrue("{{" !in loaded.finalSystem.replace("{{TEXT}}", ""))
            for (other in SummaryConfig.FORMATS.values.filter { it.id != f.id }) {
                // An heading of another format that this one does not have is not in the prompt.
                for (l in other.sections.map { it.label } - f.sections.map { it.label }.toSet()) assertFalse("$lang ${f.id} has $l", l in loaded.finalSystem)
            }
            assertTrue(loaded.notesSystem == SummaryPrompts.load({ File(prompts, it).readText() }, lang).notesSystem)
        }
    }

    @Test
    fun theFormatIdIsPartOfTheFingerprintAndOfThePromptVersion() {
        val ids = SummaryConfig.FORMATS.keys
        for (id in ids) assertEquals(id, SummaryConfig.fingerprint(id).substringBefore("|"))
        assertEquals(ids.size, ids.map { SummaryConfig.fingerprint(it) }.toSet().size)
        // The same prompt texts under another format id are another version.
        val versions = ids.map { SummaryPrompts("s", "u", "n", "nu", it).version() }
        assertEquals(ids.size, versions.toSet().size)
    }

    @Test
    fun aSummaryWrittenInAnyFormatIsSplitAtItsOwnHeadings() {
        assertEquals(listOf("Data", "Assessment", "Plan"), SummarySections.parse("Data: a\nAssessment: b\nPlan: c").map { it.label })
        assertEquals(listOf("Behavior", "Intervention", "Response", "Plan"), SummarySections.parse("Behavior: a\nIntervention: b\nResponse: c\nPlan: d").map { it.label })
        assertEquals(listOf(""), SummarySections.parse("A plain narrative.\n\nIn short paragraphs.").map { it.label })
    }

    // ---- changing the format ----

    private class Wired(client: String = "c") {
        val store = FakeSummaryRepo(shortSession()).also { it.addMap(MAP_S1_THERAPIST) }
        val jobs = FakeJobRepo().also { it.sessions["s"] = "c" to 1L }
        val queue = JobQueue(jobs, clock = { 1L })
        var format = "soap"
        val saved = ArrayList<String>()
        val svc = SessionSummaries(store, clock = { 5L })
        fun prompts() = SummaryPrompts("FINAL-SYSTEM", "FINAL-USER {{TEXT}}", "NOTES-SYSTEM", "NOTES-USER {{PART}}/{{PARTS}} {{TEXT}}", format)
        val api = SessionSummaryRepository(
            repo = { store },
            lastSession = { c -> if (c == client) "s" else null },
            promptVersion = { prompts().version() },
            model = { "m" },
            generating = { queue.unfinished(JobGraph.SUMMARY, it)?.let { j -> j.status != JobStatus.Failed } == true },
            enqueue = { JobGraph.enqueueSummary(queue, it) },
            clients = { listOf("c", "other") },
            format = { format },
            saveFormat = { format = it; saved += it },
        )
        fun make() = svc.build("s", prompts(), "m", TreeSummary(), RecordingAsk())
        fun summaryJobs() = jobs.jobs().count { it.kind == JobGraph.SUMMARY }
    }

    @Test
    fun changingTheFormatMakesTheSummaryStaleAndQueuesARebuild() {
        val w = Wired()
        w.make()
        assertEquals(SummaryState.Ready, w.api.summary("s").state)
        assertTrue(w.api.changeFormat("dap"))
        assertEquals(listOf("dap"), w.saved)
        assertTrue(w.store.stored!!.stale)
        assertEquals(SummaryState.Generating, w.api.summary("s").state)
        assertEquals(1, w.summaryJobs())
        // The old text stays until the new one replaces it, and the new one is current for the new format.
        assertTrue(w.api.summary("s").text!!.isNotBlank())
        val job = w.queue.claimNext { true }!!
        w.make()
        w.queue.complete(job)
        assertEquals(SummaryState.Ready, w.api.summary("s").state)
        assertEquals(w.prompts().version(), w.store.stored!!.provenance.promptVersion)
    }

    @Test
    fun choosingTheSameOrAnUnknownFormatChangesNothing() {
        val w = Wired()
        w.make()
        assertFalse(w.api.changeFormat("soap"))
        assertFalse(w.api.changeFormat("nope"))
        assertTrue(w.saved.isEmpty())
        assertFalse(w.store.stored!!.stale)
        assertEquals(0, w.summaryJobs())
    }

    @Test
    fun aSessionWithoutASummaryIsNotQueuedAndGoingBackMakesItCurrentAgain() {
        val w = Wired()
        assertTrue(w.api.changeFormat("birp"))
        assertEquals(0, w.summaryJobs())
        w.make() // as made under birp
        assertTrue(w.api.changeFormat("soap"))
        assertTrue(w.store.stored!!.stale)
        w.queue.complete(w.queue.claimNext { true }!!)
        w.make()
        assertFalse(w.api.changeFormat("soap"))
    }

    @Test
    fun aFormatChangeWhileOneIsBeingMadeQueuesNoSecondJob() {
        val w = Wired()
        w.make()
        JobGraph.enqueueSummary(w.queue, "s")
        assertTrue(w.api.changeFormat("narrative"))
        assertEquals(1, w.summaryJobs())
    }

    // ---- the example copy ----

    private fun strings(dir: String): Map<String, String> {
        val out = HashMap<String, String>()
        File("src/main/res/$dir").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
            for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.takeIf { it.tagName == "string" }?.let { out[it.getAttribute("name")] = it.textContent }
        }
        return out
    }

    @Test
    fun everyFormatHasANameAndAnExampleInBothLanguages() {
        for (dir in listOf("values", "values-cs")) {
            val s = strings(dir)
            for (id in SummaryConfig.FORMATS.keys) {
                assertTrue("$dir: summary_format_$id", s["summary_format_$id"].orEmpty().isNotBlank())
                val example = s["summary_example_$id"].orEmpty()
                assertTrue("$dir: summary_example_$id", example.isNotBlank())
                val words = example.split(Regex("\\s+")).count { it.isNotEmpty() }
                assertTrue("$dir: summary_example_$id has $words words", words in 90..220)
            }
        }
    }

    @Test
    fun theExamplesFollowTheirFormat() {
        for (dir in listOf("values", "values-cs")) {
            val s = strings(dir)
            for ((id, f) in SummaryConfig.FORMATS) {
                val paragraphs = s.getValue("summary_example_$id").split("\\n\\n")
                if (f.sections.isEmpty()) assertTrue("$dir $id is a narrative with no headings", paragraphs.size in 2..4 && paragraphs.none { p -> SummaryConfig.ALL_LABELS.any { p.startsWith("$it:") } })
                else assertEquals("$dir $id", f.sections.map { it.label }, paragraphs.map { it.substringBefore(":") })
            }
        }
    }

    @Test
    fun theUiKnowsEveryRegisteredFormat() {
        val ui = File("src/main/java/com/therapylocal/app/ui/app/SummaryFormatPicker.kt").readText()
        for (id in SummaryConfig.FORMATS.keys) assertTrue(id, "\"$id\" to Texts(R.string.summary_format_$id, R.string.summary_example_$id)" in ui)
    }
}
