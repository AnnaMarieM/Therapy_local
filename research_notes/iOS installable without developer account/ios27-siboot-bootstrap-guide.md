# iOS 27 no-computer bootstrap guide (SideStore via siboot + SideInstaller)

Written 2026-11-16 for the app owner: one iPhone, iOS 26.x, Czech Republic (EU), fresh burner Apple ID, **zero computer access, ever**. Goal: SideStore installed and signed in, our source added, Therapy app installed — all on the phone.

Builds on [no-mac-bootstrap.md](no-mac-bootstrap.md) (which settled the tool landscape and the Oct 2026 auth waves). This file is the **operational guide**: every step verified against primary sources fetched **2026-11-16** unless labeled otherwise.

**Labels:** **[V]** verified today (primary source, fetched 2026-11-16, URL given) · **[D]** documented earlier in no-mac-bootstrap.md (not re-verified) · **[C]** community-reported (issue/Reddit/video, no primary doc) · **[I]** inference · **[U]** unverified — confirm on device.

---

## 0. Before you start (checklist)

| ✓ | Item | Why / source |
|---|------|--------------|
| ☐ | **iPhone on iOS 27.2.1** (see §1) | siboot's no-computer path is gated on iOS 27+ [V: SideInstaller README — Compatibility](https://github.com/FrizzleM/SideInstaller) |
| ☐ | **Burner Apple ID created and 2FA-enabled**, with a reachable code channel (your real SIM number in the burner's trusted numbers is the practical choice) | sign-in needs email + password + 6-digit code [V: siboot minis/README](https://github.com/FrizzleM/siboot/blob/main/minis/README.md); burner ID recommended because shared anisette servers lock accounts [V: SideStore FAQ](https://docs.sidestore.io/docs/faq) |
| ☐ | **~45–60 minutes**, uninterrupted | 3 App Store apps + 2 sign-ins + several restarts; iOS suspends backgrounded apps mid-run, so don't plan to multitask [V: siboot minis/README — "Keep Minis open until siboot says it's done"] |
| ☐ | **Phone charged ≥ 50 %** | two forced restarts happen during the flow (Developer Mode on; "Allow & Restart" trust) [V: siboot minis/README; SideStore install docs](https://docs.sidestore.io/docs/installation/install) |
| ☐ | **Stable Wi-Fi** — needed for the whole bootstrap and for every later refresh (cellular-only refresh is experimental) [V: SideInstaller README — Requirements; discussion #1482](https://github.com/orgs/SideStore/discussions/1482) | |
| ☐ | **iCloud backup ON** (Settings → [name] → iCloud → iCloud Backup → Back Up Now) | belt-and-braces before an OS update; no computer needed |
| ☐ | **No other VPN active** during the bootstrap | iOS runs one VPN at a time; LocalDevVPN must be the active one [V: SideInstaller CHANGELOG 1.6.0 — "iOS runs one VPN at a time"; siboot issue #3](https://github.com/FrizzleM/siboot/issues/3) |
| ☐ | **Two Apple IDs, kept apart** — the **App Store** (downloading Open Minis + LocalDevVPN) signs in with your **real** Apple ID; only **siboot / SideInstaller / SideStore** ever take the **burner** | typing the burner's password into the App Store, or the real one into SideInstaller, is the worst-case credential mix-up [I — separation demanded by the vault rule (§4) + burner containment] |

**Trust boundary, read first:** SideInstaller is open source but its builds are signed by one pseudonymous developer. The only official sources are **https://sideinstaller.net/** and **https://github.com/FrizzleM/SideInstaller**. There is a fake site **sideinstaller.com** serving likely malware; forks "can insert a credential stealer". The app itself asks, before your first Apple ID entry, whether you downloaded it from sideinstaller.net — answer honestly, and never type the burner's password into a copy from anywhere else. [V: SideInstaller README caution block + CHANGELOG 1.6.0; help.html Q&A]

---

## 1. Update to iOS 27 first

1. **Current version: iOS 27.2.1 (build 24B91034), released 2026-11-09.** [V: Apple Developer release news](https://developer.apple.com/news/releases?id=10262026b) and [About iOS 27 Updates](https://support.apple.com/en-us/149076), both fetched 2026-11-16. iOS 27.3 is in beta only (dev beta 1 Nov 3, public beta 1 Nov 5, 2026) [C: MacRumors roundup] — **stay on 27.2.1, not a beta**.
2. **Known regressions affecting SideStore/siboot on iOS 27.x, as of 2026-11-16:**
   - None blocking found in the primary repos. **Honesty about the test claim:** siboot was tested end-to-end on an iPhone 16 on iOS 27 on **2026-11-10 — but that run used a test build from the `minis-pairing` branch and predates Open Minis 1.16's App Store release (2026-11-14)**. There is **no public end-to-end report that the shipped siboot 0.18.1 completes the Minis self-pairing path on a stock iPhone**: treat the **first pairing run as the most likely failure point** (fallback at step 17 — whose own hand-off "hasn't completed on an iPhone yet" either). [V: siboot minis/README — "What's been tested"]. The SideStore nightly (Oct 27) was rebuilt with the **iOS 27.2 SDK** [V: releases page](https://github.com/SideStore/SideStore/releases).
   - One **open** siboot issue (opened 2026-11-15): "Something at 10.7.0.1:49152 took the connection but didn't answer as an iPhone" — iPhone 14, iOS 27, siboot 0.18.1. The error text itself says what to check: **Developer Mode on, and LocalDevVPN is the VPN that's on** (the reporter had another sideloading setup expire just before). [V/C: siboot issue #3](https://github.com/FrizzleM/siboot/issues/3)
   - One **iOS 27 sign-in quirk reported by the community**: the "airplane-mode 2FA code" trick (§3, step 31) reportedly stopped working for one user's re-login on iOS 27 [C: r/SideStore thread "iOS 27 + Sidestore?"](https://reddit.com/r/SideStore/comments/1u0wzdh/ios_27_sidestore). If the trick fails, use SMS/call/another-device codes instead (they worked on iOS 27.2 in issue #1693) [C].
3. **Does updating wipe anything?** Apple's iOS 27 update notes (27.0, 27.0.1, 27.2, 27.2.1) contain no data-erasure step — they are ordinary bug-fix/feature updates installed over the existing system [V: support.apple.com/en-us/149076]. OTA updates preserve apps and data as a matter of standard iOS mechanism [I]. **Erase** only happens via Settings → General → Transfer or Reset [I]. Keep the iCloud backup anyway (§0).
4. Update path: Settings → General → Software Update → Update to 27.2.1. Plug into power, stay on Wi-Fi.
5. **What the update does break, occasionally:** the lockdown/remote **pairing file** — "Your pairing file may expire if you update or reset your iPhone… This also occurs at random times because of Apple's software" [V: SideStore install docs + pairing-file doc](https://docs.sidestore.io/docs/advanced/pairing-file). On the iOS 27 self-pairing route this is recoverable without a computer — see §5.4. On iOS 26.x this would have required a PC; that is the whole reason for the iOS 27 requirement [V: SideInstaller README — Compatibility table].
6. EU note: nothing in this flow is region-gated; the free-provisioning route used here is worldwide and DMA-agnostic [D: no-mac-bootstrap.md Q6]. (iOS 27.2's "Siri AI is not available in the EU" notice is feature-gating, irrelevant to sideloading [V: same Apple page].)

---

## 2. The siboot bootstrap — step by step

### What changed since the README was written (read this first)

- The GitHub README of SideInstaller (fetched today) still describes a **three-app flow**: rootshell (with its "Location Diary Mode") + Open Minis + LocalDevVPN, pairing via a `siboot-pair.wasm` in rootshell's shell.
- **That is outdated. Since Open Minis 1.16 (App Store, 2026-11-14) and siboot 0.18.x, the iPhone pairs with itself inside Open Minis — rootshell is no longer needed.** siboot "pairs the iPhone with itself too… so it needs 1.16 or later"; the pairing happens under Settings › Privacy & Security › Developer Mode via a "Pair with siboot" entry that Open Minis advertises itself. rootshell's hand-off "stays for an older Minis" as a fallback. [V: siboot minis/README](https://github.com/FrizzleM/siboot/blob/main/minis/README.md) — version history 0.18.0/0.18.1 entries, dated in-text 2026-11-10 and 2026-11-14.
- SideInstaller's own install page was updated accordingly on **2026-11-15 12:59 CET** (version 2.0.1) and lists only **Open Minis** and **LocalDevVPN**. [V: https://sideinstaller.net/install.html]
- The guide below follows the **current** (Minis-only) flow; the rootshell variant is kept at step 17 as fallback.
- **Above the fold, honestly:** the shipped-siboot-0.18.1 + App-Store-Minis-1.16 combination has **no public end-to-end iPhone success report yet** (the 2026-11-10 iPhone test used a `minis-pairing`-branch build and an earlier Minis; the 2026-11-14 self-pairing success used a branch test build). **Expect the first pairing run to be where it breaks** — both fallbacks are stated below, and a failed bootstrap is recoverable (siboot persists its state in `~/.siboot` and re-pairs).

### Phase A — install the helper apps

1. On the iPhone, install from the App Store (both free): **Open Minis** (https://apps.apple.com/app/id6759188481) and **LocalDevVPN** (https://apps.apple.com/app/id6755608044). [V: sideinstaller.net/install.html — "Get the apps"; the same two app IDs appear in the SideStore FAQ's LocalDevVPN link and the siboot docs]
2. If you see a **third** app recommended in older instructions — **rootshell** (id6755794662, "Local Terminal SSH") — you do not need it with Open Minis 1.16+. It is only the legacy pairing path. [V: SideInstaller README; siboot minis/README]
3. (iOS 27 requirement check) SideInstaller needs **iOS 27+, Wi-Fi, Developer Mode on, Open Minis 1.16+** [V: sideinstaller.net/install.html]. On iOS ≤ 26.7 this whole no-computer route does not work [V: Compatibility table].

### Phase B — Developer Mode

4. Check **Settings → Privacy & Security → Developer Mode** exists. [V: SideStore install docs step 7; siboot minis/README]
5. If the switch is **not there** (normal on a phone that never had a dev-signed app): connect LocalDevVPN (open LocalDevVPN → Connect), open Open Minis → tap the **console icon** (top right) → **Shell Terminal**, and run exactly:
   ```
   wget -qO- https://raw.githubusercontent.com/FrizzleM/siboot/main/minis/install.sh | sh -s developer-mode
   ```
   Developer Mode then appears in Settings › Privacy & Security › Developer Mode. [V: sideinstaller.net/install.html — "No Developer Mode in Settings?" section]
6. Turn **Developer Mode** on in Settings. **The iPhone restarts.** After unlocking, iOS asks you to tap **Turn On** to confirm. [V: SideStore install docs step 7 — "turn on Developer Mode. Your device will restart."; siboot minis/README — "iOS restarts, and once you unlock it, asks you to tap Turn On"]
7. (Passcode caveat) iOS can only *turn Developer Mode on for you* (`--turn-on`) while the phone has **no passcode**; with a passcode set (normal), you tap the switch in Settings yourself — exactly what step 6 does. [V: siboot minis/README — Developer Mode section]

### Phase C — run siboot (installs SideInstaller, signed with your burner ID)

8. Open **LocalDevVPN → Connect**. Leave it connected. [V: sideinstaller.net/install.html step 1 — "Connect LocalDevVPN, then run…"]
9. Open **Open Minis** → console icon (top right) → **Shell Terminal**. [V: same]
10. Run exactly (one line):
    ```
    wget -qO- https://raw.githubusercontent.com/FrizzleM/siboot/main/minis/install.sh | sh
    ```
    The script downloads the siboot binary from the siboot repo and verifies it against an embedded SHA-256. [V: sideinstaller.net/install.html step 2; siboot minis/README — "Running it"]
11. siboot opens a **page over the terminal** (in Minis's own browser) that shows each step and asks its questions — tap through it there. (Add `-s -- --terminal` after `sh` if you prefer the text terminal.) [V: siboot minis/README — "The page"]
12. **Self-pairing (first run only):** the page says what to do; tap **Start pairing**, then open **Settings → Privacy & Security → Developer Mode**, scroll to the bottom, tap **Pair with siboot**, enter your **passcode**, then the **6-digit code** siboot shows (it also arrives as a notification). [V: siboot minis/README — "Pairing in Minis" + "Running it"]
13. A small player opens over Minis playing silence while you are in Settings — **leave it open**; it keeps Minis alive in the background (iOS suspends backgrounded apps within seconds, which would kill the pairing). Go back to Minis once Settings says it's paired. [V: siboot minis/README — "Keeping Minis awake"]
14. After pairing, siboot saves the pairing file privately (`~/.siboot`, mode 600) and reuses it on later runs — you will not be asked again. [V: siboot minis/README — "Running it" + steps table]
15. Sign in with the **burner Apple Account**: type the email — **all lowercase** (Apple's sign-in proof hashes the name as typed; a capital letter makes siboot wrongly report "account or password was wrong") — then password, then the **6-digit verification code**. At the code prompt a letter offers **Other ways** to get one (text, call, your other Apple devices, resend). Keep Minis open until siboot says it's done. [V: siboot minis/README — questions + 0.16.2 changelog entry]
16. **Trust the developer app (first time):** open **Settings → General → VPN & Device Management**, and trust your Apple Account (the entry under "Developer App") — i.e. tap it → Trust → enter passcode. Without this SideInstaller won't launch. [V: siboot minis/README — "The first time, trust your Apple Account in Settings › General › VPN & Device Management"; exact wording of the taps from SideStore install docs steps 2–5]
17. **Legacy fallback (pre-1.16 Minis or if self-pairing fails):** the README's original flow — install rootshell, in rootshell enable **Settings › Location Diary Mode › Session Only**, tap Local Shell → Open, run `curl -fsSLO …siboot-pair.wasm`, `ifconfig en0 > ifconfig.txt`, `./siboot-pair.wasm`, then "Pair with siboot" with the passcode + the code **rootshell** shows, and in Minis run the same install command, typing rootshell's **transfer code** instead. [V: SideInstaller README — "How to use it" (still on the page today); siboot minis/README — "siboot still takes rootshell's file… whenever siboot-pair is offering"]. Known rough edge: the hand-off between the two apps "hasn't completed on an iPhone yet" per the 0.13.x notes — prefer the Minis flow. [V: siboot minis/README — "What's been tested"]

### Phase D — SideInstaller installs SideStore

18. With **LocalDevVPN connected**, open **SideInstaller**, log in with the burner Apple Account (password + 2FA code; the code prompt offers text/call/devices), and tap **Install SideStore** (or **Install SideStore + LiveContainer** — not needed for us). [V: SideInstaller README step 4]
19. In SideInstaller's picker choose the SideStore channel: **prefer Stable 0.7.0-alpha** (2026-09-15, the tagged "Latest") — a #1693 participant confirmed the §3 anisette recipe "works with the stable version as well!" on iOS 27.2, and the SideStore FAQ explicitly withholds data guarantees from unofficial/nightly builds ("We cannot guarantee your data transfers when testing an unofficial or nightly build") — **a vault app must not run an experimental build**. **Nightly** (2026-10-27 build, SDK 27.2, matches iOS 27.2/27.2.1; the error-code doc points iOS 26.4+ users at it) is the fallback only if stable misbehaves. [V: releases page; error-codes doc; FAQ; C: issue #1693 comment]
20. Wait for download → sign → install. SideInstaller signs SideStore **with the burner's own free developer certificate** (registers 1 App ID — SideStore's widget costs no extra App ID), registers the device, and puts the pairing file inside the app. The install runs as a background task on iOS 26+ (progress in the Dynamic Island / Lock Screen); it turns the Dynamic Island orange when it needs you (2FA code, App ID cost, VPN, Trust prompt). [V: SideInstaller CHANGELOG 1.6.0/1.6.2; siboot minis/README steps table]
21. If SideInstaller stops on a popup showing the **App ID cost** and too few App IDs remain, it names when the next one frees — a fresh burner ID has all 10, so this should not happen. [V: SideInstaller CHANGELOG 1.6.0 — App-ID-cost popup]
22. When SideInstaller finishes, SideStore is on the Home Screen. **Do not open it yet if you plan the anisette fix first** — SideInstaller already pre-configures SideStore for the post-October Apple rules (see §3 step 25). [V: SideInstaller CHANGELOG 1.6.1 — "SideStore signs in after SideInstaller installs it"]

**Time check:** phases A–D ≈ 25–40 min including the two restarts. [I, from step count]

---

## 3. SideStore sign-in with the fresh burner Apple ID

### Current state of the Oct 2026 auth waves (-80045 / -80046 / 503)

23. **No release fix exists as of 2026-11-16.** The latest tagged release is still **0.7.0-alpha** (2026-09-15, "fixes for 503 during sign-in"); the Oct 12 (-80045) and Oct 22 (-80046) Apple changes are **not** covered by any tagged release. The Nightly tag (build of 2026-10-27, commit cd260851, "Update SDK to 27.2") is the newest build. [V: https://github.com/SideStore/SideStore/releases — fetched 2026-11-16]
24. Therefore the working recipe is the **anisette override**, community-verified on iOS 27.2 with a brand-new free Apple ID (issue #1693, 2026-10-25, confirmed by several other users through Oct 28): [C: issue #1693](https://github.com/SideStore/SideStore/issues/1693)
   1. SideStore Settings → **User Customizations** → **On-Device Anisette OFF** → restart SideStore when asked.
   2. Settings → **Anisette Servers** → select **"SideStore (New)"** (`new.ani.sidestore.io`) → scroll down → **Enable Auto Rotation OFF**.
   3. Settings → User Customizations → **Anisette Client Configuration** → **Remote Server Sync → Fetch** → **Save as Overrides** (and the page's **Save Overrides** button).
   4. Back in User Customizations → **Reset adi.pb**.
   5. Sign in **once** and wait — do not tap again while it processes.
   - Note: SideInstaller 1.6.1 (current 2.0.1) performs the equivalent configuration **automatically** right after installing SideStore ("turns both off and picks 'SideStore (New)'… and puts client info… back to SideStore's default"). Check rather than assume: on-device anisette OFF, auto-rotation OFF, server "SideStore (New)". [V: SideInstaller CHANGELOG 1.6.1]
25. **Server policy — official only.** Use the official list ([servers.sidestore.io/servers.json](https://servers.sidestore.io/servers.json)) — servers "SideStore (New)", "jkcoxson", "iLoader (Mac ADI)" are the macOS-library ones that currently pass Apple's checks. **Never** pick a random shared community server: "Older Anisette servers that are used by many users are known to cause locking of Apple ID's. Please use one of the official Anisette servers, or host your own anisette-v3-server." Self-hosting exists (Render, docker) but needs a card for identity and is overkill for one phone. [V: SideStore FAQ + anisette docs](https://docs.sidestore.io/docs/advanced/anisette)
26. **First sign-in attempt:**
    - Open SideStore → sign in with the burner's email (lowercase) + password. [V: SideStore install docs step 10; siboot lowercase finding applies to Apple's GrandSlam generally]
    - Wrong-address mistake answers **-20970** ("account information entered incorrectly") — double-check the address before tapping. [C: issue #1693]
    - 2FA: enter the 6-digit code. SMS 2FA is supported (0.6.4+ "Full Two-Factor Authentication: Added support for SMS verification, Voice phone call in addition to trusted devices"). [V: 0.6.4 release notes]
    - If a **"Device Registration Error"** (DeviceEndpointNotInitialized / "VPN tunnel iface is up but tunnel peer IP is not yet reachable") appears after the password+code: **connect LocalDevVPN, wait a few seconds, tap Retry** — sign-in then completes. [C: issue #1693 — verified resolution]
27. **2FA code never arrives (the radio-off method) — community workaround:** when SideStore waits for the code that Apple would push to this very phone: turn on **Airplane Mode with Wi-Fi off**, go to **Settings → [your name] → Sign-In & Security → Get Verification Code** (the manual option appears when the phone is offline), read the code, turn Wi-Fi/airplane mode back off, then type the code into SideStore. [C: r/SideStore + r/sideloaded threads, e.g. "I'm not receiving the 6 digit verification code"](https://reddit.com/r/SideStore/comments/1v4o19b/im_not_receiving_the_6_digit_vertification_code). **iOS 27 caveat:** one report says this exact trick stopped working on iOS 27 for a re-login [C: r/SideStore "iOS 27 + Sidestore?"]. Fallbacks that did work on iOS 27.2: SMS or call codes (SideStore offers SMS 2FA), or a code from another Apple device signed into the same burner ID. [C]
28. **After sign-in completes:** Settings shows the account as **Free Developer Account**. **Refresh SideStore immediately** — My Apps tab → tap the **'7 DAYS' counter** to the right of SideStore. Two reasons: it's the documented first step after login ("After a clean install, it's best to not sideload anything before refreshing SideStore" — it fixes the app-group ID left by bootstrap tools), and if a prompt appears **asking to revoke or create a new signing certificate, tap 'Yes' / 'Refresh Now'** — this is the one moment it is safe: SideStore re-signs and re-installs itself (Home Screen flash + notification, back within seconds) and **no user apps exist yet**. [V: SideStore install docs steps 11–13 + FAQ]

### Fresh-ID first-login blockers, documented

- -80045/-80046/503 — the Oct 2026 waves; resolved by the anisette recipe above (steps 24–25). [C+V mix as cited]
- -20970 — typo'd email. [C: #1693]
- Device Registration Error — LocalDevVPN was down; connect + Retry. [C: #1693]
- "This account requires signing in with two-factor authentication" (3018) / "Incorrect verification code" (3019) — expected flow errors, retry with a fresh code. [V: error-codes doc]
- Account at its certificate/App-ID limit — impossible on a fresh burner; noted for completeness (siboot asks which certificate to revoke — decline and see §4 rule). [V: siboot minis/README — "Not tested yet" list]
- Unverified email on the burner ID — Apple refuses developer services (error 2160) until the address is verified at account.apple.com. [V: SideInstaller CHANGELOG 1.5.1]

---

## 4. Add our source + first install of the Therapy app

29. **Add the source (exact taps):** open SideStore → **Browse** tab → tap **Sources** (top right) → tap **+** → paste the source URL → **Add** → **Add Source** → **Done**. The source's apps then appear under Browse. [C: DolphiniOS official walkthrough (same UI lineage), YouTube/Reddit walkthroughs agree on Browse → Sources → + → Add Source; e.g. https://dolphinios.oatmealdome.me/beta and r/eeveespotifyipa post]. One-tap alternative: open a `sidestore://source?url=<URL>` link (SideStore must already be installed, or the link crashes the tab). [V: app-sources doc](https://docs.sidestore.io/docs/advanced/app-sources)
    - Our source URL is the AltStore-format `source.json` published by the CI pipeline — take the current URL from [live-workflow-examples.md](live-workflow-examples.md) (placeholder here: `https://<our-ci-host>/apps.json`). [D]
30. **Install:** in Browse → our source → tap the Therapy app → **Install** (or **Free/Get**). LocalDevVPN must be connected; if SideStore reports "No Wi-Fi/StosVPN" (1414) or minimuxer error 27, check Wi-Fi **and** LocalDevVPN, then retry ("spamming the button sometimes helps"). [V: error-codes doc 1414/27; common-issues doc]
31. **What the first Install does (mechanics):** SideStore downloads the IPA from the source URL, **re-signs it with your burner's personal development certificate** ("SideStore resigns apps with your personal development certificate"), registers an App ID (consuming **1 of 10 weekly App IDs**), and installs it — consuming the **2nd of the 3 active app slots** a free account allows (SideStore itself is slot 1). [V: FAQ — limitations; prior research Q2]
32. **Trust prompt for the new app:** the first launch after install fails until you trust the developer certificate: **Settings → General → VPN & Device Management → "Developer App" section → tap the entry named after the burner Apple Account → "Trust [Apple Account name]" → "Allow & Restart" → enter passcode.** If iOS demands a restart ("Allow & Restart"), let it — the phone reboots and the app is trusted after unlock. [V: SideStore install docs steps 2–5, wording verbatim; error 1011 text — "you still need to trust your Apple Account in Settings"]
33. After trust, launch the Therapy app. It should open. (If iOS shows "Untrusted Developer", redo step 32.) [V: install docs]
34. **Refresh the new app once from SideStore** (My Apps → tap its days-left counter) so the profile clock and app-group state are settled. [I from docs step 12 pattern]

### The certificate prompt — the one rule that protects the vault

35. **ACCEPT (the one safe moment):** during **first-time SideStore setup**, step 28's prompt — "a prompt asking to revoke or create a new signing certificate → tap 'Yes' or 'Refresh Now'" — is safe **only** because SideStore immediately re-signs and reinstalls itself and no other apps exist yet. [V: install docs step 13]
36. **NEVER ACCEPT — the vault-loss rule:** once the Therapy app is installed, **any prompt whose action revokes the current certificate and creates a new one must be declined/cancelled** while apps signed with that certificate are installed. A free Apple ID holds **one** development certificate; revoking it "stopped every app SideStore had signed" — those apps can no longer be refreshed in place, die at day 7, and a forced reinstall means deleting the app **with its vault** (cross-team/keychain rule in no-mac-bootstrap.md Q4 makes it worse). [V: SideInstaller CHANGELOG 1.6.0 — "the only way on was revoking it, which stopped every app SideStore had signed"; D: no-mac-bootstrap.md Q4]
37. The prompt you may legitimately meet, and its safe answer: when SideInstaller (or another tool) later needs to sign something and SideStore already holds the burner's certificate, the popup is titled **"A certificate already exists"** — the correct button is **"Use SideStore's certificate"**, which has SideStore export its certificate via `sidestore://certificate` and **revokes nothing**. [V: SideInstaller README — Links section; CHANGELOG 1.6.0]
38. Corollary — **never use this burner Apple ID on a second iPhone**: installing SideStore on another device with the same free ID revokes this device's certificate ("Certificate revokes when installing SideStore on multiple devices"), killing refreshes of the installed Therapy app here. [C: issue #978](https://github.com/SideStore/SideStore/issues/978)

---

## 5. Renewal reality (the 7-day treadmill)

### 5.1 The clock

39. Free-account apps are signed with **7-day provisioning profiles**. The days-left counter sits next to each app in **My Apps**; tapping it **manually refreshes** that app (renews the profile; SideStore "resigns" — data stays). Let it hit zero and the app stops launching; refresh before expiry. [V: FAQ — "SideStore will periodically 'refresh' your apps in the background, to keep their normal 7-day development period from expiring"; install docs step 12]

### 5.2 Auto-refresh — conditions

40. SideStore refreshes apps automatically in the background, but each refresh **runs on the phone** and needs, at the moment it fires: **Wi-Fi connected**, **LocalDevVPN connected** (it's the special do-nothing VPN that lets iOS install to itself; "SideStore cannot refresh or install applications without Wi-Fi and LocalDevVPN" — error 1414), the phone **used/unlocked** around that time ("You still need to use the device as it can only do the action when you it's unlocked"), and **Background App Refresh on + Low Power Mode off**. [V: FAQ + error-codes 1414; C: #496 comment by Spidy123222]
41. Cellular refresh exists via Shortcuts but is officially "experimental"; the standing rule is Wi-Fi + LocalDevVPN ("Stable refresh still requires Wi-Fi + the official App Store LocalDevVPN. Cellular-only refresh remains experimental."). [C/V: discussion #1482, Sept 2026 — scheduled refresh verified on an iPhone 12, iOS 26.6.1]
42. **Make it reliable with a Shortcuts automation (recommended):** Shortcuts → new shortcut → action **"Refresh All Apps"** (SideStore) → Automations → run daily at a set time, "Run Immediately" + "Notify When Run" on; a second run an hour later guards against the occasional timeout. Keep LocalDevVPN's VPN **on demand** so it's always up on home Wi-Fi. [C: #496 comments (jimmysofat6864's months-long setup) — still the standard advice; the 0.6.4+ nightly also has built-in background scheduling per its changelog]
43. Leave the phone plugged in overnight on Wi-Fi and the daily automation fires unattended. [I]

### 5.3 If it lapses

44. Profile expired → the app icon greys/refuses to launch ("Untrusted Developer"-style behavior). **Do not delete the app.** Open SideStore (Wi-Fi + LocalDevVPN) and **manually refresh the app** — re-signing reinstalls over the existing app and keeps its data ("sideload the same or an updated IPA… with your data still intact" — the same-tool, same-ID case). [V: FAQ — "Can I transfer my AltStore/Sideloadly apps?"; C: community reports that refresh revives an expired-but-not-deleted app]. If SideStore itself can no longer run, see 5.4.
45. **Deleting the Therapy app deletes its vault** — that is the one unrecoverable mistake. Everything above exists to avoid it. [D: no-mac-bootstrap.md Q4 — keychain/team rule]
46a. **Bold-face the lapse risk:** in the weeks-offline → revive path, the vault's survival rests on **nobody deleting the app** and on **SideStore itself still running**. If SideStore's own profile expired too, revival requires re-running siboot — and the siboot author's own NOT-TESTED list includes **"a second run after the 7-day profile expires"** [V: siboot minis/README]. Until that is device-verified: **do not let it lapse** (run the §5.2 automation; keep 2 spare days of margin before any 7-day boundary), and if it does lapse, **do not delete anything** — re-run siboot, revive, and note what happened. [U on the siboot re-run itself]

### 5.4 Pairing file after iOS updates — the iOS 27 answer

46. The pairing file "may expire if you update or reset your iPhone… This also occurs at random times because of Apple's software." [V: install docs + pairing-file doc]
47. SideStore's official replacement guide is **iLoader-on-a-computer** — unusable here. [V: pairing-file doc — the whole guide assumes USB + iLoader]
48. **On iOS 27 with the siboot tooling the computer-free recovery is:** re-run siboot in Open Minis (`wget -qO- https://raw.githubusercontent.com/FrizzleM/siboot/main/minis/install.sh | sh`) — it **re-pairs the iPhone with itself** (step 12 flow; on later runs it first tries the saved file, and re-pairs if the iPhone no longer accepts it) — then in SideInstaller use **"Import pairing file"**, or SideInstaller's **"Get it from SideStore"** card, or in SideStore Settings → **Reset Pairing File** and re-hand the file over. [I — assembled from documented pieces: siboot minis/README ("After that siboot uses the pairing file it saved"), SideInstaller README (pairing-file links: `sideinstaller://pairingFile?data=…` and "Get it from SideStore" / `sidestore://pairing?urlname=sideinstaller`), SideInstaller install page "Import pairing file". **Not explicitly documented as an iOS-update-recovery path — confirm on device [U]**]
49. SideInstaller itself survives iOS updates: its pairing/sign-in state is kept out of backups and stays on the phone [V: CHANGELOG 1.7.0], and "Free accounts' apps last 7 days; **SideInstaller can renew itself** from then on" [V: siboot minis/README] — so even after a lapse you can re-sign/reinstall SideStore from SideInstaller; the Therapy app then gets refreshed from the new SideStore. Note the siboot author's own caveat: "a second run after the 7-day profile expires" is in the **not tested yet** list. [V: siboot minis/README]
50. After **any** future iOS update: expect to redo step 12-style pairing and re-check SideStore's pairing before the next 7-day boundary; keep 2 spare days of margin. [I]

---

## 6. Honest gaps (could not verify today)

- **Nothing on-device has been verified by this research** — all steps are document-verified, not device-run. The single most likely failure points, ranked: (a) LocalDevVPN not the active VPN / Developer Mode off → siboot issue #3's error; (b) anisette selection → -80045/-80046 (recipe at §3.24); (c) 2FA code never arriving (radio-off trick unreliable on iOS 27 per one report).
- SideStore's exact revoke-prompt **body text** was not found verbatim in a primary source (docs paraphrase: "a prompt asking to revoke or create a new signing certificate"; SideInstaller names the popup title "A certificate already exists" and its own safe button "Use SideStore's certificate"). Treat any revoke-shaped prompt per §4.35–37. [U]
- The iOS 27 pairing-recovery path (§5.4 step 48) is assembled from documented mechanisms, not documented end-to-end. [U]
- SideStore source-adding taps are community/UI-lineage-verified (Browse → Sources → +), not in SideStore's own docs. [C]
- The 10-App-ID/weekly window mechanics ("App IDs renew if the app has existed 7 days after when it was first installed or renewed last") come from SideStore's error-code doc; behavior on a fresh burner confirmed only by community posts. [V doc / C behavior]
- siboot at certificate/App-ID limits asks "which certificate to revoke" — untested by the author; irrelevant for a fresh burner but keep §4.37 in mind. [V: siboot minis/README "Not tested yet"]

---

## Source register (all fetched 2026-11-16 unless noted)

| Source | Used for |
|---|---|
| https://github.com/FrizzleM/SideInstaller (README) | requirements, compatibility table, rootshell legacy steps, `sideinstaller://` links, caution block |
| https://sideinstaller.net/install.html | current 2-app flow, developer-mode command, version 2.0.1 / 15 Nov 2026 stamp |
| https://sideinstaller.net/help.html | Q&A: computer-free on iOS 27, Apple ID safety, fake-site warning |
| https://raw.githubusercontent.com/FrizzleM/SideInstaller/main/CHANGELOG.md | 1.6.0/1.6.1/1.7.0/2.0.x: "A certificate already exists" mechanism, post-Oct anisette auto-fix, App-ID popups, iOS 27 self-pairing, backup-exclusion |
| https://github.com/FrizzleM/siboot + /blob/main/minis/README.md | self-pairing flow, Open Minis 1.16 gate, passcode/code step, lowercase email, pairing persistence, tested-on-iOS-27, not-tested-yet list |
| https://github.com/FrizzleM/siboot/issues/3 (open, 15 Nov 2026) | current bootstrap failure mode + remedy hints |
| https://docs.sidestore.io/docs/installation/install | trust wording ("Trust … Allow & Restart"), 7-DAYS tap, revoke-prompt-at-setup, pairing expiry after update |
| https://docs.sidestore.io/docs/faq | 3-app/10-ID limits, anisette lock warning, refresh-first-after-login, app-group |
| https://docs.sidestore.io/docs/advanced/anisette | v3 servers, official list, self-host route |
| https://docs.sidestore.io/docs/advanced/pairing-file | pairing expiry, iLoader replacement (computer — unusable here) |
| https://docs.sidestore.io/docs/troubleshooting/error-codes + common-issues | 1414/27/1011/3018-3019 meanings, nightly advice for iOS 26.4+ |
| https://docs.sidestore.io/docs/advanced/app-sources | `sidestore://source?url=` one-tap links |
| https://github.com/SideStore/SideStore/releases | no release fix for Oct waves; nightly 2026-10-27 (SDK 27.2); 0.7.0-alpha 2026-09-15 |
| https://github.com/SideStore/SideStore/issues/1693 | the 5-step anisette recipe on iOS 27.2 + fresh free ID; -20970; Device Registration Error fix; "works on stable too" |
| https://github.com/SideStore/SideStore/issues/496 | refresh needs unlocked device; Shortcuts automation |
| https://github.com/orgs/SideStore/discussions/1482 | Wi-Fi + LocalDevVPN requirement; cellular experimental; scheduled refresh verified iOS 26.6.1 |
| https://github.com/SideStore/SideStore/issues/978 | second-device cert revocation warning |
| https://support.apple.com/en-us/149076 + developer.apple.com/news/releases | iOS 27.2.1 (24B91034, 2026-11-09) current; update contents; no wipe step |
| Reddit r/SideStore, r/sideloaded threads (linked in text) | radio-off 2FA method + iOS 27 report; Browse→Sources→+ taps |
