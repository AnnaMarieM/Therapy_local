# iOS OTA install: hosting on GitHub infrastructure + one-tap delivery in a PR comment

Research date: 2026-11-16. Scope: hosting the installable link and delivering it via a GitHub PR comment (+ optional QR). Signing/certificates are covered by a parallel researcher; interface points are flagged where they matter.

**Scope correction verified up front:** `gh repo view AnnaMarieM/Therapy_local --json visibility` returns `PUBLIC` (`isPrivate: false`), and an *unauthenticated* `GET https://api.github.com/repos/AnnaMarieM/Therapy_local` returns 200 with `"private": false` (probed in this session; `git remote -v` confirms origin is this repo). The brief assumed the repo is private. Both the private-repo rules and the public-repo reality are documented below, since the owner could flip visibility at any time. All HTTP probes in this session were anonymous (no `gh` token), read-only.

---

## Key Question 1: `itms-services://` OTA install requirements in 2026 (manifest fields, HTTPS, encoding, trust, size, prompts)

### Takeaway
An OTA install link is `itms-services://?action=download-manifest&url=<https URL of manifest.plist>`; iOS then fetches the manifest, then the `.ipa` it names. Everything in that chain (manifest and IPA) must be served over HTTPS with a certificate the device already trusts, the `.ipa` must be a *signed* build whose provisioning profile covers the device, and both files must be reachable **without a login** — iOS fetches them from `com.apple.appstored`, outside the Safari session.

### Cited Findings

**The link and what it does**
- Sample link per Apple: `<a href="itms-services://?action=download-manifest&url=https://melardclothing.com/manifest.plist">Install App</a>` — "When a user taps a web link, the manifest file is downloaded, which triggers the downloading and installation of what your webpage has described." — [Apple Platform Deployment: Distribute proprietary in-house apps](https://support.apple.com/guide/deployment/distribute-proprietary-in-house-apps-depce7cefc4d/web)
- Do not link directly to the `.ipa`; the device downloads it via the manifest. "Although the protocol portion of the URL is 'itms-services,' the App Store isn't involved." — [Apple Platform Deployment](https://support.apple.com/guide/deployment/distribute-proprietary-in-house-apps-depce7cefc4d/web)
- "Only iOS, iPadOS and visionOS understand the scheme… a desktop browser cannot run the link at all" — macOS/Windows/Android ignore it by design. — [BetaDrop: Fix an itms-services link that does nothing](https://betadrop.app/blog/fix-itms-services-link-not-working)

**HTTPS — both hops, valid trusted cert, either host**
- Requirements for website distribution: `.ipa` format, XML manifest, "downloaded from a website whose address begins with HTTPS," "signed by a certificate that's trusted on the device." — [Apple Platform Deployment](https://support.apple.com/guide/deployment/distribute-proprietary-in-house-apps-depce7cefc4d/web)
- "Be sure that the .ipa file is accessible over HTTPS and that your site is signed with a certificate that's trusted by iOS and iPadOS. Installation fails if a self-signed certificate doesn't have a trusted anchor and can't be validated by the device." — [Apple Platform Deployment](https://support.apple.com/guide/deployment/distribute-proprietary-in-house-apps-depce7cefc4d/web)
- "The `.ipa` URL *inside* the manifest must also be HTTPS. A perfectly secure manifest pointing at an insecure package fails just the same." Manifest and IPA can be separate URLs; nothing in Apple's doc requires same-host (examples mix hosts, e.g. a Dropbox-hosted manifest used in a working QR flow). — [BetaDrop](https://betadrop.app/blog/fix-itms-services-link-not-working); [Apple Developer Forums: enterprise app install via itms-services with Dropbox manifest](https://developer.apple.com/forums/thread/676970)
- TLS 1.2+ valid certificate is required for both manifest and `.ipa`; "Get any URL, the bundle identifier or the HTTPS certificate wrong and the install fails — often silently." — [Appisto: OTA App Installation Explained (2026)](https://appisto.app/blog/ota-app-installation-explained)

**Manifest fields (working minimal + optional icons)**
- Minimal working manifest keys: `items[] → assets[]` with one asset `kind = software-package`, `url = https://…/*.ipa`; and `metadata` with `bundle-identifier` (must match the app's `CFBundleIdentifier` exactly), `bundle-version`, `kind = software`, `title`. — [BetaDrop](https://betadrop.app/blog/fix-itms-services-link-not-working)
- Full example adds icon assets (`kind = display-image`, `kind = full-size-image`) and `metadata.platform-identifier = com.apple.platform.iphoneos`; `bundle-version` hardcoded to `1.0` "didn't seem to affect anything" in one deployment (i.e., it is not the enforcement key; bundle-identifier is). — [decovar.dev: Distributing iOS applications within a company from a website](https://decovar.dev/blog/2025/04/03/distributing-ios-applications-without-app-store/)
- A manifest that parses but has the wrong `bundle-identifier` lets the whole download run and then fails at the last moment with "Unable to Download App." — [BetaDrop](https://betadrop.app/blog/fix-itms-services-link-not-working)

**MIME types (documented, but iOS tolerates near-misses)**
- Apple instructs configuring the web server with `application/octet-stream ipa` and `text/xml plist` (IIS: same). — [Apple Platform Deployment](https://support.apple.com/guide/deployment/distribute-proprietary-in-house-apps-depce7cefc4d/web); [Apple deployment reference for iOS](https://support.apple.com/en-is/guide/deployment-reference-ios/apdda9e027d2/web)
- Community reports show installs working with `.plist → application/xml` too, so the exact XML media type is not strictly policed. — [Ask Different: iOS OTA installation not working](https://apple.stackexchange.com/questions/243758/ios-over-the-air-installation-not-working-cannot-connect-to-server); [StackOverflow 5205721](https://stackoverflow.com/questions/5205721/iphone-is-there-a-special-mimetype-for-iphone-downloads)
- **What iOS's server-side fetch tolerates for content-type is empirically what matters** — see the authentication finding below: the fetcher is `com.apple.appstored/1.0 iOS/… AMS/1`, a system service, not Safari. — [decovar.dev](https://decovar.dev/blog/2025/04/03/distributing-ios-applications-without-app-store/)

**Both files must be reachable WITHOUT authentication (critical for "private repo" hosting)**
- With auth on the manifest route: install dies (302 → login page fetch by `appstored`, "these requests are happening behind the scenes (so user gets no prompts)"). With auth on the IPA route only: icon appears "with a cloud symbol" and the app never installs. Only when **both** routes are anonymous does the install complete. "The only thing that can have authentication/authorization is the page where you publish the itms-services:// link." — [decovar.dev](https://decovar.dev/blog/2025/04/03/distributing-ios-applications-without-app-store/)
- App Center worked the same way: short-lived anonymous signed URLs on Azure CDN, because auth-less fetches are required. — [decovar.dev](https://decovar.dev/blog/2025/04/03/distributing-ios-applications-without-app-store/)

**URL-encoding rules**
- If the manifest URL contains no spaces/query params, it is used as-is: `itms-services://?action=download-manifest&url=https://host/manifest.plist` (all primary examples do this). — [Apple Platform Deployment](https://support.apple.com/guide/deployment/distribute-proprietary-in-house-apps-depce7cefc4d/web)
- Spaces etc. must be percent-encoded, and because the `url=` value gets unescaped once, `%` must be doubled: `url=https://example.com/My%2520App.plist` decodes to `…/My%20App.plist`. — [StackOverflow 4695180](https://stackoverflow.com/questions/4695180/wireless-iphone-app-distribution-problem-with-itms-services-protocol/15434553)
- If the manifest URL itself carries query parameters (signed URLs, `?dl=1`), those `?`, `&`, `=` must be percent-encoded inside the `url=` parameter (`%3F`, `%26`, `%3D`); a link checker flags "a signed manifest address whose own `?` or `&` was not percent-encoded." — [BetaDrop](https://betadrop.app/blog/fix-itms-services-link-not-working); worked example with a fully encoded Dropbox manifest URL: [Apple Developer Forums 676970](https://developer.apple.com/forums/thread/676970)
- In raw HTML the `&` between query params must be written `&amp;`; in GitHub-flavored Markdown, plain `&` is fine.

**Trust / signing on the phone (interface with signing research)**
- "They need to be signed by a certificate that's trusted on the device." An unsigned `.ipa` is not installable by this mechanism at all (install fails; Apple's troubleshooting starts at "make sure the app is signed correctly"). — [Apple Platform Deployment](https://support.apple.com/guide/deployment/distribute-proprietary-in-house-apps-depce7cefc4d/web)
- First launch shows "Untrusted Developer"; user must go to Settings → General → **VPN & Device Management** and trust the profile (one-time per developer profile/device). — [decovar.dev](https://decovar.dev/blog/2025/04/03/distributing-ios-applications-without-app-store/)
- **iOS/iPadOS 18+:** "proprietary in-house apps that you manually install (not using a device management service) require a device restart to complete the trust of the provisioning profile." — [Apple Platform Deployment](https://support.apple.com/guide/deployment/distribute-proprietary-in-house-apps-depce7cefc4d/web)
- First launch also validates the distribution certificate against Apple's OCSP server (`ocsp.apple.com` must be reachable); response cached 3–7 days. — [Apple Platform Deployment](https://support.apple.com/guide/deployment/distribute-proprietary-in-house-apps-depce7cefc4d/web)
- For ad-hoc/dev profiles (the free-Apple-ID case), the device's UDID must be in the provisioning profile or install fails with "Unable to Install"; profiles expire (12 months enterprise; 7 days free-provisioning — the 7-day figure is covered by the signing researcher). — [Appisto](https://appisto.app/blog/ota-app-installation-explained); [decovar.dev](https://decovar.dev/blog/2025/04/03/distributing-ios-applications-without-app-store/)

**Prompt behavior and current status in 2026**
- Tapping a valid link in Safari produces the sheet *"example.com would like to install 'MyApp'"* → tap **Install** → icon lands on Home Screen with a progress ring. So yes, iOS still prompts before install, and the link/manifest's job ends at that sheet. — [BetaDrop](https://betadrop.app/blog/fix-itms-services-link-not-working)
- The scheme is still supported and evolving: on iOS 26, an OTA install triggered from *inside* a running app no longer auto-closes the running app (behavior change, works on iOS 18). — [Apple Developer Forums 847091](https://developer.apple.com/forums/thread/847091)
- In-app browsers (Slack, Gmail, Instagram webviews) silently ignore the scheme; the link must run in Safari (paste into Safari's address bar works, with a confirmation). Third-party iPhone browsers (Chrome/Firefox) are inconsistent. — [BetaDrop](https://betadrop.app/blog/fix-itms-services-link-not-working)

**Size limits**
- No Apple-documented size cap for itms-services OTA installs was found in any source reviewed. Practical limits are hosting-side (GitHub: 2 GiB per release asset; see Q2) and device-side (free space). The relevant artifact here is tiny: the workflow artifact `MnemoThera-ios-unsigned.ipa` is **1.7 MB** (probed via the artifacts API this session). — see Q2 probes; [GitHub docs: About releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)

### Inferences
- A GitHub-hosted manifest + IPA satisfies the Apple requirements *if and only if* both URLs are anonymously reachable over HTTPS: `*.github.io` (Pages) and `github.com/<owner>/<repo>/releases/download/...` (release assets) both qualify on TLS and anonymity for a public repo.
- Because `appstored` fetches outside Safari, "authenticated hosting" (e.g., anything gated behind GitHub login) is fundamentally incompatible with itms-services — this single fact decides the private-repo question (Q2).
- The manifest does not need a `.plist` extension or a `text/xml` content-type to work per community evidence, but since this session could not verify GitHub's content-type for `.plist`, the safe design serves the manifest from GitHub Pages (or tests the tap empirically) — flagged in Gaps.

### Gaps
- GitHub's actual Content-Type for a `.plist` (or `.xml`) file served from Pages / raw.githubusercontent / release assets was not verified this session (no existing `.plist` asset to probe). Apple asks for `text/xml`; installs are reported working with `application/xml`; untested: `application/octet-stream`/`text/plain`. Mitigation: name the manifest file `manifest.xml` on Pages (or test the tap on-device).
- Whether an automatic redirect (`<meta http-equiv="refresh">` or JS `location.href`) from an https page to an `itms-services://` URL works reliably in Safari — not verified; the working examples use an explicit `<a href>` tap (decovar, Apple).
- No authoritative Apple statement on an OTA download size cap (historical cellular limits not verified).
- iOS 26-vs-18 differences beyond the one forum thread (e.g., whether the pre-install sheet changed) were not exhaustively researched.

---

## Key Question 2: Hosting on GitHub for a PRIVATE repo — what is reachable unauthenticated; alternatives

### Takeaway
For a **private** repo, nothing GitHub hosts can feed an iPhone OTA install: Pages (would be auth-gated or unpublishable on Free), release assets, raw URLs, and Actions artifacts all require authentication, and the phone's `appstored` fetch cannot authenticate. The repo is in fact **public today** (verified), where release assets and raw URLs *are* anonymously reachable — the current setup already ships APKs this way. If the repo must stay/become private, use a separate public distribution repo (or an external static host) and push the IPA there from Actions with a PAT.

### Cited Findings

**The repo's actual state (probed anonymously this session)**
- `GET https://api.github.com/repos/AnnaMarieM/Therapy_local` → 200, `"private": false` — the repo is public. `gh repo view` agrees (`visibility: PUBLIC`). Also found: no Pages site exists (`https://annamariem.github.io/Therapy_local/` → 404); releases exist (tags `main`, `pr-114`) carrying APK assets; Actions artifacts exist (1913 total), including `MnemoThera-ios-unsigned.ipa` (1.7 MB). — *empirical probes, this session*
- Anonymous `HEAD https://github.com/AnnaMarieM/Therapy_local/releases/download/main/Therapy-main-qa.apk` → **200**, `content-type: application/vnd.android.package-archive`, `content-length: 138172882` — i.e., **release assets on a public repo are anonymously downloadable, byte-identical, with extension-aware content types**, and the repo already uses per-PR release tags (`pr-114`) for exactly this kind of delivery. — *empirical probe, this session*
- Anonymous `GET …/actions/artifacts` (list) → 200 (JSON list served unauthenticated), but anonymous `GET …/actions/artifacts/14215243851/zip` → **401**: **Actions artifacts cannot be downloaded without a signed-in account, even on a public repo.** — *empirical probe; matches* [actions/upload-artifact #144 "Why downloading public artifacts requires authentication"](https://github.com/actions/upload-artifact/issues/144) *and* [#51 "Artifact download URLs only work for registered users (404 for guests)"](https://github.com/actions/upload-artifact/issues/51)

**Private-repo rules (if visibility flips back)**
- GitHub Pages: on GitHub Free for personal accounts, the repository **must be public** to publish Pages at all; making a repo private **automatically unpublishes** its Pages site. — [GitHub Docs: Creating a GitHub Pages site](https://docs.github.com/en/enterprise-cloud@latest/pages/getting-started-with-github-pages/creating-a-github-pages-site); [GitHub Docs: Setting repository visibility](https://docs.github.com/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility)
- "Private" Pages visibility (site accessible only to people with read access) exists only for private/internal repos on **GitHub Enterprise Cloud** (organizations); it is auth-gated behind GitHub login and therefore useless for a phone-side `appstored` fetch. — [GitHub Docs: Changing the visibility of your GitHub Pages site](https://docs.github.com/en/github/working-with-github-pages/changing-the-visibility-of-your-github-pages-site); [GitHub changelog: Access control for GitHub Pages (2021)](https://github.blog/changelog/2021-01-21-access-control-for-github-pages)
- `raw.githubusercontent.com` URLs **return 404 for private repositories** ("because `raw.githubusercontent.com` requires authentication for private repo content"). — [github/gh-aw #22510](https://github.com/github/gh-aw/issues/22510); corroborated by [componentjs/remotes.js #19](https://github.com/componentjs/remotes.js/issues/19) and [r/github: raw.githubusercontent.com not working for private repositories](https://reddit.com/r/github/comments/139a8rl/rawgithubusercontentcom_not_working_for_private)
- Release assets on a private repo likewise require read access (the docs statement "Anyone with read access to the repository can use this endpoint" is the general rule for repo endpoints; anonymous access only exists on public repos). — [GitHub Docs: REST API endpoints for GitHub Actions artifacts](https://docs.github.com/en/rest/actions/artifacts) (explicit "read access" rule for the analogous artifacts endpoints)

**Alternatives that keep the IPA byte-identical and the URL stable**
- **GitHub Releases on a public repo** (the repo's own existing pattern): per-file limit 2 GiB, up to 1000 assets per release, no total-size or bandwidth limit; browser asset URL `https://github.com/<owner>/<repo>/releases/download/<tag>/<asset>` is stable and anonymously downloadable for public repos (verified 200). Best default for the IPA. — [GitHub Docs: About releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases); probe this session
  - Caveat: `/releases/latest/download/<asset>` returned **404** here — the "latest" alias only resolves an asset if it exists on the latest non-prerelease release; with per-PR asset names (`Therapy-pr114-qa.apk`) the name differs per tag. Always use the explicit `<tag>` URL. — *probe this session*
- **GitHub Pages from a public repo** (this repo has none today; the URL would be `https://annamariem.github.io/Therapy_local/…`): free, valid TLS (github.io cert is publicly trusted — satisfies Apple's "trusted certificate" requirement), anonymous. Limits: soft 100 GB/month bandwidth, soft 10 builds/hour, published-site size limit ~1 GB, files >100 MiB blocked. — [GitHub Docs: Pages limits](https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits); [github/docs source](https://github.com/github/docs/blob/main/content/pages/getting-started-with-github-pages/github-pages-limits.md)
- **A dedicated public distribution repo** (e.g., `AnnaMarieM/Therapy-dist`): keeps the source repo private while hosting IPA/manifest/install-page publicly. A workflow in the private repo pushes artifacts to it with a fine-grained PAT/App token stored as a Actions secret (`GITHUB_TOKEN` is scoped to the workflow's own repo). — [GitHub Docs: Use GITHUB_TOKEN for authentication in workflows](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token) (additional permissions require a GitHub App or PAT)
- **Gists**: raw gist files (`gist.githubusercontent.com`) are anonymously readable even for *secret* gists, but gists are designed for text (binary IPA hosting is off-label, URLs are revision-locked, and content-type is `text/plain`); acceptable at most for the tiny `manifest.plist`, not for the IPA. — (no strong single source; treat as community practice)
- **External free static hosts** (Cloudflare Pages / Netlify / Surge): all serve anonymously with valid TLS and stable URLs; fine for a 1.7 MB IPA (Cloudflare Pages has a 25 MiB per-file cap). Requires an account + deploy token in CI. — (general knowledge; no single citation needed for the decision, flagged as unverified-in-session detail)

### Inferences
- **Recommended shape for this repo:** upload `MnemoThera-<branch>.ipa` + `manifest.plist` (+ install page/QR) as release assets on tag `ios-pr-<N>` (mirroring the existing `pr-114` APK pattern), and host only the tiny install page (or nothing — see Q3's sanitizer finding) on GitHub Pages or as part of the comment flow. Release assets give byte-identical, stable, anonymous URLs and match what the repo already does for Android.
- If the source repo goes private again, the *only* GitHub-native path is a separate public repo for hosting (plus a PAT in the private repo's CI). Alternatively swap the artifact destination to Cloudflare Pages/Netlify and leave GitHub entirely.
- Whatever hosts the IPA, the phone must reach it without cookies/tokens — any "private" GitHub URL will 404/401 for `appstored` and the install will die silently (decovar's exact failure mode).

### Gaps
- Whether GitHub release-asset responses set a workable Content-Type for `.plist` (the APK probe returned an extension-aware type, so GitHub does map extensions; `.plist` mapping unverified).
- Gist binary-file size limits were not verified.
- Cloudflare Pages/Netlify per-file caps were taken from general knowledge, not re-verified in this session.

---

## Key Question 3: Delivering the link as a ONE-TAP PR comment (+ QR)

### Takeaway
A workflow can comment on its own repo's PR with `permissions: pull-requests: write` (`gh pr comment` with `GH_TOKEN: ${{ github.token }}`; the issues API also accepts `issues: write` since PR comments are issue comments; comment bodies cap at 65,536 characters). **But GitHub's comment sanitizer strips `itms-services` hrefs** (its anchor allowlist is only http/https/mailto/relative/github-windows/github-mac), so the one-tap link in the comment must be an **https landing page** whose own `<a href="itms-services://…">Install</a>` does the trick — the user opens the PR conversation in Safari, taps the https link, then taps Install. QR: encode the https page URL for the iOS Camera; encoding the raw itms-services URL is scanner-dependent.

### Cited Findings

**Token permissions for commenting on a PR from Actions**
- `permissions` key grants GITHUB_TOKEN scopes per workflow/job; `pull-requests` covers "Pull requests and related comments"; the 2021 changelog enumerates `pull-requests: read|write|none`. — [GitHub Docs: Use GITHUB_TOKEN for authentication in workflows](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token); [GitHub changelog 2021-04-20: Control permissions for GITHUB_TOKEN](https://github.blog/changelog/2021-04-20-github-actions-control-permissions-for-github_token)
- A PR-comment action documents: "requires the `pull-request: write` permission to add a comment to your pull request." — [vitest-coverage-report-action commit d1f762b](https://github.com/davelosert/vitest-coverage-report-action/commit/d1f762b6cf8ba6e6388a9060ba3aa6316accef02)
- PR comments are issue comments at the API level (the too-long error resource is `IssueComment`), so `issues: write` also grants the POST `/repos/{owner}/{repo}/issues/{issue_number}/comments` endpoint. — [changesets/action #174](https://github.com/changesets/action/issues/174) (error payload `{"resource":"IssueComment",...}`)
- Using `gh` in Actions: pass the token via env — the official tutorial's gh CLI example sets `GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}` with `permissions: issues: write`. — [GitHub Docs: Use GITHUB_TOKEN](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token)
- Since Feb 2023 default GITHUB_TOKEN is read-only (`contents: read`) on new repos/orgs — the workflow must declare the permissions it needs; note that **uploading release assets also needs `contents: write`**. — [craci.com: GITHUB_TOKEN permissions](https://craci.com/blog/github-token-permissions); [GitHub Docs: Use GITHUB_TOKEN](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token)
- Fork caveat: workflows triggered by `pull_request` from a fork get a read-only GITHUB_TOKEN — same-repo PRs (this project's model) get the declared permissions. — [GitHub Docs: Use GITHUB_TOKEN](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token) (least-privilege design; fork restriction is standard GitHub security design)

**Comment size limit**
- Issue/PR comment bodies max out at **65,536 characters** ("Body is too long (maximum is 65536 characters)"); several Actions-comment libraries hard-code truncation at 65,536. — [GitHub community discussion #27190](https://github.com/orgs/community/discussions/27190); [mshick/add-pr-comment #93](https://github.com/mshick/add-pr-comment/issues/93); [peter-evans/create-or-update-comment fix #182](https://github.com/peter-evans/create-or-update-comment/commit/ca08ebd5dc95aa0cd97021e9708fcd6b87138c9b)

**Does GitHub render/blocklist itms-services links in comments? — it strips them**
- GitHub's own (open-sourced) sanitization pipeline allows exactly these schemes in `<a href>`: `ANCHOR_SCHEMES = ['http', 'https', 'mailto', :relative, 'github-windows', 'github-mac']` — everything else is removed from user content. — [github/html-pipeline `sanitization_filter.rb`](https://raw.githubusercontent.com/github/html-pipeline/master/lib/html/pipeline/sanitization_filter.rb)
- Real-world confirmation of the behavior on GitHub: "When I add links with custom URI Scheme like `map://` or `comgooglemaps://` this links are not clickable" (filed on GitHub about GitHub-rendered markdown). — [TelegramMessenger/Telegram-iOS #244](https://github.com/TelegramMessenger/Telegram-iOS/issues/244); other products behave the same ([Overleaf #852](https://github.com/overleaf/overleaf/issues/852), [claude-code #95675](https://github.com/anthropics/claude-code/issues/95675))
- Consequence: `[Install](itms-services://?action=download-manifest&url=…)` in a PR comment renders as **plain text, not a tappable link**; pasted raw, the scheme won't autolink either. The user can still *copy* the string and paste it into Safari (Safari accepts it, with a confirmation). — [BetaDrop](https://betadrop.app/blog/fix-itms-services-link-not-working)

**What GitHub *does* allow in comments — the workable one-tap design**
- `<img src>` is sanitized to `http`/`https`/relative only, and external images in comments are served/proxied (GitHub "Camo"), so a **markdown image pointing at an https QR PNG** renders inline in the PR conversation. — [github/html-pipeline `sanitization_filter.rb`](https://raw.githubusercontent.com/github/html-pipeline/master/lib/html/pipeline/sanitization_filter.rb)
- So the robust comment contains: (1) an **https link to a tiny install page** (GitHub Pages or any static host) whose HTML has `<a href="itms-services://?action=download-manifest&url=https://…/manifest.plist">Install MnemoThera</a>` — that page's anchor is *not* GitHub-sanitized, so it's a genuine one-tap; (2) the raw `itms-services://` string as copy-paste fallback text; (3) optionally a QR image (`![install QR](https://github.com/AnnaMarieM/Therapy_local/releases/download/ios-pr-<N>/install-qr.png)`) — QR PNG generated in CI (e.g., Python `qrcode` lib) and attached as a release asset so the image URL is stable and anonymous. (The general pattern — generate `manifest.plist` + an `index.html` install page + a QR code from an IPA — is what established OTA tooling does, e.g. the `itms-services` CLI "generates a `manifest.plist` and an `index.html` install page (with QR code)".) — [xykong/itms-services](https://github.com/xykong/itms-services); scheme allowlist per html-pipeline above

**The user's flow: PR conversation in Safari on the iPhone**
- Tapping an https link in the PR conversation opens the install page in Safari; tapping the page's `itms-services` anchor triggers the *"would like to install"* sheet — this is the documented working path (website → itms-services anchor → install sheet). — [Apple Platform Deployment](https://support.apple.com/guide/deployment/distribute-proprietary-in-house-apps-depce7cefc4d/web); [decovar.dev](https://decovar.dev/blog/2025/04/03/distributing-ios-applications-without-app-store/); [BetaDrop](https://betadrop.app/blog/fix-itms-services-link-not-working)
- GitHub **Actions artifacts** are NOT a viable delivery target for the phone (anonymous download → 401); release assets ARE (→ 200). — *probes this session;* [actions/upload-artifact #144](https://github.com/actions/upload-artifact/issues/144)

**QR codes: what to encode**
- iOS Camera/Code Scanner opens QR-detected links (Apple documents scanning "websites, apps, tickets, and more"). — [Apple Support: Scan a QR code with your iPhone or iPad](https://support.apple.com/en-us/102680)
- For a standard URL in a QR, the stock Camera app shows a notification that opens the link in Safari. — [StackOverflow 43503413](https://stackoverflow.com/questions/43503413/how-to-open-ios-app-when-user-scans-a-qr-code-with-an-url-in-it)
- Whether the stock Camera app will *act on* a QR containing `itms-services://` is **scanner-dependent/uncertain**: one practitioner reports QR reader apps vary — one third-party reader installed the app, other testers reported "QR just opens Safari"; the recommended workaround was your own web page that then hands off to the scheme. — [StackOverflow 45450850](https://stackoverflow.com/questions/45450850/convert-custom-url-scheme-to-qr-code-ios)
- Established OTA tooling therefore encodes the **https install page** in the QR (index.html + QR generated together) rather than the raw scheme. — [xykong/itms-services](https://github.com/xykong/itms-services)
- Practical QR note: the user reads the PR comment *on the iPhone*, so a QR inside the same phone is only useful if they first open the comment on a computer (or print/share the QR); its main value is scan-from-Mac → install-on-iPhone. — (inference)

### Inferences
- Concretely, the PR comment body should look like:
  ```
  📲 iOS test build `pr-114` (2026-11-16)
  **One-tap install:** https://annamariem.github.io/Therapy_local/install.html#pr-114  (open in Safari)
  Manifest/iPA: [manifest.plist](https://github.com/AnnaMarieM/Therapy_local/releases/download/ios-pr-114/manifest.plist) · [.ipa](…/MnemoThera-pr114.ipa)
  Fallback: copy this into Safari → itms-services://?action=download-manifest&url=https://github.com/AnnaMarieM/Therapy_local/releases/download/ios-pr-114/manifest.plist
  ![Install QR](https://github.com/AnnaMarieM/Therapy_local/releases/download/ios-pr-114/install-qr.png)
  ```
  (well under 65,536 chars). The install page can be one static generic page (`install.html`) whose JS reads the manifest URL from a query/hash param and sets the anchor's `href` — deployed once to Pages; or a per-PR page committed to the Pages branch by the same workflow.
- Workflow wiring: one job with `permissions: { contents: write, pull-requests: write }`, steps: `gh run download` (or `actions/download-artifact`) → generate `manifest.plist` + install page + QR PNG → upload all to release tag `ios-pr-<N>` (`gh release create/upload` with `GH_TOKEN: ${{ github.token }}`) → `gh pr comment ${{ github.event.number }} --body-file comment.md`. GitHub Pages deploys via Actions additionally need `pages: write` + `id-token: write`. — [GitHub Docs: Use GITHUB_TOKEN](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token); [actions/deploy-pages README](https://github.com/actions/deploy-pages/blob/main/README.md); [actions-workshop docs](https://github.com/actions-workshop/actions-workshop/blob/main/docs/005-deployment-github-pages.md)
- Because GitHub strips the scheme from comment anchors, the "one-tap" property realistically means **one tap in the comment → one tap on the install page → install sheet**. That is still dramatically better than fighting GitHub's mobile UI for an artifact download (which requires being logged in).

### Gaps
- GitHub.com's production sanitizer is not guaranteed to be identical to the open-source `html-pipeline` default (no official list published); evidence (Telegram-iOS #244) says custom schemes are stripped in practice, but a one-off empirical test in a scratch PR comment would confirm on this exact repo.
- Whether the GitHub *mobile app* (not Safari) opens https links in the internal browser and swallows itms-services from the landing page — untested; the brief's plan (use Safari) sidesteps this.
- Whether `api.qrserver.com`-style URL-parameter QR images pass GitHub Camo — unverified; the release-asset PNG approach avoids the question entirely.
- Whether an auto-redirect from the install page to itms-services works (vs. requiring the explicit anchor tap) — unverified.

---

## Key Question 4: Fallback — signed IPA direct download from a PR comment → SideStore/AltStore

### Takeaway
If OTA is a dead end, the PR comment links a release-asset IPA (anonymously downloadable; verified 200), Safari saves it to Files, and the user imports it into SideStore (or AltStore), which *re-signs* the app with the user's own free Apple ID. The free-account ceiling is 3 sideloaded apps at once (including SideStore itself), 10 App IDs per week, and a 7-day signing expiry — and the IPA must still be a valid, unencrypted app archive for SideStore to sign it.

### Cited Findings
- Safari on iOS can download files (to Files/Downloads) since iOS 13; a `.ipa` is just a file to Safari — the earlier OTA failures don't apply because SideStore installs it locally rather than via `itms-services`. — (mechanism general knowledge; the download step is the uncontroversial part)
- Release-asset download from a **public** repo works anonymously from Safari on the phone (verified 200 this session); Actions artifacts do **not** (401 anonymous) — so the fallback link must be a release asset, not an artifact link. — *probes this session;* [actions/upload-artifact #144](https://github.com/actions/upload-artifact/issues/144)
- **SideStore**: "An alternative app store for seamless sideloading. No jailbreak needed, and **no computer after the initial install**, only a Wi-Fi connection." — [sidestore.io](https://sidestore.io/)
- Free-account limits: "If using a free Apple Account, SideStore can only install **3 apps (including itself) at a time**. Additionally, only **10 different apps may be installed in a week** (Referred to as App IDs)." Apps installed with a free Apple ID expire after ~**7 days** and must be refreshed (SideStore refreshes on-device). — [SideStore Docs FAQ](https://docs.sidestore.io/docs/faq); [SideStore no-PC guide](https://youtube.com/watch?v=usN502asfwk)
- Install flow after setup: connect SideStore's WireGuard/VPN profile, open SideStore → My Apps → **+** → select the IPA file (SideStore then signs with the user's Apple ID and installs). — [onejailbreak.com: SideStore guide](https://onejailbreak.com/blog/sidestore)
- The initial SideStore install itself needs a computer (AltServer-style) or a web-based helper ("SideInstaller: sideloading, without the computer"). — [SideInstaller](https://sideinstaller.net/); [SideStore no-PC guide](https://youtube.com/watch?v=usN502asfwk)
- **AltStore** (the alternative) keeps requiring AltServer running on a computer on the same Wi-Fi for installs/refresh — strictly worse for a phone-only user. — [sidestore.io](https://sidestore.io/) (SideStore's stated advantage); [SideStore FAQ](https://docs.sidestore.io/docs/faq)
- Note on the **unsigned** IPA: the current CI artifact is `MnemoThera-ios-unsigned.ipa` — unsigned device builds are exactly what SideStore exists to re-sign with the user's Apple ID, so the fallback path does not require the OTA path's "trusted certificate in the manifest chain"; it requires SideStore to be set up and one of 3 app slots to be free. — artifact name/size probe this session; [SideStore Docs FAQ](https://docs.sidestore.io/docs/faq)

### Inferences
- "What breaks" in the fallback: (1) the phone must be signed into GitHub in Safari to reach the IPA if it's an *artifact* — so ship it as a *release asset*; (2) iOS may complain the file type is unknown — user opens Files → share sheet → SideStore; (3) the 3-app/10-App-ID-per-week caps bite if the user is already running other sideloaded apps; (4) the 7-day expiry means every build effectively self-destructs in a week unless refreshed; (5) initial SideStore setup is the one-time hurdle (computer or web helper).
- This fallback is fully independent of Apple's OTA plumbing — no manifest, no HTTPS-manifest requirement — which makes it a good design-B if GitHub's MIME handling for the manifest or the sanitizer quirks prove troublesome.
- TestFlight is the zero-hosting alternative but requires a paid Apple Developer account, which is out of scope for a free Apple ID. — (standard platform fact, consistent with the Appisto/BetaDrop framing that OTA "earns its keep" outside TestFlight)

### Gaps
- Exact on-iOS-26 behavior of SideStore/SideJIT refresh was not verified this session (SideStore's own docs are the best current reference).
- Whether Safari on iOS 17/18/26 offers "Share → SideStore" directly from the download completion sheet vs. requiring Files — not verified (minor UX detail).
- Whether an *unsigned* IPA (rather than a merely free-signed one) is accepted by SideStore's signing flow without complaints was not verified from sources; SideStore's core function is signing unsigned IPAs with the user's Apple ID, so it should be accepted, but a device test would settle it.

---

## Session probe log (all anonymous, read-only)
| Probe | Result |
|---|---|
| `gh repo view AnnaMarieM/Therapy_local --json visibility,isPrivate` | `PUBLIC`, `false` |
| `GET api.github.com/repos/AnnaMarieM/Therapy_local` (anon) | 200, `"private": false` |
| `GET raw.githubusercontent.com/AnnaMarieM/Therapy_local/main/README.md` (anon) | 200 `text/plain` |
| `GET api.github.com/repos/AnnaMarieM/Therapy_local/actions/artifacts` (anon) | 200, `total_count: 1913`, includes `MnemoThera-ios-unsigned.ipa` (1.7 MB) |
| `GET …/actions/artifacts/14215243851/zip` (anon) | **401** — artifacts need sign-in even on a public repo |
| `HEAD github.com/AnnaMarieM/Therapy_local/releases/download/main/Therapy-main-qa.apk` (anon) | **200**, `application/vnd.android.package-archive`, 138,172,882 bytes |
| `GET api.github.com/repos/AnnaMarieM/Therapy_local/releases` (anon) | 200 — tags `main`, `pr-114` with APK assets |
| `HEAD …/releases/latest/download/Therapy-main-qa.apk` (anon) | **404** — "latest" alias + per-PR asset names don't match; use explicit tag URLs |
| `GET annamariem.github.io/Therapy_local/` (anon) | 404 — no Pages site currently published |
