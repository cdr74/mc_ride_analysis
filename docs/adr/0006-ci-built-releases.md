# ADR 0006 — Releases built and published by GitHub Actions

Date: 2026-07-13 · Status: accepted · No app behavior change (devops only)

Until v0.3.2, releases were built locally and uploaded with `gh release create`. The
signature constraint (upgrades must install over each other, so every release must be
signed with the *same* debug keystore) tied releases to the one dev machine.

## Decision

- `.github/workflows/android.yml` runs `lint testDebugUnitTest assembleDebug` on every
  push/PR to `main` and uploads the APK as a build artifact.
- Pushing a `v<version>` tag additionally creates the GitHub release with
  `ridelogger-<version>.apk` attached. The workflow fails the tag build if the tag does
  not match `versionName`.
- Signature continuity is preserved by storing the dev machine's
  `~/.android/debug.keystore` (created 2026-07-11) as the repo secret
  `DEBUG_KEYSTORE_BASE64`; CI restores it before building, so CI-built APKs carry the
  same signature as all previous releases and local `installDebug` builds. A tag build
  without the secret fails hard rather than release a foreign signature.

## Alternatives considered

- **Verification-only CI, releases stay local** — safer (no keystore in secrets) but
  keeps the machine dependency; rejected by Chris 2026-07-13.
- **Proper release signing config** — over-engineering for a throwaway app whose users
  already have the debug-signed APK installed; a signature change would force everyone
  to uninstall/reinstall.

## iOS

Explicitly out of scope: no iOS project exists in this repo (the iOS logger is parked).
An iOS workflow additionally needs an Apple Developer account, signing
certificate/profile secrets, and a distribution path (TestFlight) — revisit when the
iOS logger lands.
