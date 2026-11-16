# iOS port plan (lane E1, issue #125)

Working notes for AD-23 (draft, `docs/AD/AD-23.md`). Not a decision record — AD-23 is the decision; this is the plan behind it. Statuses here are lane cards on the tracker, not merged facts.

## Phases

```text
A1 baseline landed (1f70fa9)
      │
      ├── A2  iOS CI green (issue #121)          ios.yml on macos-15: XcodeGen generate,
      │                                          simulator + unsigned device build, unit tests;
      │                                          Linux job: every ios/Packages/* in swift:6.3
      │
      ├── B1  MindVaultASR   (issue #122)        whisper.cpp via Metal + model download
      ├── C1  MindVaultLLM   (issue #123)        Gemma via llama.cpp (Metal) + extraction/summary
      ├── D1  MindVaultPerception (issue #124)   diarization (ONNX) + text embeddings
      │            (B1/C1/D1 in parallel, all on A1)
      │
      └── B2/C2/D2  wire into app (issue #126)   replace the placeholder implementations
                   │                             (SFSpeechRecognizer, NLEmbedding, the
                   │                             deterministic fact-extraction placeholder)
                   │                             with the engines per INTEGRATION.md; where
                   │                             CONTRACTS.md pins the replaced behavior
                   │                             (FactExtracting is deterministic, :119; one
                   │                             embedding model, :118) the wiring lane
                   │                             amends the contract first; model download
                   │                             UI per ModelDownloadWorker semantics
                   │
                   └── R1  parity release (issue #127)  person-verified end-to-end session on an
                         iPhone → enable ios-release.yml → rolling ios-main
```

- Branches: each lane a worktree on `origin/ios`, PR back to `ios`, never merges itself (`ios/KANBAN.md`).
- Release gate: R1 only. The release workflow is drafted and disabled; enabling it is R1's deliverable.
- Out of v1 scope unless CONTRACTS.md requires it: AD-01 process split, AD-04 quiet exit, AD-05 pinned look, AD-16 QA API.

## Models: Android pinned files (reference) and the iOS equivalents

The Android app is the fidelity reference; the iOS app downloads its own files, pinned by sha256, never committed.

| Role | Android (ModelSpec.kt, pinned) | iOS stack | iOS HF source | Size |
|---|---|---|---|---|
| Memory LLM | Gemma 4 E2B `litertlm` 2.59 GB / E4B 3.66 GB (`litert-community`) | llama.cpp, Metal, GGUF | `ggml-org/gemma-4-E2B-it-GGUF` Q4_0 2.84 GB, Q8_0 4.97 GB; E4B Q4_0 4.59 GB | 2.4-5 GB |
| ASR (Quality) | Whisper large-v3 q8_0 1.66 GB (no host URL yet — `adb push` by hand) | whisper.cpp, Metal, ggml | `ggerganov/whisper.cpp`: large-v3 f16 3.10 GB, q5_0 1.08 GB, turbo q8_0 0.87 GB | 0.9-3.1 GB |
| ASR (Canary) | Canary 1B v2 int8, sherpa-onnx, 1.41 GB (`fussraider/canary-1b-v2-sherpa-onnx-int8`) | — no iOS equivalent yet (Canary is ONNX; an iOS port would need onnxruntime, not planned in v1) | — | — |
| Embedder (text+audio) | EmbeddingGemma 2 740M 485 MB (`litert-community`) | text v1: NLEmbedding 512-d (en) + hashing fallback (different vector space than Android's 768-d — never mixed in one index); EmbeddingGemma ONNX (166 MB q4) is a wiring follow-up; **audio: no iOS equivalent** | HF `onnx-community/embeddinggemma-2-ONNX` `onnx/model_q4.onnx`, verified by D1 research | 0.5 GB (later) |
| Diarization | pyannote segmentation-3.0 (5.99 MB) + WeSpeaker ResNet34 (26.5 MB), ONNX | ONNX Runtime / sherpa-onnx — **same files as Android** | `csukuangfj/sherpa-onnx-pyannote-segmentation-3-0` + `k2-fsa/sherpa-onnx` release | 32 MB |

Notes:
- The GGUF variant/params to use are the ones validated in `bench/` and `research/asr-llm-combo/README.md` (e2b/e4b are the ggml-org Q4_0/Q8_0 builds; `bench/bench.py` pins repo and file).
- One model per role carries over (AD-02): a session needs at most two large files (ASR + LLM) plus the embedder and the diarizer.
- Download rule: pinned sha256 per file, ask before downloading on metered connections, show progress — mirror `ModelDownloadWorker` semantics (lane B2/C2/D2).

## Unsigned IPA + sideload recipe

CI builds an unsigned `.ipa` on macOS runners (no signing identity needed). It does not run as-is; the person installing signs it with their own Apple ID.

**AltStore Classic 2.2+ (primary — over Wi-Fi or cable):**
1. Install AltServer (macOS 1.7.3 / Windows 1.7.4) on the computer and sign in with the Apple ID.
   Windows: the web downloads of iTunes + iCloud from Apple's site, not the Microsoft Store versions.
2. iPhone and computer on the same network (or cable); install AltStore on the iPhone via AltServer.
   The Apple ID must have been signed in on a device before, or AltServer fails with error -22411.
3. In AltStore on the phone: `+` → pick `MnemoThera-ios-unsigned.ipa`; AltStore strips the unsigned
   placeholder signature and re-signs with the Apple ID, then installs.
4. First launch: Settings → General → VPN & Device Management → trust the developer profile.
5. AltStore re-signs over Wi-Fi in the background before the 7 days expire (AltServer running).

Why AltStore first: it is the only sideloader that carries the `increased-memory-limit` entitlement
request through re-signing, which the multi-GB models need (free-account cap without it is near
~3.4 GB; with it 6-8 GB). Depends on an A2 follow-up (issue #121): CI must ad-hoc-sign the IPA with
an entitlements plist carrying the request before zipping — until that lands, installs work but
large-model loading may hit the memory cap.

**Sideloadly (fallback, by cable):** connect the iPhone, drop the IPA, enter the Apple ID, sign,
install (same trust step). It does not preserve the entitlement request; fine for smoke installs.

**Verify before installing:** the release carries `MnemoThera-ios-unsigned.ipa.sha256`.
macOS: `shasum -a 256 -c MnemoThera-ios-unsigned.ipa.sha256`; Linux/Windows: `sha256sum -c …`.

**Limits with a free Apple ID:** signature valid **7 days** (AltStore re-signs over Wi-Fi;
Sideloadly by cable); at most **3** sideloaded apps per Apple ID; at most **10** new App IDs per
7 days; entitlements that need a paid profile are not available (push notifications, iCloud,
extended background modes).

**Simulator:** `MnemoThera-ios-sim.zip` is for a Mac: unzip and `xcrun simctl install booted <app>`.
It is a **UI smoke build** — the on-device engines (Metal whisper/llama) are verified at R1, not by
this zip.

**When an Apple Developer account arrives (what changes):**
- TestFlight becomes the install path: `fastlane pilot upload` or `xcrun altool` with an **App Store Connect API key** (ASC key, not an Apple ID password); 90-day builds, up to 10k testers, no 7-day limit.
- The build gets a real distribution provisioning profile; entitlements beyond the free profile become available (some background modes, push for processing notifications).
- The unsigned rolling `ios-main` release can stay as the QA channel (fastest path, no TestFlight processing) or be retired; that is R1/Martin's call.

**Open follow-ups (tracked on the cards, not here):**
- AltStore `source.json` for in-app update notifications — fields must match Info.plist exactly (bundle id `com.therapylocal.app`, version, size in bytes) and `appPermissions` must list every entitlement + privacy usage description, or AltStore refuses to install (R1).
- A paid Apple Developer account ($99/yr) is the eventual proper channel; ad-hoc distribution is the middle option — Martin's call, not v1.
- The repo then holds an ASC key or a `.p12` — store it in GitHub Actions secrets only; AD-23 point 6 says no Apple secrets are committed.

## Risks

| Risk | Why it matters | Mitigation |
|---|---|---|
| Xcode-only compile debt | The app target has never been compiled by Xcode (Linux syntax-parse only); SwiftUI/AVFoundation/Info.plist errors surface only on a macOS runner | Lane A2 exists exactly for this; expect a fix-iterate loop on the first green build (`ios/KANBAN.md` DoD) |
| Metal memory limits on older iPhones | The Gemma GGUF is a 2.4-5 GB download; an iPhone with 6 GB RAM must keep the model, the vault and the OS under its per-app cap (Android learned this the hard way: D3 pauses the runner while recording) | Default to E2B Q4_0; measure free memory at load like `BackendLadder`; fail closed to CPU or refuse with a clear message; document per-device expectations in Settings |
| Audio-embedding gap | EmbeddingGemma's audio encoder has no iOS equivalent; live cues and voice search may be text-only at first (CONTRACTS.md already allows `embedAudio` to return `nil` in the baseline) | Ship text-only live cues; Settings must show the capability gap (CONTRACTS.md UI rule); revisit when an audio encoder ships for iOS |
| Diarization accuracy | pyannote+WeSpeaker ONNX is the same model as Android, but the runtime (onnxruntime-swift vs sherpa-onnx Android) may differ in speed and memory; turns packing is new code | Lane D1: golden tests on turns packing and VectorMath; compare speaker maps against Android exports on the same recordings before R1 |
| No Apple account = no device testing from CI | CI can build and test on simulators only; nothing in CI can run on a real iPhone, so R1's parity proof is manual by construction | R1 is explicitly person-verified; keep a device test checklist in the R1 card |
| 7-day resign churn | A QA install that expired mid-review breaks a session | AltStore resign-over-Wi-Fi as the default path; re-sign before every device sitting |
| Broken local Swift toolchain | The checked-in Linux toolchain lacks libxml2; local `swift test` fails confusingly | Always test through docker (`swift:6.3`) exactly as CI does; CI runs the container as the workspace user so `.build` never becomes root-owned |
| Branch namespace | The branch `ios` occupies the `ios/*` ref namespace: no `ios/*` branch can exist | Lane branches are flat (`ios-asr-whisper`, not `ios/asr`) — `ios/KANBAN.md` |
| Runner capacity | macOS arm64 queue warnings appear; 45-min timeout is comfortable at ~11 min but grows with the port | Watch job minutes; split jobs before they near the timeout |
| Agent quota outages | Free-usage limits killed lane agents mid-task more than once | Lane state lives in worktrees + cards, not in the agent's head; relaunch from the card |
| Artifact-contract drift | The release lane downloads artifacts by exact name; a rename on the CI side silently breaks the first publish | Names kept in lockstep (found in review); any change to either file diffs both in review |

## Draft: "iOS" section for `ios/README.md` (for whoever lands it — this file does not edit the README)

```markdown
## Release

There is no App Store or TestFlight build yet: the repository has no Apple Developer
account. CI can build an unsigned IPA, but publishing it is gated behind R1
(parity checklist in `KANBAN.md`): nothing is released before a person has run a
session end to end on an iPhone.

When the rolling release is live, it mirrors the Android one (`Therapy-main.apk`):
the prerelease `ios-main` on GitHub releases holds `MnemoThera-ios-unsigned.ipa`
with its `.sha256` and `MnemoThera-ios-sim.zip` (a UI smoke build), replaced on
every publish. The IPA is unsigned: sign it with your own Apple ID — AltStore
Classic 2.2+ first (it carries the memory entitlement the models need),
Sideloadly as fallback; a free Apple ID gives 7 days and at most 3 sideloaded
apps. Verify the download with `shasum -a 256 -c` (macOS) or `sha256sum -c`.
See `scratchpad/ios/PLAN.md` for the recipe.
```

