# Integration: MindVault UI on the on-device pipeline

How the UI is wired to the memory pipeline. The pipeline itself (database, job graph, live cues, decisions) is described in [MEMORY.md](MEMORY.md).

## Architecture
```
MainActivity -> MindVault (ui/app/)  -- Gate (welcome, permissions, password, biometrics, unlock), then Home with sheets:
   |                                    Profile, Session, Settings, Add client
   |-- ClientsViewModel -> ClientRepository (ui/data/) -> MindStore (mind.db)     clients, sessions, facts, mentions, segments, search
   |-- MainViewModel: setup phase, search (text and held-mic audio)
   |-- SessionController (session/, process-wide): start/pause/resume/finish, ChunkRecorder -> chunks rows + PendingAudio,
   |      live cues (LiveCues), mic level; RecordingService (microphone foreground service) keeps it alive in the background
   `-- JobGraph.enqueueRecording -> JobQueue (jobs/) -> MemoryService -> JobRunner -> Pipeline (facts/: TranscriptWindows, CommitPlanner)
                                    progress in Jobs.snapshot (QueueSnapshot)
```
The UI never touches SQL; everything goes through `ClientRepository`. The developer harness (Record/Recordings/Memory/Logs tabs, `memory_lab` intent extra, `MainViewModel.skipPrepare`) is kept in `MainActivity`. It opens from Settings -> Developer tools in debug builds only; a `memory_lab` launch opens it directly (see MEMORY.md, Developer harness).

## Interaction model
Home is always underneath; every other surface is a sheet that grows out of the control that opened it and folds back into it
(`ContainerTransform`, one interruptible spring per sheet, also driven by predictive back and by pulling down from the top).
Every swipe has a tap or accessibility equivalent: the two bar buttons are tappable, the hold-to-finish has a "Finish session" action.
Gesture rules live in plain Kotlin with unit tests (`ui/motion/GestureMath`, `ui/logic/ReelLogic`). Design tokens (palette, type,
springs, haptics) are in `ui/design/`.

## Data flow
- Home (the reel): `ClientRepository.patients()` in alphabetical order (`ReelIndex`). Up/down scrolls the names; the letter rail jumps
  to the letter under the finger. Under the selected name: recording now, processing stage (`SessionLogic.processingByClient`), a failed
  session with Retry (`JobQueue.retryFailed`), or the session count. The last item adds a client: first name plus the surname's initial,
  id a unique slug of the name (`ClientInput`). Long press deletes (drops its jobs, deletes pending audio, removes the row; the rest
  cascades).
- Profile: one page: search (`ClientRepository.search`), facts (heading, content, mention count), sessions (newest first). Opening a fact
  shows every occurrence with the transcript around it (`occurrences`); opening a session shows its transcript (`transcript`).
  Long press on a session deletes it (`ClientRepository.deleteSession`: its jobs, audio and rows; facts first seen in it move to their
  next mention, D2).
- Session: Record starts recording at once (`SessionController.start`, which creates the `sessions` and `recordings` rows and starts
  `RecordingService`); live cues start too once the embedder is ready (`enableCues` if setup finishes mid-session). A start that fails
  (no microphone, no storage) rolls back its rows and shows why. Pause and resume are `ChunkRecorder.isPaused`; elapsed time is a
  monotonic `SessionClock`. There is no live transcript (D1): the screen shows the top live cue, held for at least 8 s unless clearly
  overtaken (`CueView`), with the fact, its source segment on request (`ClientRepository.sourceChunk`) and the next two. Minimising
  keeps recording; the pill on Home and the notification open it again.
- Finish (hold): `SessionController.finish` stops cues and the recorder. Once the last chunk is saved the recording is queued
  (`JobGraph.enqueueRecording`) and `JobRunner.recordingActive` is cleared, so waiting jobs run (D3). Until then the client shows
  "Finalizing recording...". A notice offers Discard, which deletes the session.
- Lock: Settings -> Lock now asks for confirmation while a job is running; processing pauses and continues after unlock.
- Privacy: `FLAG_SECURE` keeps every screen out of screenshots and the recents preview; notifications never name a client.

## Mock vs real
Real: everything above, including biometric unlock, encrypted storage, model choice and the live-cue settings.
Not built: consent capture (the old static "Consent confirmed" badge and the unconnected "Show past facts" toggle were removed).

## Open TODOs
- Edit/rename client.
- Consent capture.
- Dismissing or marking cues as covered during a session (waiting on design).
- Dark theme; auto-lock after a period in the background.
