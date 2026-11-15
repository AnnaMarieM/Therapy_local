# iOS Port — Kanban

The live board is the issue tracker of `AnnaMarieM/Therapy_local` with state labels
(`kanban:todo` → `kanban:doing` → `kanban:review` → `kanban:done`); this file is the
lane map and the rules. Column state moves with `gh issue edit <n> --add-label/--remove-label`.

Branches: each lane works in its own git worktree on a branch off `origin/ios`
(`ios/<lane>`), opens a PR back to `ios`, and **never merges itself** — the coordinator
merges after review (Martin decides). The CI lane (A) is the exception: it pushes
directly to `ios`, which is the integration branch for the port.

## Rules for every lane

- Scope is files, not ideas: a lane may touch only the paths listed in its card.
- Pure logic must build and test on Linux (SwiftPM, no Apple-only imports outside
  `#if os(iOS)`/`canImport(Metal)` guards) — CONTRACTS.md is law.
- Model files are never committed; download URLs carry a pinned sha256 like
  `app/build.gradle.kts` does for sherpa-onnx.
- No AI attribution in commits or PRs (AGENTS.md). English + Czech UI parity later;
  do not invent copy — screen wording belongs to the UI owner.

## Lanes

| Card | Issue | Lane | Owns (files only) | Depends on |
|---|---|---|---|---|
| A1 Land baseline | done | coordinator | `ios/**` initial landing | — |
| A2 iOS CI green | issue | ci | `.github/workflows/ios.yml`, `ios/Sources/**`, `ios/project.yml`, `ios/Tests/**` | A1 |
| B1 ASR package | issue | asr | `ios/Packages/MindVaultASR/**` | A1 |
| C1 LLM package | issue | llm | `ios/Packages/MindVaultLLM/**` | A1 |
| D1 Perception package | issue | perce | `ios/Packages/MindVaultPerception/**` | A1 |
| E1 Release + AD-23 | issue | docs | `docs/AD/AD-23.md`, `.github/workflows/ios-release.yml`, `scratchpad/ios/**` | A1 |
| B2/C2/D2 Wire into app | issue | ci | `ios/Sources/**` | A2 green + B1/C1/D1 merged |
| R1 Parity release | issue | coordinator | release job enablement, `ios/README.md` | B2/C2/D2, parity checklist E1 |

## Definition of done per lane

- **A2**: `ios.yml` green on `macos-*` — XcodeGen project generates, simulator build
  succeeds, device build (unsigned) succeeds, `MindVaultCore` + app unit tests pass;
  plus a Linux job running every `ios/Packages/*` core test in the `swift:6.3` image.
- **B1/C1/D1**: package builds and tests on Linux AND compiles for iOS in CI; vendored
  or SwiftPM-pinned third-party code is pinned by commit/tag + sha256; `INTEGRATION.md`
  in each package states exactly how the app will call it.
- **E1**: AD-23 drafted as *Proposed* (a person must accept it), unsigned-IPA recipe
  documented, release workflow drafted but disabled until R1's parity checklist passes.
- **R1**: whisper + Gemma + embeddings actually run on an iPhone (a person has run a
  session end to end), THEN the rolling `ios-main` release goes live mirroring
  `Therapy-main.apk` (unsigned IPA + simulator zip + sha256).

Parity is measured against the Android app (AD-01 process split, AD-04 quiet exit,
AD-05 pinned look, AD-16 QA API are out of scope for v1 except where CONTRACTS.md
requires the behavior).
