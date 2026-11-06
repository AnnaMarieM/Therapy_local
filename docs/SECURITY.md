# Security: patient data at rest

Everything the app stores about patients is encrypted under one data key (DEK) that exists in RAM only while the app is unlocked. Code: `vault/` (`Vault`, `VaultCrypto`, `KeyFile`, `SecureFile`), `data/MindStore.kt`, `data/PendingAudio.kt`.

## Threat model

| In scope | Out of scope |
|---|---|
| Lost or stolen phone | A compromised app process while unlocked |
| Files read over USB / adb / a file manager | A rooted device while unlocked (key is in RAM) |
| Cloud or device backups (key file is excluded: `noBackupFilesDir`) | Shoulder surfing, screen capture of the unlocked UI |

## Keys

| Item | Detail |
|---|---|
| Data key (DEK) | Random 256 bit, generated at first setup, never stored in plaintext, held in RAM only while unlocked |
| Password path | KEK = PBKDF2-HMAC-SHA256(password, 16-byte random salt, 600,000 iterations); the DEK is sealed with AES-256-GCM under the KEK |
| Biometric path (optional, at setup or later in Settings) | Android Keystore AES-256-GCM key, user authentication required, BIOMETRIC_STRONG only, invalidated by a new biometric enrollment; the DEK is sealed with it through a BiometricPrompt CryptoObject |
| Key file | `noBackupFilesDir/vault.json`: salt, iterations, password-sealed DEK, optional biometric IV and sealed DEK |
| Lock | Wipes the DEK and restarts the process, so no decrypted data survives in ViewModels or singletons. Background processing resumes after the next unlock |

## What is encrypted

| Data | Where | How |
|---|---|---|
| `mind.db`, the only database (clients, sessions, transcripts with timestamps, windows and embeddings, facts, mentions and evidence quotes, LLM caches, the job queue) | internal `databases/` | SQLCipher (AES-256), DEK as raw key, incl. journal/WAL |
| Audio of a recording until its session is processed | internal `files/memory/audio/<recording>/<idx>.pcm` | SecureFile: magic `TLV1` + AES-256-GCM, AAD = file name, atomic write. Deleted by the session's last job (`cleanup`) |
| Log fields `client` and `session` | `logs/tl.log` | written as an 8-hex HMAC pseudonym keyed by the DEK (`locked` while locked) |
| Foreground notification | lock screen | private visibility; the public version only reads "Building client memory" |

There are no transcript, cache or queue files: all of that is in `mind.db`. Job errors are stored as short codes, never as exception messages (they can quote patient text). Live-cue and search audio stays in RAM and is never written.

Opening the database or a patient file while locked fails closed (`VaultLockedException`).

## What is NOT encrypted, and why

| Item | Why |
|---|---|
| Model files and LiteRT caches | Public model weights, no patient data |
| SharedPreferences `settings` | Engine, model and live-cue preferences only, no patient data |
| Developer inputs: `prompts/`, `job.json`, `in/` test transcripts (external `files/memory/`) | Read only by the debug harness. Use synthetic transcripts there |
| Debug-harness marker `out/DONE` | Written only when a run is started from the debug harness, never in app mode |
| Debug `LiveCuesBench` output | Debug-build benchmark tool, synthetic input only |

## Engine processes

Models run in two bound, non-foreground processes, `:live` and `:batch` ([AD-01](AD/AD-01.md)). They are stateless model servers:
- They never open `mind.db`, the vault or the key file, and never receive the DEK or the log pseudonymisation key (`App.onCreate` sets up logging only in a host).
- They hold prompts, transcripts and audio **in memory only**, while a call runs. Audio arrives in `SharedMemory`; nothing patient-related is written to disk by a host. The memory ends with the process, and a host leaves as soon as the main process dies, so locking the vault (which kills the main process) also ends both.
- Their log files (`engine-live.log`, `engine-batch.log`) follow the same rules as `tl.log`: lengths, counts, ids and timings only. Failures in calls that handle text are logged by type and code location, never with a message, and the host's uncaught-exception handler writes redacted lines too. `client` and `session` are never logged by a host.
- The services are not exported, so only the app can bind them.
- Debug builds will carry a fault-injection entry for tests. It is protected by the `DUMP` permission and does not exist in release builds (described when that step lands).

**Residual risk.** If a host crashes natively, Android may write a tombstone or a dropbox entry (`/data/tombstones`, `dropbox`), and these can contain fragments of what was in the host's memory, such as part of a prompt. This is the same as when models ran in the main process. They are system-owned files, not readable by the app or over a plain `adb pull`. The app never reads or exports them: it uses only `ApplicationExitInfo` fields (reason, status, importance, PSS/RSS, description) and never `getTraceInputStream()`. A rooted or bugreport-capable attacker with the unlocked phone is already out of scope (see the threat model).

## Limitations

- A forgotten password cannot be recovered: the DEK exists only sealed under the password (and the optional biometric key). Use Erase, which stops processing and waits for the running job, closes `mind.db`, then deletes the key file, the database (and the legacy ones) and all patient files.
- Face unlock is offered only if the device rates it Class 3 (BIOMETRIC_STRONG); otherwise use fingerprint or the password.
- Adding a new fingerprint or face invalidates the biometric key; unlock with the password and enroll again.
- The password is only as strong as the user's choice; 600k PBKDF2 iterations slow offline guessing but do not stop weak passwords.
