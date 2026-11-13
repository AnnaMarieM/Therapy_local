# Human-voice corpus

Real human recordings of the session scripts. This is the primary test corpus; it carries more weight than the synthetic Piper/Edge TTS sessions in `../sessions/`.

Each client folder holds, for session `sNN`:

- `sNN.m4a`: the recording
- `sNN.txt`: the script as read, verbatim, one `THERAPIST:` / `CLIENT:` line per turn
- `sNN.json`: the same turns as structured data (`client`, `session`, `lang`, `date`, `voice`, `audio`, `turns`)

| client | lang | sessions |
|---|---|---|
| daniel-r | en | s01 |
| hannah-w | en | s01 |
| jana-n | cs | s01 |
| tomas-b | cs | s01 |

Hannah W. exists only here; she is not in `../clients.json`. The other three have a synthetic counterpart in `../sessions/`, but the human scripts are new and longer (about 90-110 turns), so their text differs from the synthetic `s01`.
