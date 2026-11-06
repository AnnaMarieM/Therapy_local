package com.therapylocal.app.models

import com.therapylocal.app.testsupport.MapPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpeechMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Lines : SpeechMigration.Log {
        val all = ArrayList<Pair<String, Map<String, Any?>>>()
        override fun line(warn: Boolean, evt: String, vararg f: Pair<String, Any?>) { all += evt to f.toMap() }
        fun count(evt: String) = all.count { it.first == evt }
    }

    private fun prefs(p: MapPrefs) = object : SpeechMigration.Prefs {
        override val all: Map<String, Any?> get() = p.all
        override fun remove(keys: Collection<String>) = p.edit().apply { keys.forEach { remove(it) } }.commit()
    }

    private fun legacyDir(root: File, id: String, bytes: Int = 10) = File(root, id).apply { mkdirs(); File(this, "model.onnx").writeBytes(ByteArray(bytes)) }

    @Test
    fun everyOldValueIsRemovedWithOneLine() {
        for (engine in listOf("whisper", "parakeet", "gemma", "canary", "", "garbage \u0000 ✓")) {
            val p = MapPrefs(mutableMapOf("engine" to engine, "whisper_size" to "small", "default_language" to "cs"))
            val log = Lines()
            SpeechMigration.run(prefs(p), null, log)
            assertEquals(setOf("default_language"), p.values.keys)
            assertEquals(1, log.count("speech_engine_migrated"))
            assertEquals(engine, log.all.single().second["from"])
        }
    }

    @Test
    fun anEmptyStoreAndASecondRunDoNothing() {
        val p = MapPrefs(mutableMapOf("engine" to "whisper"))
        val root = tmp.newFolder("models")
        legacyDir(root, "whisper-base")
        val empty = Lines(); SpeechMigration.run(prefs(MapPrefs()), null, empty); assertTrue(empty.all.isEmpty())
        val first = Lines(); SpeechMigration.run(prefs(p), root, first)
        assertEquals(1, first.count("speech_engine_migrated")); assertEquals(1, first.count("model_files_removed"))
        val second = Lines(); SpeechMigration.run(prefs(p), root, second)
        assertTrue(second.all.isEmpty())
    }

    @Test
    fun legacyFoldersGoAndOthersStay() {
        val root = tmp.newFolder("models")
        legacyDir(root, "whisper-tiny.en", 7); legacyDir(root, "parakeet-tdt-0.6b-v3", 5)
        val keep = legacyDir(root, "canary-1b-v2")
        val log = Lines(); SpeechMigration.run(prefs(MapPrefs()), root, log)
        assertFalse(File(root, "whisper-tiny.en").exists()); assertFalse(File(root, "parakeet-tdt-0.6b-v3").exists())
        assertTrue(keep.exists())
        assertEquals(setOf(7L, 5L), log.all.filter { it.first == "model_files_removed" }.map { it.second["bytes"] }.toSet())
    }

    @Test
    fun aStrayFileAtAFolderPathIsRemovedToo() {
        val root = tmp.newFolder("models")
        File(root, "whisper-base").writeText("x")
        SpeechMigration.run(prefs(MapPrefs()), root, Lines())
        assertFalse(File(root, "whisper-base").exists())
    }

    @Test
    fun aStoreThatCannotBeWrittenKeepsTheKeysAndRetriesNextStart() {
        val p = MapPrefs(mutableMapOf("engine" to "whisper"), commits = false)
        val log = Lines(); SpeechMigration.run(prefs(p), null, log)
        assertTrue("engine" in p.values); assertEquals(0, log.count("speech_engine_migrated")); assertEquals(1, log.count("speech_migration_failed"))
        p.commits = true
        SpeechMigration.run(prefs(p), null, log)
        assertFalse("engine" in p.values); assertEquals(1, log.count("speech_engine_migrated"))
    }

    @Test
    fun aThrowingStoreOrLoggerNeverEscapes() {
        val boom = object : SpeechMigration.Prefs {
            override val all: Map<String, Any?> get() = error("unreadable")
            override fun remove(keys: Collection<String>): Boolean = error("unwritable")
        }
        SpeechMigration.run(boom, tmp.newFolder("m"), Lines())
        SpeechMigration.run(boom, null) { _, _, _ -> error("log failed") }
    }

    @Test
    fun aMissingModelsRootIsFine() = SpeechMigration.run(prefs(MapPrefs()), File(tmp.root, "nope"), Lines())
}
