# Brief — memory-01

**Task:** W1-MEMORY  
**Baseline:** `/home/martin/projects/Therapy_local/orchestration/space/integration/ios`  
**Staging:** `/home/martin/projects/Therapy_local/orchestration/agents/memory-01/ios`  
**Status:** ready

## Mission

Deliver grounded memory: local embeddings, strictly extractive facts with verbatim evidence, source-timestamped search, live cues, and a durable processing queue. No speculative output, no cloud calls.

## Owned files

- `Sources/MindVault/Services/EmbeddingService.swift`
- `Sources/MindVault/Services/FactExtractionService.swift`
- `Sources/MindVault/Services/MemoryPipeline.swift`
- `Sources/MindVault/Services/SearchService.swift`
- `Sources/MindVault/Services/LiveCueService.swift`
- New memory/search service files under `Sources/MindVault/Services/`
- New memory tests under `Tests/MindVaultTests/`

## Do not touch

- `SearchRanking`, `FactParser`, `TranscriptWindows` contracts without a proposal
- `VaultController`/Keychain or audio capture internals
- Android source, Gradle, `.github/`, `KANBAN.md`, other agents' trees
- The integration tree; work in your staging copy

## Requirements

- Use `NLEmbedding.sentenceEmbedding` when available; otherwise use `HashingEmbedding`. Report which is active in Settings.
- Audio embeddings are unavailable in the baseline; return `nil` and keep cues text-based.
- Facts must be extractive and quote verbatim evidence. Reject facts without evidence.
- Search returns `SearchResult` with session/chunk IDs and timestamps; enforce `clientID` isolation.
- Live cues are limited, scored, and deduplicated; never cross clients.
- Add a durable job queue path (`JobQueue`) so processing survives app restarts.
- No network calls, no model downloads, no speculative facts.

## Acceptance

- Unit tests cover extraction, duplicate suppression, evidence containment, client isolation, search ranking, and cue limits.
- Search snippets match the stored source text.
- Processing queue persists and resumes safely.
- `make parse-docker` passes; core tests remain green; macOS tests recorded.
- No real patient data.

## Handoff

Use `STATUS_TEMPLATE.md` and `HANDOFF_TEMPLATE.md`. Include test output, embedding mode, and any contract proposals. Request integration through `updates/<TASK-ID>.md`.
