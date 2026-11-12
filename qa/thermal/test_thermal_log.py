import csv, io, os, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(__file__))
import thermal_log as tl

FIX = os.path.join(os.path.dirname(__file__), "fixtures")
def fx(n): return open(os.path.join(FIX, n), encoding="utf-8").read()


def stream(ms, status="0", skin="40.0", skin_status=0, zone="48000"):
    return (f"@@S {ms}\n@@B\n" + fx("battery.txt") + "@@T\nThermal Status: " + status + "\nCurrent temperatures from HAL:\n"
            f"\tTemperature{{mValue=51.7, mType=0, mName=CPU0, mStatus=0}}\n\tTemperature{{mValue={skin}, mType=3, mName=skin, mStatus={skin_status}}}\n"
            "\tTemperature{mValue=90.0, mType=8, mName=socd, mStatus=3}\nCurrent cooling devices from HAL:\n"
            f"@@Z {zone} 3378 x\n@@P \n@@L 1.00 2.00 3.00 1/1 1\n@@F 1152000 x\n")


HEAD = "@@H model Test\n@@H soc_model SM8750\n@@ZN 0 1 cpu-0-0-0\n@@ZN 1 1 vbat\n@@ZN 2 0 wireless\n@@FN cpu0\n@@FN cpu1\n"


class Parse(unittest.TestCase):
    def test_hal_section_of_the_real_dump(self):
        t = tl.parse_hal(fx("thermalservice.txt"))
        names = {n: (ty, v, s) for n, ty, v, s in t}
        self.assertEqual(len(t), 28)
        self.assertEqual(names["CPU7"][0], 0)
        self.assertEqual(names["nsp3"][0], 9)
        self.assertEqual(names["socd"][2], 3)
        self.assertAlmostEqual(names["skin"][1], 41.638)

    def test_current_section_wins_over_cached(self):
        t = {n: v for n, _, v, _ in tl.parse_hal(fx("thermalservice.txt"))}
        self.assertAlmostEqual(t["battery"], 40.6)       # the cached list says 30.7
        self.assertEqual(tl.parse_thermal_status(fx("thermalservice.txt")), 0)

    def test_garbage_gives_nothing(self):
        self.assertEqual(tl.parse_hal("nope"), [])
        self.assertIsNone(tl.parse_thermal_status("nope"))

    def test_battery(self):
        b = tl.parse_battery(fx("battery.txt"))
        self.assertEqual((b["level"], b["voltage_mv"], b["status"], b["plugged"]), (6, 3406, 2, "usb"))
        self.assertAlmostEqual(b["temp_c"], 40.9)
        self.assertEqual(b["charge_counter_uah"], 346000)
        self.assertIsNone(tl.parse_battery("")["level"])

    def test_zone_values(self):
        self.assertEqual(tl.zone_celsius("51700", "cpu-0-0-0"), 51.7)
        self.assertIsNone(tl.zone_celsius("x", "cpu"))
        self.assertIsNone(tl.zone_celsius("-273000", "sdr0_pa"))
        self.assertIsNone(tl.zone_celsius("3378", "vbat"))
        self.assertIsNone(tl.zone_celsius("94", "socd"))
        self.assertIsNone(tl.zone_celsius("0", "pm8550-bcl-lvl0"))

    def test_groups(self):
        g = tl.group_of
        for n in ("cpu-0-0-0", "CPU3", "gpuss-2", "GPU1", "nsp0", "nsphvx-1", "aoss-0", "mdmss-1", "pm8550_tz", "pmih010x_tz", "xo-therm", "quiet_therm", "skin", "ddr", "cpu_therm"):
            self.assertEqual(g(n), "soc", n)
        self.assertEqual(g("battery"), "battery")
        self.assertEqual(g("charger_therm0"), "battery")
        self.assertEqual(g("wifi_therm"), "other")
        self.assertEqual(g("conn_therm"), "other")
        self.assertEqual(g("vbat", 6), "power")
        self.assertEqual(g("socd", 8), "power")
        self.assertEqual(g("pmih010x-ibat-lvl0"), "power")


class Convert(unittest.TestCase):
    def run_convert(self, text):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "t.csv")
            tl.convert(io.StringIO(text), p)
            return open(p, encoding="utf-8").read()

    def rows(self, text):
        lines = [l for l in text.splitlines() if not l.startswith("#")]
        return list(csv.DictReader(lines))

    def test_csv_line_has_soc_group_and_blank_for_unreadable(self):
        out = self.run_convert(HEAD + stream(1000) + stream(2000))
        r = self.rows(out)
        self.assertEqual(len(r), 2)
        self.assertEqual(r[0]["epoch_ms"], "1000")
        self.assertEqual(r[0]["soc:hal:CPU0"], "51.7")
        self.assertEqual(r[0]["soc:zone:cpu-0-0-0"], "48")
        self.assertEqual(r[0]["soc:soc_max_c"], "51.7")
        self.assertEqual(r[0]["battery:dumpsys:level"], "6")
        self.assertEqual(r[0]["load:freq_khz:cpu1"], "")      # unreadable: empty cell
        self.assertEqual(r[0]["load:loadavg1"], "1.00")
        self.assertEqual(r[0]["power:hal:socd"], "90")        # percent, kept out of the temperature groups
        self.assertIn("# soc: ", out)
        self.assertIn("unreadable: zone2:wireless", out)

    def test_header_names_phone_soc_and_throttle_state(self):
        out = self.run_convert(HEAD + stream(1000, status="2", skin="50", skin_status=2))
        self.assertIn("# phone model: Test", out)
        self.assertIn("SM8750", out)
        self.assertIn("thermal status at start: 2 (MODERATE)", out)
        self.assertIn("skin=MODERATE", out)

    def test_truncated_stream_keeps_what_was_read(self):
        out = self.run_convert(HEAD + stream(1000) + "@@S 2000\n@@B\nlevel: 5\n")
        self.assertEqual(len(self.rows(out)), 2)

    def test_empty_stream_writes_nothing_and_does_not_raise(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "t.csv")
            self.assertEqual(tl.convert(io.StringIO(""), p), 0)
            self.assertFalse(os.path.exists(p))


class Summary(unittest.TestCase):
    def csv_for(self, specs):
        text = HEAD + "".join(stream(1000 * (i + 1), **kw) for i, kw in enumerate(specs))
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "t.csv")
            tl.convert(io.StringIO(text), p)
            return tl.read_csv(p)

    def test_max_mean_time_above_and_throttle(self):
        h, rows = self.csv_for([dict(skin="40"), dict(skin="46", status="1", skin_status=1), dict(skin="47", status="1", skin_status=1), dict(skin="44")])
        s = tl.summarize(h, rows)
        self.assertIn("4 samples over 3 s", s)
        skin = [l for l in s.splitlines() if l.strip().startswith("hal:skin") and "max" not in l]
        self.assertTrue(any("47.0" in l and "44.2" in l for l in skin), s)
        self.assertRegex(s, r"hal:skin\s+2 s")                 # two samples above 45 C
        self.assertIn("OVERALL SoC max", s)
        self.assertIn("thermal status: max 1 (LIGHT), 2 change(s)", s)
        self.assertIn("skin", s.split("throttle events")[1])
        self.assertIn("1 event(s), 2 s throttled, worst LIGHT", s)

    def test_no_samples(self):
        self.assertIn("no samples", tl.summarize([], []))

    def test_a_flat_run_has_no_events(self):
        h, rows = self.csv_for([dict(), dict()])
        s = tl.summarize(h, rows)
        self.assertTrue(s.rstrip().endswith("none"))


if __name__ == "__main__":
    unittest.main()
