# Notes for agents working in this repository

## Architecture decisions: `docs/AD/`

Decisions that shape the architecture are recorded in [`docs/AD/`](docs/AD/), one file per decision: `AD-01.md`, `AD-02.md`, … The index [`docs/AD/README.md`](docs/AD/README.md) lists every decision and whether it still holds.

- **Read them before** changing how models run, where data lives, the vault, the job queue, or how the app is split into processes. A change that goes against an accepted decision needs a new decision that supersedes it. Do not change the code quietly.
- **Record a new decision** when a choice is hard to reverse or affects several modules: a new process, a new runtime, a storage or security model, a threading model, which models the app uses.
- **A merged decision is untouchable.** Once an `AD-xx.md` is on `main`, it is never edited, overwritten, renamed or deleted, not even for a typo or a status change. CI enforces this: the `ad-immutable` job (`scripts/check-ad-immutable.sh`) fails any PR or push that modifies, deletes or renames an existing `docs/AD/AD-*.md`. Do not work around the check. Until it is merged, a decision can still be edited in its own PR.
- **To change or reverse a decision**, add a new file with the next number. Name the old one in its header (`Supersedes | AD-yy`), say what changes and why, and mark the old one as superseded in the index `docs/AD/README.md`. The index is the only place where status changes; the old record stays as it was.
- **Merge only decided records.** A decision is merged once a person has accepted it. A proposal stays in its PR until then.
- **Every decision states when it was made and by whom**, in a header table at the top:

  | | |
  |---|---|
  | Status | Accepted |
  | Decided | `YYYY-MM-DD HH:MM` with time zone |
  | Decided by | the person's name and GitHub handle, e.g. `Martin Hajný (@hajnina)` |
  | Supersedes | `AD-yy`, only when it replaces an earlier decision |

  Take the person from GitHub: `gh api user -q '.login + " " + .name'` for whoever approves it in the session, or the author of the PR or commit that accepts it (`gh pr view <n> --json author`, `git log -1 --format='%an %ad'`). An agent drafts a decision; a person decides it.
- **Sections:** Context (the facts from the code that force the choice), Decision, Alternatives considered (and why rejected), Consequences (better, worse and accepted, still true), Verification, Delivery.
- **Link it** from the index and from the short decision list in the relevant doc (for example D6 in [`docs/MEMORY.md`](docs/MEMORY.md#decisions) points to AD-01).
