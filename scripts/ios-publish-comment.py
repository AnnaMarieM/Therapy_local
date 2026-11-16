#!/usr/bin/env python3
"""Builds the sticky iOS install pull request comment from the facts the ios.yml jobs gathered.

  ios-publish-comment.py --out comment.md

All input comes from environment variables (the workflow's step env). Three shapes, one marker each:

- publish ok:   the SideStore route — one-time setup state, source URL, vault rules, direct-IPA
                fallback, device checklist, QR.
- publish failed or skipped: the links are stale, what to look at.

Every shape ends with the GitHub Pages status (never faked: a Pages gap is stated with its manual
fix). There are deliberately NO Xcode/Keychain/OTA instructions anywhere: no computer exists in this
project, CI does not sign, and the itms-services path is gone. The comment marker is still
`<!-- ios-ota -->` — the legacy name keeps the sticky update finding the comment the first run posted.
GitHub's comment cap is 65,536 characters; this stays far under it.
"""
import argparse
import os

MARKER = "<!-- ios-ota -->"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    o = ap.parse_args()
    g = os.environ.get

    pr = g("PR", "")
    repo = g("GH_REPO", "")
    head = g("HEAD_SHA", "")
    short = head[:7] if head else "unknown"
    run_url = g("RUN_URL", "")
    version = g("VERSION", "")
    publish_result = g("PUBLISH_RESULT", "")
    pages_ok = g("PAGES_OK", "")
    pages_reason = g("PAGES_REASON", "").strip()

    owner, name = (repo.split("/") + [""])[:2]
    pages_base = f"https://{owner.lower()}.github.io/{name}"
    landing_url = f"{pages_base}/"
    source_url = f"{pages_base}/sidestore.json"
    release_url = f"https://github.com/{repo}/releases/tag/ios-pr-{pr}"
    unsigned_url = f"https://github.com/{repo}/releases/download/ios-pr-{pr}/MnemoThera-ios-unsigned.ipa"
    sha_url = f"https://github.com/{repo}/releases/download/ios-pr-{pr}/MnemoThera-ios-unsigned.ipa.sha256"
    qr_url = f"https://github.com/{repo}/releases/download/ios-pr-{pr}/MnemoThera-ios-source-qr.png"

    L = [MARKER, ""]

    if publish_result == "failure":
        L += [
            "## iOS delivery lane failed",
            "",
            f"ios-publish failed in [this run]({run_url}) — links from earlier comments are stale. The release [ios-pr-{pr}]({release_url}) may be missing or half-published; the next run rebuilds it whole.",
            "",
        ]
    elif publish_result == "skipped":
        L += [
            "## iOS delivery lane did not run",
            "",
            f"The build lane (macos-build) failed in [this run]({run_url}), so ios-publish was skipped. No new build this time; earlier comments' links are as stale as their commit.",
            "",
        ]
    else:
        L += [
            "## Install MindVault on your iPhone — no computer",
            "",
            f"Build of #{pr} at `{short}` — **version {version or '?'}** (stamp `0.<PR>.<run>`; every push bumps it and SideStore offers the update).",
            "",
            "These instructions supersede the earlier install instructions in this comment: the one-tap OTA path is gone — free-account OTA needs a provisioning profile that only a Mac can mint, and this project has no computer. The delivery is **SideStore with a rolling source**, entirely on the phone.",
            "",
            "### One-time setup (the honest state)",
            "",
            "- On iOS 26.x the fully on-device SideStore bootstrap (\"siboot\") is **not available yet** — it needs **iOS 27+**. The current no-computer path is: update the phone to iOS 27+, then run the siboot route (SideInstaller + siboot). Verified step-by-step instructions will land in a later comment here.",
            "- That one-time bootstrap is **technical** — shell commands, Developer Mode, transfer codes, Apple-ID 2FA flows; budget **30–60 minutes**, it is not mum-easy. After it, every install and refresh is 1–2 taps.",
            "- Do **not** use an enterprise-cert \"direct install\" of SideStore on iOS 26.x: it installs and signs in but is inert at the first real app install — its pairing file needs a PC.",
            "- SideStore sign-in currently needs **anisette overrides** (Apple's October 2026 authentication waves) — the verified steps will cover it.",
            "",
            "### Then",
            "",
            "1. Create a **burner Apple ID** — a fresh free ID used only for this.",
            "2. Log the burner in inside SideStore.",
            f"3. Add the source: `{source_url}` (landing page: {landing_url} — or scan the QR below in Safari).",
            "4. MindVault → **Install**.",
            "",
            "### The vault rules — read before the first install",
            "",
            "- The **first install must be under the burner Apple ID that owns all future refreshes**. Installing under a different ID/team later forces a delete-first reinstall — the keychain is team-prefixed, so **the vault is lost**.",
            "- A same-team refresh only renews the provisioning profile — safe; data stays.",
            "- If SideStore offers to \"revoke and create a new certificate\", **never accept from inside the app** — that tap deletes the certificate.",
            "- SideStore major updates have had data-eating migrations before — keep it updated via its own source, cautiously.",
            "- Refreshes need **Wi-Fi and an unlocked phone**, periodically.",
            "",
            "### Direct-IPA fallback",
            "",
            f"[MnemoThera-ios-unsigned.ipa]({unsigned_url}) from release [ios-pr-{pr}]({release_url}): download in Safari → Files → share → SideStore → My Apps → **+**. Check it against [the .sha256]({sha_url}) if you want.",
            "",
            f"![Source QR]({qr_url})",
            "",
            "### On the phone",
            "",
            "- **Developer Mode** on: Settings → Privacy & Security → Developer Mode.",
            "- **Trust the burner's developer certificate**: Settings → General → VPN & Device Management → tap it → Trust.",
            "- Use **Safari** for downloads, not the GitHub app's browser.",
            "- Refreshes need the phone unlocked periodically (and Wi-Fi).",
            "",
        ]

    if pages_ok == "true":
        L += [
            "### GitHub Pages",
            "",
            f"Source is live: `{source_url}` — landing page: {landing_url}",
            "",
        ]
    elif pages_ok == "false":
        why = f" ({pages_reason})" if pages_reason else ""
        L += [
            "### GitHub Pages gap",
            "",
            f"Pages deploy did not finish{why}. The release and its assets are still up; the source URL will not resolve until Pages is on. Manual fix (works from the phone's browser): repo **Settings → Pages → Build and deployment → Source: GitHub Actions**, then Actions → ios-ci → re-run the latest run.",
            "",
        ]
    else:
        L += [
            "### GitHub Pages gap",
            "",
            f"The Pages deploy did not run to completion — see the pages-publish job in [this run]({run_url}). Manual fix: repo **Settings → Pages → Build and deployment → Source: GitHub Actions**, then re-run.",
            "",
        ]

    text = "\n".join(L)
    if len(text) > 65536:
        print(f"comment is {len(text)} characters — over GitHub's 65,536 cap", file=os.sys.stderr)
        raise SystemExit(1)
    with open(o.out, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"{len(text)} characters -> {o.out}")


if __name__ == "__main__":
    main()
