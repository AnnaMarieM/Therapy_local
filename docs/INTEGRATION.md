# Integration: MindVault UI on the on-device pipeline

## Architecture
```
MainActivity -> MindVaultApp (ui/)  -- screens: Unlock, Patients, Profile, LiveSession, Summary, Settings
   |-- ClientsViewModel -> ClientRepository (ui/data/) -> MemoryStore (memory.db)      clients, sessions, facts, source chunks, recurrence
   |-- MainViewModel: startRecording/stopRecording (ChunkRecorder -> Whisper -> Embedder -> Store), liveCues (LiveCues)
   `-- MemoryProcessor.enqueueSession -> MemoryService/MemoryLab -> MemoryStore;  progress in MemoryQueue.state
```
The developer harness (Record/Recordings/Search/Memory/Logs tabs, `memory_lab` intent extra, `MainViewModel.skipPrepare`) is kept in `MainActivity`. It opens from Settings -> Diagnostics in debug builds only; a `memory_lab` launch opens it directly.

## Data flow
- Patients/Profile: `ClientRepository.patients()` maps `MemoryStore` rows to the UI `Patient` model (facts, sessions with their facts as summary, recurring facts shown as "(xN)"). Client id = lowercase slug without underscores (`MemoryProcessor.clientId`). Add patient creates a `clients` row; delete (profile screen or long press in the list) removes chunks, facts, supports, sessions and the row in one transaction and drops the queue jobs.
- Live session: once the models are ready, `liveCues.start(client)` then `startRecording()`. Elapsed time counts while recording. Transcript lines come from the stored chunks of the active recording. Cue rows (category, fact, score bar) expand to the source chunk (`ClientRepository.sourceChunk`).
- End session: `liveCues.stop()`, `stopRecording()`. When the pipeline has finished (`recording == false`, `status == "Done"`) the merged transcript goes to `MemoryProcessor.enqueueSession`, then the raw recording rows are deleted. Summary shows the queue stage, the facts of that session as they are committed, and the therapist's observations (`session_meta.observations`). Delete removes the session's chunks, facts, supports, observations, queued job and transcript file.

## Mock vs real
Real: client list, create/delete, facts, sessions, recurring counts, recording, live transcript, live cues, end-of-session processing, summary facts, observations, session delete.
Mock: the "Consent confirmed" and "100% On-device" badges (static text, no consent capture), Settings toggles (biometrics, auto-surface), pre-session note (first facts of the last session, not a generated briefing), `Patient.age`/`presenting` (from the add form or a goal/emotion fact).

## Open TODOs
- Pause: the pause button was removed; `ChunkRecorder` has no pause.
- Pressing End within about a second of starting can miss the stop (the recorder is created asynchronously).
- Leaving the app mid-session does not end the session; a process death loses the pending end-of-session handoff.
- Delete is disabled while a session's job is running; there is no cancel for a single job.
- Edit/rename client; persisted pre-session notes; encrypted storage (SQLCipher) is still outstanding.
- Tests of `MemoryStore` queries and `deleteSession` need an instrumented or Robolectric setup (not done).
