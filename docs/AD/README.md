# Architecture decisions

Each record is immutable once merged (see [`AGENTS.md`](../../AGENTS.md); CI job `ad-immutable`). A decision is changed by a later record that supersedes it, and only this index changes status.

| AD | Decision | Decided | By | Status |
|---|---|---|---|---|
| [AD-01](AD-01.md) | Model inference runs in separate processes (`:live`, `:batch`) | 2026-11-06 15:06 CET | Martin Hajný (@hajnina) | Accepted; its Gemma speech recognition and Whisper mentions are superseded by AD-02 |
| [AD-02](AD-02.md) | One model per role; Canary is the only speech recogniser | 2026-11-06 15:27 CET | Martin Hajný (@hajnina) | Accepted |
