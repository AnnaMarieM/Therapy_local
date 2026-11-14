package com.therapylocal.app.qaapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The settings registry: typed parsing, ranges, the error text, the round trip. The app's real list ([appSettings]) needs a Context and is exercised on a phone. */
class QaSettingsTest {
    companion object {
        /** In-memory settings, one of each type, with the same shape as the app's. */
        fun fakeSettings(): List<QaSetting> {
            var mode = "a"; var fmt = "soap"; var flag = true; var count = 5L; var ratio = 0.5
            return listOf(
                QaSetting("mode", QaSettingType.ENUM, "a", "an enum", values = listOf("a", "b"), read = { mode }, apply = { mode = it as String }),
                QaSetting("summary_format", QaSettingType.ENUM, "soap", "the alias target", values = listOf("soap", "dap"), read = { fmt }, apply = { fmt = it as String }),
                QaSetting("flag", QaSettingType.BOOL, true, "a bool", read = { flag }, apply = { flag = it as Boolean }),
                QaSetting("count", QaSettingType.INT, 5L, "an int", min = 1.0, max = 10.0, read = { count }, apply = { count = it as Long }),
                QaSetting("ratio", QaSettingType.FLOAT, 0.5, "a float", min = 0.05, max = 1.0, read = { ratio }, apply = { ratio = it as Double }),
            )
        }
    }

    @Test fun theProcessingLanguageIsAnEnumOfTheAppsModesWithTheAppsDefault() {
        var mode = com.therapylocal.app.config.ProcessingLanguage.DEFAULT
        val s = QaSetting("processing_language", QaSettingType.ENUM, com.therapylocal.app.config.ProcessingLanguage.DEFAULT.key, "the pipeline's language",
            values = com.therapylocal.app.config.ProcessingLanguage.entries.map { it.key }, read = { mode.key }, apply = { mode = com.therapylocal.app.config.ProcessingLanguage.of(it as String) })
        assertEquals(listOf("english", "native"), s.values)
        assertEquals("english", s.default)
        val r = QaSettings.set(listOf(s), "processing_language", "native")
        assertEquals("english", r.getString("old")); assertEquals("native", r.getString("new")); assertTrue(r.getBoolean("changed"))
        assertTrue(bad { QaSettings.set(listOf(s), "processing_language", "czech") }.contains("english, native"))
        assertEquals(com.therapylocal.app.config.ProcessingLanguage.Native, mode)
    }

    private fun bad(block: () -> Unit): String {
        try { block() } catch (e: QaException) { assertEquals(QaCode.BAD_REQUEST, e.code); return e.message.orEmpty() }
        fail("expected bad_request"); return ""
    }

    @Test fun everyKeyHasACurrentValueInsideItsAllowedSet() {
        val all = QaSettings.get(fakeSettings()).getJSONArray("settings")
        val seen = HashSet<String>()
        for (i in 0 until all.length()) {
            val s = all.getJSONObject(i)
            assertTrue(seen.add(s.getString("key")))
            assertTrue(s.getString("description").isNotBlank())
            when (s.getString("type")) {
                "enum", "bool" -> {
                    val values = (0 until s.getJSONArray("values").length()).map { s.getJSONArray("values").get(it) }
                    assertTrue(s.getString("key"), s.get("value") in values)
                    assertTrue(s.getString("key"), s.get("default") in values)
                }
                "int", "float" -> {
                    assertTrue(s.getDouble("value") in s.getDouble("min")..s.getDouble("max"))
                    assertTrue(s.getDouble("default") in s.getDouble("min")..s.getDouble("max"))
                }
                else -> fail("type")
            }
        }
    }

    @Test fun badValuesAreRefusedWithTheAllowedOnesAndNothingChanges() {
        val l = fakeSettings()
        assertTrue(bad { QaSettings.set(l, "mode", "c") }.contains("a, b"))
        bad { QaSettings.set(l, "mode", "A") }          // no case folding
        bad { QaSettings.set(l, "flag", "yes") }
        bad { QaSettings.set(l, "flag", "1") }
        assertTrue(bad { QaSettings.set(l, "count", "11") }.contains("1 to 10"))
        bad { QaSettings.set(l, "count", "0") }
        bad { QaSettings.set(l, "count", "5.5") }       // no rounding
        bad { QaSettings.set(l, "count", "x") }
        bad { QaSettings.set(l, "ratio", "1.01") }
        bad { QaSettings.set(l, "ratio", "0.01") }      // no clamping
        bad { QaSettings.set(l, "ratio", "NaN") }
        bad { QaSettings.set(l, "ratio", "") }
        assertTrue(bad { QaSettings.set(l, "nope", "1") }.contains("mode, summary_format, flag, count, ratio"))
        assertEquals(listOf<Any>("a", "soap", true, 5L, 0.5), l.map { it.read() })
    }

    @Test fun roundTripReturnsOldAndNewAndReadsBack() {
        val l = fakeSettings()
        val r = QaSettings.set(l, "mode", "b")
        assertEquals("a", r.getString("old")); assertEquals("b", r.getString("new")); assertTrue(r.getBoolean("changed"))
        assertFalse(QaSettings.set(l, "mode", "b").getBoolean("changed"))
        assertFalse(QaSettings.set(l, "flag", "false").getBoolean("new"))
        assertEquals(10L, QaSettings.set(l, "count", "10").getLong("new"))
        assertEquals(0.05, QaSettings.set(l, "ratio", "0.05").getDouble("new"), 0.0)
        val after = QaSettings.get(l).getJSONArray("settings")
        assertEquals("b", after.getJSONObject(0).getString("value"))
        assertEquals(false, after.getJSONObject(2).getBoolean("value"))
        assertEquals(10L, after.getJSONObject(3).getLong("value"))
    }
}
