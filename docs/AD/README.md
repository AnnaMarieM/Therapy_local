# Architecture decisions

Each record is immutable once merged (see [`AGENTS.md`](../../AGENTS.md); CI job `ad-immutable`). A decision is changed by a later record that supersedes it, and only this index changes status.

| AD | Decision | Decided | By | Status |
|---|---|---|---|---|
| [AD-01](AD-01.md) | Model inference runs in separate processes (`:live`, `:batch`) | 2026-11-06 15:06 CET | Martin Hajný (@hajnina) | Accepted; its Gemma speech recognition and Whisper mentions are superseded by AD-02 |
| [AD-02](AD-02.md) | One model per role; Canary is the only speech recogniser | 2026-11-06 15:27 CET | Martin Hajný (@hajnina) | Accepted; point 6 (the migration) superseded by AD-03 |
| [AD-03](AD-03.md) | No speech migration; old speech models are wiped by hand | 2026-11-06 16:47 CET | Martin Hajný (@hajnina) | Accepted |
| [AD-04](AD-04.md) | Engine hosts exit quietly on a crash (native signal handler, Java crash halt); C via the NDK | 2026-11-06 22:20 CET | Martin Hajný (@hajnina) | Accepted |
| [AD-05](AD-05.md) | One pinned look: bundled static fonts, pinned font scale, weight and density, explicit colours, deterministic breakpoints | 2026-11-06 23:40 CET | Martin Hajný (@hajnina) | Accepted |
| [AD-06](AD-06.md) | Speaker diarization is a model role: pyannote segmentation plus a WeSpeaker embedding in `:batch`; diarize first, then transcribe per turn | 2026-11-08 23:54 CET | Martin Hajný (@hajnina) | Accepted |
| [AD-07](AD-07.md) | The diarizer always looks for exactly two speakers (therapist and patient) | 2026-11-09 00:12 CET | Martin Hajný (@hajnina) | Accepted |
| [AD-08](AD-08.md) | The GPU and NPU are tried every time: no free-memory gate; only a missing runtime library or the guard skips a backend | 2026-11-11 00:01 CET | Martin Hajný (@hajnina) | Accepted |
| [AD-09](AD-09.md) | The NPU runs only on SoCs validated on our own hardware, through the model catalogue; otherwise GPU, then CPU | 2026-11-07 02:08 CET | Martin Hajný (@hajnina) | Accepted; phone verification pending |
| [AD-10](AD-10.md) | Immutable versioned inputs (transcript by hash, audio by hash, speaker map) and one provenance mechanism with a stored stale flag on every derived item; one vault key, one database | 2026-11-11 16:50 CET | Martin Hajný (@hajnina) | Accepted |
| [AD-11](AD-11.md) | No migrations and no compatibility code before the first release; a database of another schema version is wiped | 2026-11-11 16:50 CET | Martin Hajný (@hajnina) | Accepted |
