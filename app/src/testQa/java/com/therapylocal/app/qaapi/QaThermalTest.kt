package com.therapylocal.app.qaapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QaThermalTest {
    @Test fun socZonesAreRecognisedAndNonTemperatureZonesLeftOut() {
        for (n in listOf("cpu-0-0-0", "cpuss-1-0", "gpuss-3", "nsphvx-1", "nsp0", "aoss-0", "mdmss-1", "ddr", "video", "camera-0", "pm8550_tz", "pmih010x_tz", "xo-therm", "quiet_therm", "skin"))
            assertTrue(n, QaThermal.isSoc(n))
        for (n in listOf("battery", "wifi_therm", "conn_therm", "charger_therm0")) assertFalse(n, QaThermal.isSoc(n))
        for (n in listOf("vbat", "socd", "pm8550-bcl-lvl0", "pmih010x-ibat-lvl1")) { assertFalse(n, QaThermal.isTemperatureZone(n)); assertFalse(n, QaThermal.isSoc(n)) }
    }

    @Test fun celsiusFromSysfsText() {
        assertEquals(51.7, QaThermal.celsius("51700\n")!!, 1e-9)
        assertEquals(37.0, QaThermal.celsius("37")!!, 1e-9)
        assertNull(QaThermal.celsius(null))
        assertNull(QaThermal.celsius("No data available"))
        assertNull(QaThermal.celsius("-273000"))
    }

    private fun sample(bat: Double? = 40.1, soc: List<Pair<String, Double>> = listOf("cpu-0-0-0" to 51.7, "gpuss-0" to 48.6), status: Int? = 0) =
        QaThermal.Sample("TRANSCRIBE", bat, 6, 3406, -432000L, "usb", status, 0.8f, soc)

    @Test fun lineFieldsAreKeyValueNumbersOnly() {
        val f = QaThermal.fields(sample()).toMap()
        assertEquals("TRANSCRIBE", f["job"]); assertEquals("40.1", f["bat_c"]); assertEquals(3406, f["mv"]); assertEquals(-432000L, f["ua"])
        assertEquals("51.7", f["soc_max_c"]); assertEquals("cpu-0-0-0:51.7,gpuss-0:48.6", f["soc"]); assertEquals("0.80", f["headroom_10s"])
        assertTrue(f.values.none { it.toString().contains(' ') || it.toString().contains('=') })
    }

    @Test fun unreadableThingsAreLeftOutNotLoggedAsErrors() {
        val f = QaThermal.fields(QaThermal.Sample(null, null, null, null, null, null, null, Float.NaN, emptyList())).toMap()
        assertEquals(mapOf<String, Any?>("job" to "none"), f)
    }

    @Test fun runSummaryKeepsMaxMeanAndStatus() {
        val r = QaThermal.RunSummary()
        r.add(sample(bat = 40.0, soc = listOf("a" to 50.0), status = 0))
        r.add(sample(bat = 42.0, soc = listOf("a" to 60.0, "b" to 70.0), status = 2))
        r.add(sample(bat = null, soc = emptyList(), status = 1))
        val f = r.fields().toMap()
        assertEquals(3, f["seconds"]); assertEquals("42.0/41.0", f["bat_c_max_mean"]); assertEquals("70.0/60.0", f["soc_c_max_mean"]); assertEquals(2, f["thermal_status_max"])
        assertTrue(QaThermal.RunSummary().fields().none { it.first.endsWith("max_mean") })
    }

    @Test fun thermalLogSettingIsABoolDefaultOnAndSettable() {
        var on = true
        val l = listOf(QaThermal.setting({ on }, { on = it }))
        val d = QaSettings.get(l).getJSONArray("settings").getJSONObject(0)
        assertEquals("thermal_log", d.getString("key")); assertEquals("bool", d.getString("type")); assertEquals(true, d.get("default")); assertEquals(true, d.get("value"))
        val r = QaSettings.set(l, "thermal_log", "false")
        assertEquals(true, r.get("old")); assertEquals(false, r.get("new")); assertFalse(on)
    }
}
