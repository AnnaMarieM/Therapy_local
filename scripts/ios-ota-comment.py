#!/usr/bin/env python3
"""Builds the sticky iOS OTA pull request comment from the facts the ios.yml jobs gathered.

  ios-ota-comment.py --out comment.md

All input comes from environment variables (the workflow's step env). Four shapes, one marker each:

- signed:      the one-tap OTA path — install page, QR, itms-services copy-paste fallback, profile facts.
- not signed:  the interim, computer-less path — the unsigned release IPA via an on-device signer.
               Getting signing credentials needs a Mac (Xcode mints them) and this project has none;
               a no-Mac path is researched separately, so the comment says that and nothing more
               (no Xcode/Keychain instructions on purpose).
- sign failed: the sign lane ran and failed — links go stale, the unsigned IPA stays the fallback.
- build skipped: macos-build failed, so no OTA lane ran at all.

Every shape ends with the device checklist and the GitHub Pages status (never faked: a Pages gap is
stated with its manual fix). GitHub's comment cap is 65,536 characters; this stays far under it.
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
    owner, name = (repo.split("/") + [""])[:2]
    pages_base = f"https://{owner.lower()}.github.io/{name}"
    install_url = f"{pages_base}/install.html#pr-{pr}"
    manifest_url = f"{pages_base}/manifests/pr-{pr}.xml"
    release_url = f"https://github.com/{repo}/releases/tag/ios-pr-{pr}"
    unsigned_url = f"https://github.com/{repo}/releases/download/ios-pr-{pr}/MnemoThera-ios-unsigned.ipa"
    qr_url = f"https://github.com/{repo}/releases/download/ios-pr-{pr}/MnemoThera-ios-install-qr.png"

    signed = g("SIGNED") == "true"
    sign_result = g("SIGN_RESULT", "")
    pages_ok = g("PAGES_OK", "")
    pages_reason = g("PAGES_REASON", "").strip()

    L = [MARKER, ""]

    if sign_result == "failure":
        L += [
            "## iOS OTA lane failed",
            "",
            f"sign-ota failed in [this run]({run_url}) — links from earlier comments are stale. Usual causes: an expired profile, a device missing from it, a p12/password mismatch. The unsigned IPA in [ios-pr-{pr}]({release_url}) stays available.",
            "",
        ]
    elif sign_result == "skipped":
        L += [
            "## iOS OTA lane did not run",
            "",
            f"The build lane (macos-build) failed in [this run]({run_url}), so sign-ota was skipped. No new build this time; earlier comments' links are as stale as their commit.",
            "",
        ]
    elif not signed:
        p12 = g("SECRET_P12") == "true"
        profile = g("SECRET_PROFILE") == "true"
        partial = ""
        if p12 and not profile:
            partial = " (IOS_P12_BASE64 is set but IOS_MOBILEPROVISION_BASE64 is missing — both are needed)"
        elif profile and not p12:
            partial = " (IOS_MOBILEPROVISION_BASE64 is set but IOS_P12_BASE64 is missing — both are needed)"
        L += [
            f"## Getting MindVault onto the iPhone — interim path (build of #{pr} at `{short}`)",
            "",
            f"The OTA sign lane is dormant: no signing credentials yet{partial}. **Minting them needs a Mac — Xcode and Keychain do that — and this project has no computer.** A no-Mac signing path is being researched; when it lands, a fresh comment here will say so and this comment turns into the one-tap install link. Nothing to do about signing right now.",
            "",
            "**What works today — the unsigned build plus an on-device signer:**",
            "",
            f"1. On the phone, download in Safari: [MnemoThera-ios-unsigned.ipa]({unsigned_url}) — release [ios-pr-{pr}]({release_url}), rebuilt on every push to this pull request. It lands in **Files → Downloads**.",
            "2. Install it with an on-device signer — **SideStore**: My Apps → **+** → pick the IPA from Files.",
            "3. The exact computer-less SideStore bootstrap is being verified and will be added in a later comment — don't burn time guessing; a fresh version of this comment appears on every push.",
            "",
            "### SideStore notes",
            "",
            "- Sign in with a **dedicated burner Apple ID**, not your main one: free accounts carry roughly one development certificate, the same burner ID must later serve CI signing too, and it keeps your main ID out of certificate-revocation churn.",
            "- SideStore 0.7.0-alpha (Sept 15) predates Apple's October 2026 authentication changes (errors −80045/−80046): if sign-in fails, use the SideStore nightly.",
            "- Free-account caps: 3 installed apps per device, 10 App IDs per week.",
            "",
            "(When signing arrives, the repository secrets `IOS_P12_BASE64`, `IOS_P12_PASSWORD`, `IOS_MOBILEPROVISION_BASE64`, `IOS_KEYCHAIN_PASSWORD` will be created through the GitHub web UI — no computer needed for that step.)",
            "",
        ]
    else:
        bundle_id = g("BUNDLE_ID", "?")
        bundle_version = g("BUNDLE_VERSION", "?")
        title = g("APP_TITLE", "MindVault")
        team = g("TEAM", "?")
        profile_name = g("PROFILE_NAME", "?")
        profile_expires = g("PROFILE_EXPIRES", "?")
        days_left = g("PROFILE_DAYS_LEFT", "?")
        devices = g("PROFILE_DEVICES", "?")
        L += [
            f"## Install {title} on the iPhone — build of #{pr} at `{short}`",
            "",
            f"**Open this in Safari on the phone:** {install_url}",
            "",
            f"![Install QR]({qr_url})",
            "",
            "The QR encodes the install page (the Camera doesn't act on `itms-services` QRs).",
            "",
            "Copy-paste fallback — paste into Safari's address bar:",
            "",
            "```",
            f"itms-services://?action=download-manifest&url={manifest_url}",
            "```",
            "",
            f"### What CI published (release [ios-pr-{pr}]({release_url}))",
            "",
            f"- signed build `MnemoThera-ios-signed.ipa`: bundle `{bundle_id}`, version {bundle_version}, team `{team}`",
            f"- profile `{profile_name}`: expires **{profile_expires} ({days_left} day(s) left)**, {devices} device(s) listed",
            f"- `MnemoThera-ios-unsigned.ipa`: SideStore fallback — download in Safari → Files → SideStore → My Apps → **+**",
            "- `manifest.xml`: backup copy (the live manifest is the Pages one; release-asset content types are not trusted for installs)",
            "- `MnemoThera-ios-install-qr.png`: the QR above",
            "",
        ]

    L += [
        "### On the phone, before installing anything",
        "",
        "- **Developer Mode** on: Settings → Privacy & Security → Developer Mode (one-time).",
        "- **Trust the signer**: Settings → General → VPN & Device Management → tap the certificate → Trust (on iOS 18+ the phone restarts to finish).",
        "- Install from **Safari**, not the GitHub app's browser.",
        "- A development profile is good for **7 days** from issuance — an expired build stops launching; install a fresh one from a newer comment, or refresh in SideStore.",
        "",
    ]

    if pages_ok == "true":
        L += [
            "### GitHub Pages",
            "",
            f"Install page and manifests are live ({install_url}).",
            "",
        ]
    elif pages_ok == "false":
        why = pages_reason or "deployment did not complete"
        why_txt = f" ({why})" if pages_reason else ""
        L += [
            "### GitHub Pages gap",
            "",
            f"Pages deploy did not finish{why_txt}. The release and its assets are still up; the OTA link and QR will not resolve until Pages is on. Manual fix (works from the phone's browser): repo **Settings → Pages → Build and deployment → Source: GitHub Actions**, then Actions → ios-ci → re-run the latest run.",
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
