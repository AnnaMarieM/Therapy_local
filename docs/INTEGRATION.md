# Integration: MindVault UI on the on-device pipeline

How the UI is wired to the memory pipeline. The pipeline itself (database, job graph, live cues, decisions) is described in [MEMORY.md](MEMORY.md).

## Architecture
```
MainActivity -> MindVaultApp (ui/)  -- screens: Unlock, Patients, Profile, LiveSession, Summary, Settings
   |-- ClientsViewModel -> ClientRepository (ui/data/) -> MindStore (mind.db)     clients, sessions, facts, mentions, segments, search
   |-- MainViewModel: startRecording/stopRecording/pause/resume (ChunkRecorder -> chunks rows + PendingAudio), liveCues (LiveCues)
   `-- JobGraph.enqueueRecording -> JobQueue (jobs/) -> MemoryService -> JobRunner -> Pipeline (facts/: TranscriptWindows, CommitPlanner)
                                    progress in Jobs.snapshot (QueueSnapshot)
```
The UI never touches SQL; everything goes through `ClientRepository`. The developer harness (Record/Recordings/Memory/Logs tabs, `memory_lab` intent extra, `MainViewModel.skipPrepare`) is kept in `MainActivity`. It opens from Settings -> Developer tools in debug builds only; a `memory_lab` launch opens it directly (see MEMORY.md, Developer harness).

## Data flow
- Patients/Profile: `ClientRepository.patients()` maps `MindStore` rows to the UI `Patient` model: sessions (newest first, with their transcript length) and facts (heading, content, date of first occurrence, mention count). The profile has Overview, Sessions and Facts tabs and a search field (`ClientRepository.search`). Opening a fact shows every occurrence with the transcript around it (`occurrences`); opening a session shows its transcript (`transcript`). Client id = a lowercase slug of the name, made unique (`ClientInput`). Add patient creates a `clients` row; delete (profile screen or long press in the list) drops the client's jobs, deletes its pending audio and removes the row, everything else cascading.
- Live session: Start begins paused; the first press of the record button calls `liveCues.start(client)` (once the models are ready) and `startRecording(client, session)`, which creates the `sessions` and `recordings` rows. Pause and resume are `ChunkRecorder.isPaused`. Elapsed time counts only while recording and not paused. There is no live transcript (D1); the screen shows the live cues, each a fact heading with its score, expanding to the fact and its source segment (`ClientRepository.sourceChunk`).
- End session: `liveCues.stop()`, `stopRecording()`. Once the last chunk is saved, `MainViewModel` queues the recording (`JobGraph.enqueueRecording`) and clears `JobRunner.recordingActive`, so waiting jobs run (D3). Until then the patient list shows the session as "Finalizing recording...". The Summary screen saves (returns to the list) or deletes the session (`ClientRepository.deleteSession`: its jobs, audio and rows; facts first seen in it move to their next mention, D2). Processing progress per client comes from `SessionLogic.processingByClient(Jobs.snapshot)`.
- Lock: Settings -> Lock asks for confirmation while a job is running; processing pauses and continues after unlock.

## Mock vs real
Real: client list, create/delete, facts and mentions, sessions and transcripts, search (text and held-mic audio), recording with pause, live cues and their settings, model choice, background processing, session delete, biometric unlock, encrypted storage.
Mock: the "Consent confirmed" badge (static text, no consent capture) and the "Show past facts" toggle (not saved, live cues always run).

## Open TODOs
- Pressing record and then End within about a second can miss the stop (the recorder is created asynchronously, `stopRecording` does nothing until then).
- Edit/rename client.
- Consent capture behind the "Consent confirmed" badge.
