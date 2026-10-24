# Connect an Android phone over Wi-Fi (SOP)

Goal: the laptop can install and run the app on your phone with no cable.
Time: about 5 minutes the first time, about 1 minute after that.

## Part 1: Turn on Developer options (phone, once)

1. Open **Settings**, then **About phone**.
2. Find **Build number** (on Xiaomi/POCO: **OS version** or **MIUI/HyperOS version**).
3. **Tap it 7 times.** Enter your PIN if asked. You will see "You are now a developer".
4. Go back to **Settings**, then **Additional settings** (or **System**), then **Developer options**.
5. Switch these **ON**:
   - **USB debugging**
   - **Wireless debugging** (tap the words to open its page, not just the switch)
   - Xiaomi/POCO only: **USB debugging (Security settings)**. Needed if an agent must tap the screen for you.

## Part 2: Same network

Phone and laptop must be on the **same Wi-Fi**, or the laptop is on the phone's hotspot.
Keep the phone **awake and unlocked** while connecting.

## Part 3: Pair (once per laptop)

1. On the phone: **Wireless debugging**, then **Pair device with pairing code**.
2. The phone shows an **IP:PORT** and a **6-digit code**. Keep this screen open.
3. On the laptop:
   ```bash
   adb pair <IP>:<PAIRING_PORT>
   ```
   Type the 6-digit code when asked. You should see `Successfully paired`.

The pairing port is different from the connect port, and it changes every time the pairing screen opens. A wrong port gives `protocol fault`. Use exactly what the pairing screen shows right now.

## Part 4: Connect

1. Close the pairing popup. On the main **Wireless debugging** page, read **IP address & Port**.
2. On the laptop:
   ```bash
   adb connect <IP>:<CONNECT_PORT>
   adb devices -l
   ```
   The phone should be listed as `device`.

## If it does not work

| Symptom | Fix |
|---|---|
| `offline` | Phone has not trusted this laptop. Look for an "Allow debugging?" popup on the phone and tick **Always allow**. If none, redo Part 3. |
| `offline` after pairing | Stale connection. Run `adb disconnect`, then `adb connect <IP>:<CONNECT_PORT>`. |
| `failed to connect` | The port changed. Toggle **Wireless debugging** off and on, read the new port, and connect again. |
| Port unknown (auto-discovery empty, common on hotspots) | Scan for it: `nmap -T5 -p 30000-50000 --open <IP>`. The open ports are the pairing port (only while that screen is open) and the connect port. |
| Worked yesterday, not today | Android turns Wireless debugging off by itself when the network changes or the phone restarts. Switch it on again and read the new port. No re-pairing is needed. |

## Rules for agents

- Never use `pkill -f <pattern>` if the pattern appears in your own command line. It kills your own shell.
- Take a screenshot (`adb exec-out screencap -p > shot.png`) **before** every `adb shell input tap`.
- Do not store the pairing code or the phone PIN in files, commits or memory. The code is single-use. If a pair attempt fails, ask for a **fresh** code.
- Stay within what the human asked. Do not open other apps or change phone settings.
- Disconnect when done: `adb disconnect`.

## Install and run this app

```bash
./gradlew :app:assembleDebug        # run as a background command, takes minutes
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.therapylocal.app/.MainActivity
adb logcat -s ModelDownload LlmEngine MainViewModel
```

On first launch the app downloads about 2.6 GB. Use Wi-Fi.

## Never re-download the model

The model (about 2.6 GB) lives in the app's own folder on the phone:
`/sdcard/Android/data/com.therapylocal.app/files/models/`

- **Update the app with `adb install -r`. Never uninstall.** Updates keep the model. Uninstall deletes it (Android will ask whether to keep app data, say **Keep**).
- **Everyone signs with the same debug key** (`app/debug.keystore`, committed). A different key blocks the update and forces an uninstall.
- **Check the model:** `scripts/model.sh check` (file, size, sha256).
- **Seed it without downloading:** `scripts/model.sh push gemma-4-E2B-it.litertlm`, then open the app.
- The app trusts the file size on launch, so starting is instant. It only verifies the sha256 right after a download.
- Models now live in sub-folders: Gemma stays at the top level, `embeddinggemma-2-740m/` (485 MB, text and audio embeddings) and `whisper-<size>/` (tiny 103 MB, base 160 MB, small 375 MB) are fetched on demand with the same resumable, checksummed downloader.

## Repeatable audio tests (debug builds)

Debug builds can replay a WAV through the real chunker instead of the microphone, and use one as a voice query.

```bash
python3 -I scripts/make_test_audio.py <dir with 0.wav 1.wav 8k.wav> /tmp/testaudio   # known-speech sources: see the script
adb shell mkdir -p /sdcard/Android/data/com.therapylocal.app/files/test
adb push /tmp/testaudio/. /sdcard/Android/data/com.therapylocal.app/files/test/
```

Then use the "Record from ..." buttons on the Record tab and the "Query ..." buttons on the Search tab. Read the result with `scripts/traces.sh summary`.
