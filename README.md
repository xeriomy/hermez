# Hermez

> Native Android client for self-hosted [hermes-webui](https://github.com/nesquena/hermes-webui).
> The phone is a control plane — your server keeps the brain.

[![Android CI](https://github.com/xeriomy/hermez/actions/workflows/android.yml/badge.svg)](https://github.com/xeriomy/hermez/actions/workflows/android.yml)
[![Release](https://github.com/xeriomy/hermez/actions/workflows/release.yml/badge.svg)](https://github.com/xeriomy/hermez/releases)

## What this is

Hermez lets you drive a remote `hermes-webui` instance from your phone: log in
with the server password, browse sessions, stream chat tokens in real time via
SSE, fork/rename/pin/archive sessions, and pick model / reasoning / workspace /
profile — all from a Compose-first UI. The server does the heavy lifting; the
phone is just a fast, private remote control.

- **Single-screen Compose modules** — login, session list, chat. No fragments.
- **Ktor + OkHttp** for REST and SSE on a single shared client.
- **Room** for offline cache — sessions and messages survive a dead tunnel.
- **EncryptedSharedPreferences** + Android Keystore for the saved server URL.
- **No Play Services dependency** — distributed via GitHub Releases APK.

## Status

Pre-alpha. Tasks 1–5 of the [implementation plan](#roadmap) are in place
(scaffold, networking, auth, sessions, SSE stream). UI shells (Task 6+) and
composer/actions (Task 7) are stubs. Streaming has not been validated against
a live `hermes-webui` server yet — see [Roadmap](#roadmap).

## Roadmap

Tracking the 10-task plan in `.hermes/plans/2026-07-04-hermex-android.md`.

| #   | Task                                     | Status      |
| --- | ---------------------------------------- | ----------- |
| 1   | Scaffold project and baseline modules    | ✅ Done     |
| 2   | Wire networking layer and endpoint constants | ✅ Done |
| 3   | Auth models and repository               | ✅ Done     |
| 4   | Session models and repository            | ✅ Done     |
| 5   | SSE chat stream layer                    | ✅ Done     |
| 6   | Compose UI shells and navigation         | 🚧 Stubbed  |
| 7   | Composer + session actions               | ⏳ Pending  |
| 8   | Workspace, files, server panels          | ⏳ Pending  |
| 9   | Offline cache and content observers      | ⏳ Partial  |
| 10  | Packaging, privacy, release signing      | ✅ Done     |

## Tech stack

| Concern        | Choice                                                   |
| -------------- | -------------------------------------------------------- |
| UI             | Jetpack Compose, Material 3                              |
| Min/target SDK | 24 / 35 (Android 7.0+ through Android 15)               |
| Kotlin         | 2.0.21                                                   |
| AGP            | 8.7.3                                                    |
| Build          | Gradle 8.9 (KSP for Room)                               |
| Networking     | Ktor 3.0.3 (OkHttp engine, SSE plugin)                  |
| Serialization  | kotlinx.serialization 1.7.3                              |
| Local cache    | Room 2.6.1                                               |
| Secrets        | androidx.security:security-crypto 1.1.0-alpha06          |
| Images         | Coil Compose 2.6.0                                       |
| Distribution   | GitHub Releases APK (signed via CI secrets)              |

## Repository layout

```
hermez/
├── app/                          # Compose UI shell (MainActivity, screens)
│   └── proguard-rules.pro        # App-level R8/ProGuard rules
├── core/                         # Reusable Android library
│   ├── auth/                     # AuthRepository + EncryptedSharedPreferences
│   ├── data/                     # SessionRepository + Room entities/DAOs
│   └── network/                  # Ktor client, ApiEndpoint, ChatStream (SSE)
│   └── proguard-rules.pro        # Consumer R8 rules for core
├── .github/workflows/
│   ├── android.yml               # CI: lint, tests, debug + release APK
│   └── release.yml               # Tag-triggered signed release publishing
├── gradle/wrapper/               # Gradle wrapper jar + properties
├── build.gradle.kts              # Root build (plugin versions only)
├── settings.gradle.kts
└── gradle.properties             # JVM heap, parallel, caching flags
```

## Building

Requires **JDK 21** and either Android Studio Koala+ or Gradle 8.9+ on the
command line.

```bash
./gradlew :app:assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Release builds locally

Without signing env vars, `:app:assembleRelease` falls back to the debug
signing config so the artifact is still installable on emulators. To produce
a properly signed release APK locally:

```bash
export HERMES_KEYSTORE_FILE=/path/to/hermes.jks
export HERMES_KEYSTORE_PASSWORD=...
export HERMES_KEY_ALIAS=...
export HERMES_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

### Verifying the build chain

```bash
./gradlew lintDebug testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

## CI

Two workflows live in [`.github/workflows/`](.github/workflows/):

### `android.yml` — runs on every push and pull request

Three jobs, with the latter two depending on the first:

1. **Lint & Unit Tests** — `lintDebug` + `testDebugUnitTest` + `testReleaseUnitTest`.
   Lint is currently non-fatal (`abortOnError = false`) because the UI is still
   a stub; promote to fatal once Task 6 lands.
2. **Debug APK** — `:app:assembleDebug`, uploaded as a 7-day artifact.
3. **Release APK** — `:app:assembleRelease`, signed if secrets are present,
   uploaded as a 14-day artifact.

Cancels superseded runs on the same ref via `concurrency`, runs with
`permissions: contents: read`, validates the Gradle wrapper checksum, and
sets hard per-job timeouts (15 / 20 / 25 min).

### `release.yml` — tag-triggered release publishing

Triggers on `v*` tags (or manual dispatch with an existing tag). Builds a
signed release APK, computes a SHA-256 checksum, optionally reads release
notes from `CHANGELOG.md` (or `RELEASE_NOTES_PATH`), and creates a GitHub
Release with the APK + checksum attached. Pre-release tags (anything
containing a dash, e.g. `v1.0.0-rc1`) are auto-marked as prerelease.

### Required secrets for release signing

Configure under **Settings → Secrets and variables → Actions → New repository secret**:

| Secret                     | Description                                              |
| -------------------------- | -------------------------------------------------------- |
| `HERMES_KEYSTORE_BASE64`   | `base64 -w0 hermes.jks` (binary keystore as text)        |
| `HERMES_KEYSTORE_PASSWORD` | Keystore password                                        |
| `HERMES_KEY_ALIAS`         | Key alias inside the keystore (e.g. `hermes`)            |
| `HERMES_KEY_PASSWORD`      | Password for that key alias                              |

Generate the keystore once (and **back up the `.jks` file somewhere safe** —
losing it means users can't upgrade):

```bash
keytool -genkeypair -v \
  -keystore hermes.jks \
  -alias hermes \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Hermez, O=xeriomy, C=TR"

base64 -w0 hermes.jks   # paste output as HERMES_KEYSTORE_BASE64 secret
```

### Optional variables

| Variable              | Description                                                              |
| --------------------- | ------------------------------------------------------------------------ |
| `RELEASE_NOTES_PATH`  | Path to a markdown file whose contents become the release body. Defaults to `CHANGELOG.md` if present. |

### Cutting a release

```bash
git tag v0.2.0
git push origin v0.2.0
```

The `release.yml` workflow picks the tag up, builds the signed APK, and
publishes a GitHub Release. See [CHANGELOG.md](CHANGELOG.md) for the
version history.

## Connecting to a server

1. Run `hermes-webui` on a machine you control.
2. Expose it via Cloudflare Tunnel or Tailscale (real HTTPS required).
3. Set `HERMES_WEBUI_PASSWORD` on the server.
4. Open Hermez, enter server URL + password.

The app persists the server URL in `EncryptedSharedPreferences`; the auth
cookie is held in Ktor's `AcceptAllCookiesStorage` for the lifetime of the
HTTP client.

## Upstream compatibility

Hermez is tested against the `hermes-webui` commit pinned in
`UPSTREAM_TESTED_SHA` (to be added before v1.0). Endpoint shapes are decoded
tolerantly — unknown JSON fields are ignored, and missing fields default to
`null` rather than crashing.

## License

MIT. See [LICENSE](LICENSE) (or the SPDX header in each source file).
