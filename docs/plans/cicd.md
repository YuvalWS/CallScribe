# CI/CD on GitHub Actions — plan

Status: implemented in `.github/workflows/ci.yml` (2026-11-23). Repo: public
[YuvalWS/CallScribe](https://github.com/YuvalWS/CallScribe). The signing secrets in
[§7](#7-what-you-must-do-on-github) are for you to add.

Verified locally: YAML parses; `signingReport` picks the release key when the four variables are set
and the debug key otherwise; `-PbuildNumber=42` gives versionCode 42 / versionName `0.1.42`; tests +
`assembleDebug` pass. Not verified yet: an actual GitHub run and actionlint (not run).

## 1. Goals
- Every PR into `dev` or `main`, and every push/merge to `dev` or `main`: run the unit tests
  (`:app:testDebugUnitTest`) and build an APK that you can download from the run page.
- Every push/merge to `main`: also build the **signed, R8-shrunk release APK** and publish it as a
  **GitHub Release** (tag + release notes from the commits since the previous release).
- Stay inside the **free** GitHub plan, also for a private repo.
- Nothing secret in the repo: the release keystore lives only in GitHub secrets (and your own backup).

## 2. Workflows
One workflow file, `.github/workflows/ci.yml`, two jobs:

| Job | Runs on | What it does |
|---|---|---|
| `build` | every trigger | JDK 21 + Android platform 37 → `:app:testDebugUnitTest` → APK → upload as workflow artifact. On PRs and `dev` it builds `assembleDebug` (fast, no R8). On a push to `main` it builds `assembleRelease` instead (R8 + release signing) — the debug APK is skipped there to save minutes. |
| `publish` | push to `main` only, after `build` succeeds | Downloads the release APK artifact, creates tag `v<versionName>`, writes release notes from `git log <previous tag>..HEAD`, and runs `gh release create` with the APK attached. No Gradle, ~15 s. |

Why one file / two jobs: tests run once per event (no duplicate workflow on `main`), and only the tiny
`publish` job gets `contents: write`; the build job (which runs PR code) stays read-only.

Other details:
- **Triggers**: `pull_request` to `dev`/`main`, `push` to `dev`/`main`, plus `workflow_dispatch`
  (manual run → debug build, never a release). Changes that touch only `*.md` / `docs/**` are skipped
  (`paths-ignore`) to save minutes — a docs-only merge to `main` therefore does not cut a release.
- **Concurrency**: a newer push to the same PR cancels the older run; runs on `dev`/`main` are never
  cancelled (each `main` run is a release).
- **Caching**: `gradle/actions/setup-gradle` caches the Gradle distribution, dependencies and the build
  cache. Cache entries are written by runs on `dev` and `main`; PR runs only read them.
- **Android SDK**: the `ubuntu-latest` image ships an Android SDK with accepted licences; the workflow
  runs `sdkmanager "platforms;android-37.0"` (plus build-tools 36) so a missing platform is installed
  (a no-op when the image already has it).
- **JDK 21** via `actions/setup-java` (Temurin), same as the local build.
- **Artifacts**: debug APK ≈ 118 MB, kept **3 days**; release APK ≈ 33 MB, kept 1 day (it lives
  forever on the Release page anyway).
- Action versions pinned to current major tags (checkout v7, setup-java v6, setup-gradle v6,
  upload-artifact v7, download-artifact v8).

## 3. Signing
- `app/build.gradle.kts` reads four environment variables: `CALLSCRIBE_KEYSTORE_FILE`,
  `CALLSCRIBE_KEYSTORE_PASSWORD`, `CALLSCRIBE_KEY_ALIAS`, `CALLSCRIBE_KEY_PASSWORD`. If the keystore file
  exists, `release` is signed with it; otherwise it falls back to the debug key (today's behaviour, so
  local builds and forks keep working unchanged).
- In CI the `build` job decodes secret `CALLSCRIBE_KEYSTORE_BASE64` into `$RUNNER_TEMP/release.jks`
  (deleted with the runner) and sets the variables from the other three secrets.
- **Missing secrets → debug signing**, and the release notes then start with a warning: such APKs are
  signed with that runner's throw-away debug key, so they cannot be installed as an update over each
  other (uninstall first). Forks without secrets still build and test.
- Never commit a keystore: `*.jks` / `*.keystore` are already in `.gitignore`. Keep a backup of the
  `.jks` + passwords outside the repo (password manager). **If you lose it you can never ship an update
  to existing installs again.**
- **One-time reinstall**: the APK on your phone today is signed with your PC's debug key. The first
  CI release is signed with the new release key, so Android refuses it as an update — uninstall
  CallScribe once (this deletes its local data: call list cache, transcripts, settings, API keys), then
  install the release. From then on every release installs as an update.
  You can also sign local release builds with the same key by setting the four variables in your shell.

## 4. Versioning
- `gradle.properties` holds the human part: `callscribe.version=0.1` (bump by hand for 0.2, 1.0…).
- Gradle properties `-PbuildNumber=N` and `-PversionSuffix=…` give `versionCode = N` and
  `versionName = 0.1.N<suffix>`. Without them (local builds): `versionCode = 1`, `versionName = 0.1.0-local`.
- CI computes N as **the highest release tag's number + 1** (`git ls-remote --tags`), so releases are consecutive
  (`v0.1.13`, `v0.1.14`, …). Builds that aren't releases (dev, PRs) get the same N — the release they lead up to —
  with the suffix `-dev.<run>`, e.g. `0.1.14-dev.15`; they never number past the next release.
- Main runs never overlap (one concurrency group per branch, not cancelled), so two releases can't take the same N.
- Tag = `v<versionName>`, e.g. `v0.1.12`, pointing at the commit that was built.

## 5. Will it work on the FREE plan?
Numbers from GitHub's billing docs (read 2026-11-23):

| | Public repo | Private repo |
|---|---|---|
| Actions minutes | **free and unlimited** on standard runners | **$12/month of included compute** ≈ **2,000 min** of Linux 2-core at $0.006/min |
| `ubuntu-latest` runner | 4 CPU, 16 GB RAM | 2 CPU, 8 GB RAM |
| Artifact storage | free | 500 MB-month included (accrues hourly; shared with Packages) |
| Cache | 10 GB per repo | 10 GB per repo |

- GitHub no longer uses "multipliers"; each runner type has a price per minute: Linux 2-core $0.006,
  Windows 2-core $0.010 (≈1.7×), macOS $0.062 (≈10×). This workflow uses **Linux only**.
- Each job is rounded **up** to a whole minute.
- With no payment method on file, Actions simply stop when the included amount is used up — no
  surprise bill. Releases (and their APKs) do not count towards artifact storage.
- Branch protection / required checks are free for **public** repos only; a free private repo can run
  CI but cannot *enforce* "checks must pass before merge".

**Estimated minutes per run** (private 2-core runner; local Windows R8 build ≈ 10 min):

| Run | Cold cache | Warm cache |
|---|---|---|
| PR / `dev` (tests + debug APK) | ~8–10 min | ~4–6 min |
| `main` (tests + R8 release) + `publish` | ~18–22 min | ~12–16 min (+1 min publish) |

These are estimates; check the real numbers on the first runs (Actions tab → run → "Usage").

**How many runs fit in 2,000 min/month** (warm cache, rounded up): ~6 min per PR/dev run and
~17 min per release → e.g. **150 PR/dev runs + 30 releases ≈ 1,410 min**. Debug-only alone: ~330 runs.
Release-only alone: ~115.

**Artifact storage**: one debug APK kept 3 days ≈ 0.118 GB × 72 h ≈ 8.5 GB-hours; 500 MB-month ≈
360 GB-hours → ~40 debug runs/month inside the free storage (plus small release artifacts). If you
exceed that, lower `retention-days` in `ci.yml` to 1, or delete old artifacts in the Actions tab.

**Verdict**: yes. **CallScribe's repo is public** ([YuvalWS/CallScribe](https://github.com/YuvalWS/CallScribe)),
so minutes and artifact storage are free and unlimited, and it runs on the faster 4-CPU runner (expect
roughly half the times above). The limits that remain are per job (6 h max) and the 10 GB cache.
If the repo ever goes private: comfortably within the free minutes at a hobby pace, and artifact
storage becomes the tighter limit (~40 debug APK runs a month) because the unshrunk debug APK is 118 MB.

## 6. Branch strategy
`feature/*` → PR into `dev` → (CI: tests + debug APK) → PR `dev` → `main` → (CI: tests + signed release
+ GitHub Release). `main` is always the latest release; `dev` is the integration branch.

## 7. What you must do on GitHub
Already done: the public repo [YuvalWS/CallScribe](https://github.com/YuvalWS/CallScribe) exists with
`main` and `dev` pushed. Left for you — no secret was added for you. Run in PowerShell from the repo
root, logged in with `gh auth login`.

Secrets in a public repo are safe: they are never shown in logs, and PRs from forks don't receive them
(those PRs only build debug APKs anyway).

1. *(done)* Create the repo and push `main`.
2. *(done)* Create and push `dev`.
3. Make `dev` the default branch, so PRs target it by default (optional):
   `gh repo edit YuvalWS/CallScribe --default-branch dev`. If you do, the Gradle cache is still written
   by `dev` and `main` runs (configured explicitly in `ci.yml`).
4. **Create the release keystore** (once, *outside* the repo; you will be asked for passwords and a name): If `keytool` is not on your PATH, use its full path inside the JDK's `bin` folder.
   ```powershell
   keytool -genkeypair -v -keystore "$HOME\callscribe-release.jks" -alias callscribe -keyalg RSA -keysize 4096 -validity 10000
   ```
   Back up `callscribe-release.jks` and both passwords in a password manager.
5. **Add the four secrets** (`gh secret set` without `--body` prompts, so passwords stay out of history):
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\callscribe-release.jks")) | gh secret set CALLSCRIBE_KEYSTORE_BASE64
   gh secret set CALLSCRIBE_KEYSTORE_PASSWORD
   gh secret set CALLSCRIBE_KEY_ALIAS --body callscribe
   gh secret set CALLSCRIBE_KEY_PASSWORD
   ```
   (With a PKCS12 keystore — keytool's default — the key password equals the store password.)
6. **Check**: `gh secret list` shows four names. The first release appears with the next merge of
   code changes into `main` (`gh run list` to follow it).
7. **Phone**: uninstall the debug-key build once, then install the APK from the release (§3).
8. **Protect `main` and `dev`** (free on a public repo): Settings → Rules → Rulesets → New branch
   ruleset → target `main` and `dev` → *Require a pull request before merging* and *Require status
   checks to pass* → add the `build` check (it shows up after the first run).

## 8. Out of scope / later
- Instrumented (on-device) tests — they need an emulator (slow, expensive on private runners).
- Lint in CI (adds minutes; can be added to the PR job later).
- Play Store upload / AAB.
