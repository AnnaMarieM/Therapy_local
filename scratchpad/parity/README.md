# Screen parity: Android vs iOS

Lane A2b, card [#133](https://github.com/AnnaMarieM/Therapy_local/issues/133); feeds the R1
parity checklist, card [#127](https://github.com/AnnaMarieM/Therapy_local/issues/127).
Martin's directive (2026-11-16): both native apps exist and **screenshots must look the same**.
This directory holds the two capture halves; the compare step is a **DeepSeek vision agent pass**
over both PNG sets, one screen at a time, filed as a per-screen deviation report on #133.

## What is captured

The same nine states on both apps, named identically, so the sets line up one to one:

| screen | what it shows | iOS data | Android data |
|---|---|---|---|
| `setup` | first run: create the vault | empty vault | QA vault with no password yet (or the gate's first page) |
| `unlock` | returning user: enter the passphrase | locked, empty vault | QA vault, password `12345678` |
| `home-empty` | home with no clients | unlocked, no clients | fresh QA vault |
| `home` | home with clients | fixture clients (George, Maya) | QA test clients |
| `add-client` | the "New client" sheet | fixtures | QA test clients |
| `profile` | one client: note, memory, sessions | fixture client George | first client in the vault |
| `session` | the session page (idle) | fixture client George | first client, record screen |
| `summary` | session review sheet | fixture session | Android's session review, if reachable |
| `settings` | settings sheet | fixtures | QA build |

The names follow the Android screen tour (`app/src/androidTest/.../screens/ScreenList.kt`, AD-15)
where the two apps have the same screen. A screen that one app does not have yet is still
captured and reported as a difference — that is the point of the pass.

## iOS half (CI, no device needed)

The `screenshots` job in [`.github/workflows/ios.yml`](../../.github/workflows/ios.yml) builds the
simulator **Debug** app, boots one iPhone simulator, and for each screen runs

```
xcrun simctl launch <udid> com.therapylocal.app -MVShot <screen>
sleep 5
xcrun simctl io <udid> screenshot <screen>.png
```

`-MVShot` is read by a DEBUG-only harness
([`ScreenshotHarness.swift`](../../ios/Sources/MindVault/App/ScreenshotHarness.swift)). A launch
without the argument is untouched, and the harness is not compiled into Release.

- **Artifact:** the `ios-screenshots` artifact of the `ios-ci` run (~9 PNGs, 14-day retention).
  Download with `gh run download -R AnnaMarieM/Therapy_local -n ios-screenshots -D /tmp/opencode/ios-shots`.
- **A fresh install can show** `setup` and `unlock` with no seeded data at all (the job erases the
  simulator first). The deeper screens (`home`, `profile`, `session`, `summary`, `settings`) need
  data; the harness serves them from the app's existing `MindVaultCore.Fixtures` (two clients, two
  sessions, three facts) with an in-memory repository. That is a DEBUG seed arg, not invented data:
  the same fixtures the previews and the Linux unit tests use, and no real vault is read.
- The job also fixes the status bar (9:41, full battery, Wi-Fi) so shots differ by the app, not the
  clock, and fails if a PNG is smaller than 12 kB (a blank or black capture, not a screenshot).

## Android half (needs the QA phone)

```
scratchpad/parity/android-shots.sh                 # -> /tmp/opencode/android-shots/
OUT=dist/parity/android scratchpad/parity/android-shots.sh
```

The script checks `adb devices` first and exits with a clear message when nothing is attached; it
never touches the real app (`com.therapylocal.app`), only the QA build
(`com.therapylocal.app.qa`, password `12345678`). It wakes the phone, turns animations off, launches
the QA app, walks the gate (Continue ×N, create-or-unlock) and taps through home, a client page, the
record screen, "New client" and Settings, taking `adb exec-out screencap -p` at each step.

Android's Compose navigation has no driving seam (only the QA API, and that cannot navigate —
[`docs/QA_API.md`](../../docs/QA_API.md)), so labels are found in the `uiautomator` tree. If in-app
copy changes, update the label strings at the bottom of the script.

**Status 2026-11-16:** blocked — no device on adb and no local emulator (no KVM). Plug the POCO in
(or another QA phone) and run the script; keep the PNGs in `/tmp/opencode/android-shots/`. Nothing
in the Android half is committed as images.

## The compare step

Both sets are PNGs with the same names. A **DeepSeek vision agent** opens them side by side,
`<screen>.png` (Android) against `<screen>.png` (iOS), and writes one row per screen: same
composition and hierarchy, different spacing/type/colour, or missing on one side. It reports
deviations on card #133 with the screen name and what differs, and feeds the R1 checklist. Layout
density differs (Android 1080×2400 at ~420 dpi vs iPhone 1179×2556); compare structure and
proportions, not pixel counts.
