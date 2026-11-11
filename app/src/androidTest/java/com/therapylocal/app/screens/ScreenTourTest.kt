package com.therapylocal.app.screens

import android.graphics.Bitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.vault.Vault
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import java.io.File

/**
 * AD-15: a screenshot of every screen in [SCREENS] at every size in [SIZES], one PNG per screen and size, in
 * `<app files>/screens/<screen id>/<WxH>.png`, for the pull request comment (scripts/publish-pr-screenshots.sh).
 *
 * Skipped unless the run is started with `-e screens 1` (CI's screenshot job), so the ordinary lanes only pass over it. It never runs where
 * a vault exists, and draws only the fake data of [seed].
 */
@RunWith(Parameterized::class)
class ScreenTourTest(private val screen: Screen, private val size: WindowSize) {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var store: MindStore
    private lateinit var repo: ClientRepository

    @Before
    fun seed() {
        assumeTrue("not a screenshot run (-e screens 1)", InstrumentationRegistry.getArguments().getString("screens") == "1")
        assumeFalse("a vault exists on this device: the tour never touches one", Vault.isSetUp(context))
        val seeded = seed(context, DB)
        store = seeded.first
        repo = seeded.second
    }

    @After
    fun erase() {
        if (::store.isInitialized) store.close()
        context.deleteDatabase(DB)
    }

    @Test
    fun screenshot() {
        try {
            screen.show(Tour(compose, context, repo, size))
            save()
            note("ok", "")
        } catch (e: AssumptionViolatedException) {
            note("skip", e.message.orEmpty())
            throw e
        } catch (t: Throwable) {
            note("fail", (t.message ?: t.javaClass.simpleName).lineSequence().first())
            throw t
        }
    }

    private fun save() {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: error("no screenshot")
        var out = shot
        if (size !== DEVICE) {
            val n = compose.onNode(hasTestTag(FRAME)).fetchSemanticsNode()
            val at = n.positionOnScreen
            val x = at.x.toInt().coerceIn(0, shot.width - 1)
            val y = at.y.toInt().coerceIn(0, shot.height - 1)
            out = Bitmap.createBitmap(shot, x, y, minOf(n.size.width, shot.width - x), minOf(n.size.height, shot.height - y))
        }
        // Small files: the branch holds a few hundred of them per pull request.
        val k = MAX_SIDE_PX.toFloat() / maxOf(out.width, out.height)
        if (k < 1f) out = Bitmap.createScaledBitmap(out, (out.width * k).toInt(), (out.height * k).toInt(), true)
        val file = File(dir(), "${screen.id}/${size.key}.png").also { it.parentFile!!.mkdirs() }
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun note(result: String, message: String) {
        File(dir(), "results.tsv").appendText("${screen.id}\t${size.key}\t$result\t${message.replace('\t', ' ')}\n")
    }

    private fun dir() = File(context.getExternalFilesDir(null), "screens").also { it.mkdirs() }

    companion object {
        const val DB = "screen-tour.db"
        const val MAX_SIDE_PX = 900

        @JvmStatic
        @Parameters(name = "{0}@{1}")
        fun cases(): List<Array<Any>> {
            // The ordinary lanes run every test of the APK: for them there is one case, which skips at once, not 266 that each start an activity.
            if (InstrumentationRegistry.getArguments().getString("screens") != "1") return listOf(arrayOf(SCREENS.first(), SIZES.first()))
            writeManifest()
            return SCREENS.flatMap { s -> (if (s.dialog) listOf(DEVICE) else SIZES).map { arrayOf<Any>(s, it) } }
        }

        /** What the comment is built from: the sizes and the flows with their steps, in order. Written by every shard, identical. */
        private fun writeManifest() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val d = File(ctx.getExternalFilesDir(null), "screens").also { it.mkdirs() }
            File(d, "manifest.tsv").writeText(
                SIZES.joinToString("") { "size\t${it.key}\t${it.name}\t${it.w}\t${it.h}\n" } +
                    SCREENS.joinToString("") { "screen\t${it.id}\t${it.flow}\t${it.caption}\t${it.dialog}\n" },
            )
        }
    }
}
