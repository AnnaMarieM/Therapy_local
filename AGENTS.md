# Notes for agents working in this repository

## Architecture decisions: `docs/AD/`

Decisions that shape the architecture are recorded in [`docs/AD/`](docs/AD/), one file per decision: `AD-01.md`, `AD-02.md`, …

- **Read them before** changing how models run, where data lives, the vault, the job queue, or how the app is split into processes. A change that goes against an accepted decision needs a new decision that supersedes it. Do not change the code quietly.
- **Record a new decision** when a choice is hard to reverse or affects several modules: a new process, a new runtime, a storage or security model, a threading model.
- **Every decision states when it was made and by whom**, in a header table at the top:

  | | |
  |---|---|
  | Status | Proposed / Accepted / Superseded by AD-xx |
  | Decided | `YYYY-MM-DD HH:MM` with time zone |
  | Decided by | the person's name and GitHub handle, e.g. `Martin Hajný (@hajnina)` |

  Take the person from GitHub: `gh api user -q '.login + " " + .name'` for whoever approves it in the session, or the author of the PR or commit that accepts it (`gh pr view <n> --json author`, `git log -1 --format='%an %ad'`). An agent drafts a decision; a person decides it. If it is not decided yet, the status is `Proposed` and `Decided` is left open.
- **Sections:** Context (the facts from the code that force the choice), Decision, Alternatives considered (and why rejected), Consequences (better, worse and accepted, still true), Verification, Delivery.
- **Link it** from the short decision list in the relevant doc (for example D6 in [`docs/MEMORY.md`](docs/MEMORY.md#decisions) points to AD-01). Do not edit an accepted decision in substance; add a later one that supersedes it.
