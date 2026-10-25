# Security: patient data at rest

Everything the app stores about patients is encrypted under one data key (DEK) that exists in RAM only while the app is unlocked. Code: `vault/` (`Vault`, `VaultCrypto`, `KeyFile`, `SecureFile`), `memory/MemoryFiles.kt`.

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
| `recordings.db` (transcripts, embeddings) | internal `databases/` | SQLCipher (AES-256), DEK as raw key, incl. journal/WAL |
| `memory.db` (clients, facts, evidence quotes, chunks, observations) | internal `databases/` | SQLCipher (AES-256), same key |
| Session transcripts | internal `files/memory/in/` | SecureFile: magic `TLV1` + AES-256-GCM, AAD = file name, atomic write |
| Job queue (client and session ids, errors) | internal `files/memory/queue.json` | SecureFile |
| Per-chunk extraction cache (facts with quotes) | internal `files/memory/cache/<session>/` | SecureFile |
| Log fields `client` and `session` | `logs/tl.log` | written as an 8-hex HMAC pseudonym keyed by the DEK (`locked` while locked) |
| Foreground notification | lock screen | private visibility; the public version only reads "Building client memory" |

Opening a database or a patient file while locked fails closed (`VaultLockedException`). On every unlock an idempotent migration moves data of older builds: plaintext databases are re-keyed with `sqlcipher_export`, legacy external transcripts and queue become SecureFiles, legacy external diagnostics containing patient text are deleted.

## What is NOT encrypted, and why

| Item | Why |
|---|---|
| Model files and LiteRT caches | Public model weights, no patient data |
| SharedPreferences `settings` | Engine preferences only, no patient data |
| Developer inputs: `prompts/`, `job.json` (external `files/memory/`) | Not patient data; read only by the debug harness |
| Debug-harness diagnostics (`out/bench.jsonl`, `decisions.jsonl`, `raw/`, `DONE`, `ERROR`) | Written only when a run is started from `job.json` (debug harness), never in app mode. Use synthetic transcripts there |
| Debug `LiveCuesBench` output | Debug-build benchmark tool, synthetic input only |

## Limitations

- A forgotten password cannot be recovered: the DEK exists only sealed under the password (and the optional biometric key). Use Erase, which deletes the key file, both databases and all patient files.
- Face unlock is offered only if the device rates it Class 3 (BIOMETRIC_STRONG); otherwise use fingerprint or the password.
- Adding a new fingerprint or face invalidates the biometric key; unlock with the password and enroll again.
- Plaintext files written by older builds are deleted on migration but not securely wiped from flash storage; remnants may be recoverable with forensic tools.
- The password is only as strong as the user's choice; 600k PBKDF2 iterations slow offline guessing but do not stop weak passwords.
