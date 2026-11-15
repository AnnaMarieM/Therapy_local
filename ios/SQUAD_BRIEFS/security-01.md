# Brief — security-01

**Task:** W1-SECURITY  
**Baseline:** `/home/martin/projects/Therapy_local/orchestration/space/integration/ios`  
**Staging:** `/home/martin/projects/Therapy_local/orchestration/agents/security-01/ios`  
**Status:** ready

## Mission

Make the local vault match the Android security model: passphrase-derived key-encryption key, sealed data-encryption key, Keychain protection, biometric unlock, explicit lock, and irreversible local erase.

## Owned files

- `Sources/MindVault/Services/VaultController.swift`
- `Sources/MindVault/Services/VaultCrypto.swift`
- `Sources/MindVault/Services/VaultKeyStore.swift`
- `Sources/MindVault/Services/VaultPaths.swift`
- `Sources/MindVault/Services/LocalAuthenticator.swift`
- `Tests/MindVaultTests/VaultCipherTests.swift`
- `Tests/MindVaultTests/EncryptedStoreTests.swift`
- New security-focused test files under `Tests/MindVaultTests/`

## Do not touch

- Core model/persistence APIs without a contract proposal
- Android source, Gradle, `.github/`, `KANBAN.md`, other agents' trees
- The integration tree; work in your staging copy

## Required upgrade

The baseline stores a PBKDF2 verifier and the raw DEK in the Keychain. Replace it with:

1. Random salt + PBKDF2-derived KEK from the passphrase.
2. Random 32-byte DEK sealed with AES-256-GCM under the KEK.
3. Sealed DEK stored in Keychain (`WhenUnlockedThisDeviceOnly`); KEK never persisted.
4. Unlock verifies by decrypting the sealed DEK; no separate raw DEK item.
5. Biometric unlock releases the same sealed DEK only after `LocalAuthentication` success.
6. Lock clears the in-memory key; erase deletes credentials, sealed DEK, vault document, and pending audio.
7. Migration path for the baseline Keychain items, or a documented one-time reset if migration is unsafe.

## Acceptance

- Wrong passphrase and tampered sealed-DEK data fail closed.
- No plaintext DEK or PHI is written to disk, logs, notifications, or filenames.
- Keychain items are non-synchronizable and `WhenUnlockedThisDeviceOnly`.
- Lock/erase tests pass; client deletion removes pending audio.
- `make parse-docker` passes; macOS unit tests pass (record the command and output).
- Document any remaining risk in `HANDOFF.md`.

## Handoff

Use `STATUS_TEMPLATE.md` and `HANDOFF_TEMPLATE.md`. Include test output, migration notes, and the exact files changed. Request integration through `updates/<TASK-ID>.md`.
