#!/usr/bin/env python3
"""Parse and summarise the once-per-second thermal stream that qa/thermal-log.sh reads from a phone (docs/QA_API.md, "Thermal log").

  thermal_log.py convert --out thermal.csv          # stdin: the phone's text stream; writes the CSV, flushed after every sample
  thermal_log.py summary thermal.csv [--out f.txt]   # max / mean per sensor, time above 45 C, throttle events

Reads system sensors only; knows nothing about the app. Never raises on a sensor it cannot read: it is an empty cell.
"""
import argparse, csv, re, sys
from datetime import datetime, timezone

# android.hardware.thermal TemperatureType. 6, 7 and 8 are battery current limiting readings (volts, amps, percent), not temperatures.
TYPE_NAMES = {-1: "UNKNOWN", 0: "CPU", 1: "GPU", 2: "BATTERY", 3: "SKIN", 4: "USB_PORT", 5: "POWER_AMPLIFIER", 6: "BCL_VOLTAGE", 7: "BCL_CURRENT",
              8: "BCL_PERCENTAGE", 9: "NPU", 10: "TPU", 11: "DISPLAY", 12: "MODEM", 13: "SOC", 14: "WIFI", 15: "CAMERA", 16: "FLASHLIGHT",
              17: "SPEAKER", 18: "AMBIENT", 19: "POGO"}
NOT_TEMPERATURE_TYPES = {6, 7, 8}
STATUS_NAMES = {0: "NONE", 1: "LIGHT", 2: "MODERATE", 3: "SEVERE", 4: "CRITICAL", 5: "EMERGENCY", 6: "SHUTDOWN"}
# Zones that are not temperatures although they sit in /sys/class/thermal: battery current limiting levels, voltage, current, SoC degradation.
NOT_TEMP_ZONE = re.compile(r"(^|[-_])(bcl|ibat|vbat|socd)([-_]|$)|-lvl\d$", re.I)
# The SoC group: application processor, its clusters, GPU, NPU / DSP (nsp), always-on, modem, DDR, camera / video blocks, PMICs, skin and quiet sensors.
SOC_NAME = re.compile(r"^(cpu|cpuss|gpu|gpuss|npu|nsp|aoss|mdm|mdmss|ddr|video|camera|pm\d|pm[a-z]|soc|ap_|ap$|xo|quiet|skin)", re.I)
ABOVE_C = 45.0
SKIN_BATTERY = re.compile(r"skin|battery", re.I)
TEMP_LINE = re.compile(r"Temperature\{mValue=(-?[\d.]+(?:E-?\d+)?), mType=(-?\d+), mName=([^,}]*), mStatus=(-?\d+)\}")


def group_of(name, ttype=None):
    """soc, battery (battery and charger) or other. Not-a-temperature readings are 'power'."""
    if ttype in NOT_TEMPERATURE_TYPES or NOT_TEMP_ZONE.search(name):
        return "power"
    if re.search(r"battery|charger", name, re.I) or ttype == 2:
        return "battery"
    if SOC_NAME.search(name) or ttype in (0, 1, 3, 9, 12, 13):
        return "soc"
    return "other"


def parse_hal(dump):
    """[(name, type, value, status)] from the 'Current temperatures from HAL' section of `dumpsys thermalservice`; the cached list is the fallback."""
    for head in ("Current temperatures from HAL:", "Cached temperatures:"):
        i = dump.find(head)
        if i < 0:
            continue
        out = []
        for line in dump[i + len(head):].splitlines()[1:]:
            m = TEMP_LINE.search(line)
            if m:
                out.append((m.group(3), int(m.group(2)), float(m.group(1)), int(m.group(4))))
            elif out and not line.startswith("\t"):
                break
        if out:
            return out
    return []


def parse_thermal_status(dump):
    m = re.search(r"Thermal Status:\s*(-?\d+)", dump)
    return int(m.group(1)) if m else None


def parse_battery(dump):
    """level, temperature (C), voltage (mV), status, plugged (ac|usb|wireless|none), charge counter (uAh), max charging current / voltage."""
    def num(key):
        m = re.search(r"^\s*" + re.escape(key) + r":\s*(-?\d+)\s*$", dump, re.M)
        return int(m.group(1)) if m else None
    temp = num("temperature")
    plugged = [n for n, k in (("ac", "AC powered"), ("usb", "USB powered"), ("wireless", "Wireless powered")) if re.search(k + r":\s*true", dump)]
    return {"level": num("level"), "temp_c": None if temp is None else temp / 10.0, "voltage_mv": num("voltage"), "status": num("status"),
            "plugged": "+".join(plugged) or "none", "charge_counter_uah": num("Charge counter"), "max_charge_ua": num("Max charging current")}


def zone_celsius(raw, name):
    """A sysfs zone value as degrees C, or None when it is unreadable, not a temperature or not plausible (-273 C placeholders)."""
    if raw in (None, "", "x") or NOT_TEMP_ZONE.search(name):
        return None
    try:
        v = float(raw)
    except ValueError:
        return None
    v = v / 1000.0 if abs(v) >= 1000 else v
    return v if -60 < v < 200 else None


def fmt(v):
    return "" if v is None else (f"{v:.3f}".rstrip("0").rstrip(".") if isinstance(v, float) else str(v))


class Stream:
    """Splits the phone's text stream into header facts and samples."""

    def __init__(self):
        self.header, self.zones, self.supplies, self.freq_names = {}, [], [], []
        self.samples, self._cur, self._sec = [], None, None

    def feed(self, line):
        line = line.rstrip("\r\n")
        if line.startswith("@@H "):
            k, _, v = line[4:].partition(" ")
            self.header[k] = v
        elif line.startswith("@@ZN "):
            _, idx, ok, ty = (line.split(" ", 3) + [""])[:4]
            self.zones.append((idx, ty, ok == "1"))
        elif line.startswith("@@PN "):
            p = line[5:].split(" ", 2)
            if len(p) >= 2:
                self.supplies.append((p[0], p[1], len(p) > 2 and p[2] == "1"))
        elif line.startswith("@@FN "):
            self.freq_names.append(line[5:].strip())
        elif line.startswith("@@S "):
            self._flush()
            try:
                self._cur = {"ms": int(line[4:].strip()), "B": [], "T": [], "Z": "", "P": "", "L": "", "F": ""}
            except ValueError:
                self._cur = None
            self._sec = None
        elif self._cur is not None and line.startswith("@@") and line[2:3] in "BTZPLF" and (len(line) == 3 or line[3] == " "):
            self._sec = line[2]
            if self._sec in "ZPLF":
                self._cur[self._sec] = line[4:]
                self._sec = None
        elif self._cur is not None and self._sec in ("B", "T"):
            self._cur[self._sec].append(line)

    def _flush(self):
        if self._cur is not None:
            c = self._cur
            c["B"], c["T"] = "\n".join(c["B"]), "\n".join(c["T"])
            self.samples.append(c)
        self._cur = None

    def take(self, final=False):
        """Complete samples seen so far (all but the one still being filled, unless final)."""
        if final:
            self._flush()
        out, self.samples = self.samples, []
        return out


def sample_values(c, st):
    """{column: value} for one raw sample. Columns are 'group:source:name' so the SoC group reads as its own block."""
    row = {}
    for name, ttype, value, status in parse_hal(c["T"]):
        g = group_of(name, ttype)
        row[f"{g}:hal:{name}"] = value
        row[f"{g}:hal_status:{name}"] = status
    zvals = c["Z"].split()
    for (idx, ty, _), raw in zip(st.zones, zvals):
        v = zone_celsius(raw, ty)
        g = group_of(ty)
        row[f"{g}:zone:{ty}" if g != "power" else f"power:zone:{ty}"] = v if g != "power" else (None if raw in ("x", "") else raw)
    b = parse_battery(c["B"])
    for k, v in b.items():
        row[f"battery:dumpsys:{k}"] = v
    for (p, f, _), raw in zip(st.supplies, c["P"].split()):
        row[f"battery:supply:{p}/{f}"] = None if raw == "x" else raw
    la = c["L"].split()
    row["load:loadavg1"], row["load:loadavg5"], row["load:loadavg15"] = (la + [None] * 3)[:3]
    for n, raw in zip(st.freq_names, c["F"].split()):
        row[f"load:freq_khz:{n}"] = None if raw == "x" else raw
    ts = parse_thermal_status(c["T"])
    row["status:thermal_status"] = ts
    soc = [v for k, v in row.items() if k.startswith("soc:hal:") or k.startswith("soc:zone:")]
    soc = [v for v in soc if isinstance(v, float)]
    row["soc:soc_max_c"] = max(soc) if soc else None
    return row


def header_lines(st, first_row):
    h = st.header
    sensors = [k for k in first_row if ":hal:" in k or ":zone:" in k]
    unreadable = [f"zone{idx}:{ty}" for idx, ty, ok in st.zones if not ok] + [f"{p}/{f}" for p, f, ok in st.supplies if not ok]
    socs = [k.split(":", 2)[2] for k in sensors if k.startswith("soc:")]
    ts = first_row.get("status:thermal_status")
    flagged = [f"{k.split(':', 2)[2]}={STATUS_NAMES.get(v, v)}" for k, v in first_row.items() if k.startswith("soc:hal_status:") and v]
    lines = [
        f"phone model: {h.get('model', '?')}",
        f"soc: {h.get('soc_manufacturer', '')} {h.get('soc_model', '')} (platform {h.get('platform', '?')}, hardware {h.get('hardware', '?')})",
        f"android: {h.get('release', '?')} (sdk {h.get('sdk', '?')}), build {h.get('build', '?')}",
        f"started: {datetime.now(timezone.utc).isoformat(timespec='seconds')}",
        f"thermal status at start: {ts} ({STATUS_NAMES.get(ts, '?')}); soc sensors with a throttling status at start: {', '.join(flagged) or 'none'}",
        f"columns are group:source:name; groups: soc (application processor, GPU, NPU/DSP nsp, aoss, modem, ddr, pmic, skin, quiet), battery, other, power (volts, amps, % readings, not temperatures), load, status. Temperatures in C, cells empty when unreadable",
        f"soc group ({len(socs)}): {', '.join(socs)}",
        f"sensors found ({len(sensors)}): {', '.join(k.split(':', 1)[1] for k in sensors)}",
        f"unreadable: {', '.join(unreadable) or 'none'}",
        "comment lines start with '#'; read with pandas.read_csv(path, comment='#')",
    ]
    return ["# " + l for l in lines]


def convert(inp, out_path):
    st, cols, f, w = Stream(), None, None, None
    def emit(samples):
        nonlocal cols, f, w
        for c in samples:
            row = sample_values(c, st)
            if cols is None:
                cols = list(row)
                f = open(out_path, "w", newline="", encoding="utf-8")
                for l in header_lines(st, row):
                    f.write(l + "\n")
                w = csv.writer(f)
                w.writerow(["epoch_ms", "time_utc"] + cols)
            t = datetime.fromtimestamp(c["ms"] / 1000, timezone.utc).strftime("%H:%M:%S.%f")[:-3]
            w.writerow([c["ms"], t] + [fmt(row.get(k)) for k in cols])
        if f:
            f.flush()
    try:
        for line in inp:
            st.feed(line)
            if line.startswith("@@S "):
                emit(st.take())
    except Exception as e:  # never fail the run: keep what was written
        print(f"thermal convert stopped: {type(e).__name__}", file=sys.stderr)
    emit(st.take(final=True))
    if f:
        f.close()
    return 0


def read_csv(path):
    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.reader(l for l in fh if not l.startswith("#")))
    if not rows:
        return [], []
    return rows[0], rows[1:]


def summarize(header, rows, above_c=ABOVE_C):
    """Text summary: per-sensor max/mean (soc group first, with an overall soc max), seconds above 45 C for skin and battery, throttle events."""
    if not rows:
        return "thermal summary: no samples\n"
    ix = {c: i for i, c in enumerate(header)}
    def col(name):
        i = ix[name]
        return [float(r[i]) if i < len(r) and r[i] not in ("",) else None for r in rows]
    ms = [int(r[ix["epoch_ms"]]) for r in rows]
    dur = (ms[-1] - ms[0]) / 1000.0
    dts = [min((b - a) / 1000.0, 5.0) for a, b in zip(ms, ms[1:])]
    stats = {}
    for c in header:
        if re.match(r"(soc|battery|other):(hal|zone):", c) or c in ("battery:dumpsys:temp_c", "soc:soc_max_c"):
            vals = [v for v in col(c) if v is not None]
            if vals:
                stats[c] = (max(vals), sum(vals) / len(vals), vals)
    out = [f"thermal summary: {len(rows)} samples over {dur:.0f} s (about {dur / max(len(rows) - 1, 1):.2f} s apart)", ""]
    for g, title in (("soc", "SoC sensors"), ("battery", "battery and charger"), ("other", "other sensors")):
        out.append(f"{title} (max / mean C):")
        part = sorted(((c, s) for c, s in stats.items() if c.startswith(g + ":") and c != "soc:soc_max_c"), key=lambda x: -x[1][0])
        for c, (mx, mean, _) in part:
            out.append(f"  {c.split(':', 1)[1]:36s} {mx:7.1f} {mean:7.1f}")
        if g == "soc" and "soc:soc_max_c" in stats:
            mx, mean, _ = stats["soc:soc_max_c"]
            out.append(f"  {'OVERALL SoC max (hottest soc sensor each second)':36s} {mx:7.1f} {mean:7.1f}")
        if not part:
            out.append("  none readable")
        out.append("")
    out.append(f"time above {above_c:.0f} C (seconds, skin and battery sensors):")
    any_ = False
    for c, (_, _, vals) in stats.items():
        if SKIN_BATTERY.search(c) and not c.startswith("power"):
            allv = col(c)
            secs = sum(dt for v, dt in zip(allv, dts) if v is not None and v > above_c)
            out.append(f"  {c.split(':', 1)[1]:36s} {secs:7.0f} s")
            any_ = True
    if not any_:
        out.append("  no skin or battery sensor")
    out.append("")
    ts = [int(v) if v is not None else None for v in col("status:thermal_status")] if "status:thermal_status" in ix else []
    worst = max([t for t in ts if t is not None], default=0)
    changes = sum(1 for a, b in zip(ts, ts[1:]) if a is not None and b is not None and a != b)
    out.append(f"thermal status: max {worst} ({STATUS_NAMES.get(worst, '?')}), {changes} change(s)")
    events = []
    for c in header:
        m = re.match(r"(soc|battery|other):hal_status:(.*)", c)
        if m:
            v = [x if x is not None else 0 for x in col(c)]
            n = sum(1 for a, b in zip([0] + v, v) if b > 0 and a == 0)
            if n or any(x > 0 for x in v):
                events.append((m.group(2), n, int(max(v)), sum(1 for x in v if x > 0)))
    out.append("throttle events (a sensor's HAL throttling status going above NONE):")
    for name, n, worst_s, secs in sorted(events, key=lambda e: -e[3]):
        out.append(f"  {name:20s} {n} event(s), {secs} s throttled, worst {STATUS_NAMES.get(worst_s, worst_s)}")
    if not events:
        out.append("  none")
    return "\n".join(out) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("convert"); c.add_argument("--out", required=True)
    s = sub.add_parser("summary"); s.add_argument("csv"); s.add_argument("--out"); s.add_argument("--above", type=float, default=ABOVE_C)
    a = ap.parse_args(argv)
    if a.cmd == "convert":
        return convert(sys.stdin, a.out)
    text = summarize(*read_csv(a.csv), above_c=a.above)
    if a.out:
        open(a.out, "w", encoding="utf-8").write(text)
    print(text, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
