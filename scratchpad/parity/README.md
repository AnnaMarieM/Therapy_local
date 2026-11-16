# Screen parity: Android vs iOS

Lane A2b, card [#133](https://github.com/AnnaMarieM/Therapy_local/issues/133); feeds the R1
parity checklist, card [#127](https://github.com/AnnaMarieM/Therapy_local/issues/127).
Martin's directive (2026-11-16): both native apps exist and **screenshots must look the same**.
This directory holds the two capture halves; the compare step is a **DeepSeek vision agent pass**
over both PNG sets, one screen at a time, filed as a per-screen deviation report on #133.

## What is captured

Every state the DEBUG iOS app can present deterministically (17), aligned with the Android
screen tour (`app/src/androidTest/.../screens/ScreenList.kt`, AD-15) where the apps share a
screen. The iOS list is the raw values of `ScreenshotHarness.Screen` and the CI job parses it
from there, so this table and the captures cannot drift. Screens only one app has are still
captured and reported as differences — that is the point of the pass.

| screen | what it shows | iOS | Android |
|---|---|---|---|
| `setup` | first run: create the vault | ✓ gate, empty vault | `gate-create-password` |
| `setup-filled` | both passphrase fields typed | ✓ gate + fields `12345678` | `gate-create-password-filled` |
| `unlock` | returning user: enter the passphrase | ✓ gate, locked | `gate-unlock` |
| `erase-vault` | "Erase everything?" dialog over the gate | ✓ (iOS-only: the dialog over the gate) | `gate-reset-password` is a full page instead |
| `home-empty` | home with no clients | ✓ | `home-empty` |
| `home` | home with clients | ✓ tour clients | `home` |
| `add-client` | the "New client" sheet | ✓ | `add-client` |
| `add-client-filled` | the sheet with a name typed | ✓ Dana / Lewis, like the Android tour | `add-client-filled` |
| `notice` | a notice banner over the page | ✓ "Ana added" over home | `notice` |
| `profile` | one client: facts, sessions | ✓ Ana | `profile` (+ `profile-sessions`) |
| `profile-search` | a client's memory searched | ✓ query "running" | `profile-search` |
| `profile-delete` | "Delete client?" dialog | ✓ (iOS-only state) | delete-confirm captured in `dialog-*` set |
| `session` | the session page, ready to record | ✓ Ana, idle | (no idle twin; Android tours the recording states) |
| `session-recording` | recording: clock 22:05, level 0.45, one cue | ✓ | `session-recording` |
| `session-discard` | "Discard this recording?" dialog | ✓ (iOS-only state) | no discard dialog on the tour |
| `summary` | the session review sheet | ✓ Ana's 2026-01-01 session | (no summary twin on the Android tour) |
| `settings` | settings sheet | ✓ | `settings` (+ `settings-bottom`, `settings-technical`, …) |

### Captured on Android only (documented, no iOS twin yet)

- **Gate flow pages:** `gate-language`, `gate-welcome-1..4`, `gate-permissions`,
  `gate-speech-language` — the iOS gate is one screen (`setup`) where Android has a paged
  first-run flow.
- **State screens:** `gate-reset-password` (iOS shows the erase dialog over the gate instead),
  `home-model-download` (no model-download state on iOS home yet), `home-two-pane` (wide-window
  two-pane layout — the iOS home is a card list at all widths),
  `home-recording` (the recording card on home), `session-recording-quiet` (no cue),
  `session-paused` (the iOS paused phase exists but is not captured),
  `profile-processing` (the processing status line), `profile-fact`, `profile-transcript`
  (iOS shows facts and sessions on one page; Android tours them as separate scrolled screens),
  `settings-bottom`, `settings-technical`, `settings-technical-cues`, `settings-change-password`.
- **Dialogs:** `dialog-mobile-data`, `dialog-sign-out`, and the other `dialog-*` entries —
  the iOS equivalents surface as in-screen dialogs (`erase-vault`, `profile-delete`,
  `session-discard`), not as separate windows.

### Known content differences inside shared screens

- **Fact categories:** Android facts carry Health / Family / Work; the iOS taxonomy has nine
  cases, and the fixtures map Health → coping, Family → relationship, Work → event. The tour
  facts are the same four with the same headings, contents and evidence on both sides.
- **Fact mentions:** the Android tour mentions the "Runs again" fact in Ana's second session
  (`addMention`); the iOS `VaultDocument` has no mention slot yet, so the iOS fixtures carry
  only the base fact.
- **Home layout:** Android home is a client reel/carousel at every portrait size; the iOS home
  is a card list (Martin's call pending on which IA wins — see the compare on #133).
- **Date labels:** iOS renders `2026-01-15` (ISO), Android's tour composes `Jan 15` status
  strings. Fixture instants are identical (Android `DAY_1` = 2026-01-01 00:00 UTC).

## iOS half (CI, no device needed)

The `screenshots` job in [`.github/workflows/ios.yml`](../../.github/workflows/ios.yml) builds the
simulator **Debug** app, boots one iPhone simulator, and for each screen (parsed out of
`ScreenshotHarness.Screen`) runs

```
xcrun simctl launch <udid> com.therapylocal.app -MVShot <screen>
sleep 5
xcrun simctl io <udid> screenshot <screen>.png
```

`-MVShot` is read by a DEBUG-only harness
([`ScreenshotHarness.swift`](../../ios/Sources/MindVault/App/ScreenshotHarness.swift)); an unknown
screen name fails the launch loudly with the valid names, so a job typo can never be silently
screenshotted as the wrong screen. A launch without the argument is untouched, and the harness is
not compiled into Release.

- **Artifact:** the `ios-screenshots` artifact of the `ios-ci` run (17 PNGs, 14-day retention).
  Download with `gh run download -R AnnaMarieM/Therapy_local -n ios-screenshots -D /tmp/opencode/ios-shots`.
- **A fresh install can show** `setup`, `setup-filled`, `unlock` and `erase-vault` with no seeded
  data at all (the job erases the simulator first). The deeper screens need data; the harness
  serves them from `MindVaultCore.Fixtures` — the tour data above (four clients, five sessions,
  four facts), the same fixtures the Linux unit tests use — with an in-memory repository. That
  is a DEBUG seed arg, not invented data, and no real vault is read.
- The job also fixes the status bar (9:41, full battery, Wi-Fi) so shots differ by the app, not
  the clock, and fails if a PNG is smaller than 12 kB (a blank or black capture, not a screenshot).

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
