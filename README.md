# Hermez

> Native Android client for self-hosted [hermes-webui](https://github.com/nesquena/hermes-webui).
> The phone is a control plane — your server keeps the brain.

[![Android CI](https://github.com/xeriomy/hermez/actions/workflows/android.yml/badge.svg)](https://github.com/xeriomy/hermez/actions/workflows/android.yml)
[![Release](https://github.com/xeriomy/hermez/actions/workflows/release.yml/badge.svg)](https://github.com/xeriomy/hermez/releases)

## What this is

Hermez lets you drive a remote `hermes-webui` instance from your phone: log in
with the server password, browse sessions, stream chat tokens in real time via
SSE, fork/rename/pin/archive sessions, upload file attachments, browse server
files, and pick model / workspace / profile — all from a Compose-first UI with
a slide-out navigation drawer. The server does the heavy lifting; the phone is
just a fast, private remote control.

- **Jetpack Compose + Material 3** — dark theme, slide-out drawer, Claude-style composer
- **Ktor 3 + OkHttp** for REST and SSE on a single shared client with shared cookie jar
- **Room** for offline cache — sessions and messages survive a dead tunnel
- **Local-first loading** — cached messages appear instantly; server fetch runs in background
- **Markdown rendering** — assistant responses render code blocks, lists, tables, links
- **File upload** — attach files via `POST /api/upload` (multipart/form-data)
- **File browser** — browse and preview files on your server
- **EncryptedSharedPreferences** + Android Keystore for the saved server URL
- **No Play Services dependency** — distributed via GitHub Releases APK

## Status

Pre-alpha, actively developed. Streaming validated against a live
`hermes-webui` server. All 10 implementation tasks are substantially complete.

## Roadmap

| #   | Task                                     | Status          |
| --- | ---------------------------------------- | --------------- |
| 1   | Scaffold project and baseline modules    | ✅ Done         |
| 2   | Wire networking layer and endpoint constants | ✅ Done     |
| 3   | Auth models and repository               | ✅ Done         |
| 4   | Session models and repository            | ✅ Done         |
| 5   | SSE chat stream layer                    | ✅ Done         |
| 6   | Compose UI shells and navigation         | ✅ Done         |
| 7   | Composer + session actions               | ✅ Done         |
| 8   | Workspace, files, server panels          | ✅ Done (files) |
| 9   | Offline cache and content observers      | ✅ Done         |
| 10  | Packaging, privacy, release signing      | ✅ Done         |

## Tech stack

| Concern        | Choice                                                   |
| -------------- | -------------------------------------------------------- |
| UI             | Jetpack Compose, Material 3 (dark theme)                 |
| Min/target SDK | 24 / 35 (Android 7.0+ through Android 15)               |
| Kotlin         | 2.0.21                                                   |
| AGP            | 8.7.3                                                    |
| Build          | Gradle 8.9 (KSP for Room)                               |
| Networking     | Ktor 3.0.3 (OkHttp engine, SSE plugin)                  |
| Serialization  | kotlinx.serialization 1.7.3                              |
| Local cache    | Room 2.6.1                                               |
| Secrets        | androidx.security:security-crypto 1.1.0-alpha06          |
| Markdown       | multiplatform-markdown-renderer-m3 0.28.0                |
| Distribution   | GitHub Releases APK (signed via CI secrets)              |

## Repository layout

```
hermez/
├── app/                              # Compose UI shell
│   ├── ui/chat/                      # ChatScreen + ChatViewModel (streaming, composer, attachments)
│   ├── ui/sessions/                  # SessionListScreen + ArchivedSessionsScreen
│   ├── ui/login/                     # LoginScreen (URL + password + Test/Connect)
│   ├── ui/settings/                  # SettingsScreen (server info, logout, clear cache)
│   ├── ui/workspace/                 # FileBrowserScreen + FilePreviewScreen
│   ├── ui/drawer/                    # AppDrawer (slide-out sidebar)
│   ├── ui/navigation/                # HermesNavHost (routes for all screens)
│   └── proguard-rules.pro
├── core/                             # Reusable Android library
│   ├── auth/                         # AuthRepository + EncryptedSharedPreferences
│   ├── data/                         # SessionRepository, ConfigRepository, WorkspaceRepository
│   ├── data/local/                   # Room entities, DAOs, AppDatabase
│   ├── network/                      # SharedHttpClient, ApiEndpoint, ChatStream (SSE), FriendlyError
│   └── proguard-rules.pro
├── .github/workflows/
│   ├── android.yml                   # CI: compile, test, debug APK
│   └── release.yml                   # Tag-triggered signed release publishing
├── gradle/wrapper/                   # Gradle wrapper jar + properties
├── build.gradle.kts                  # Root build (plugin versions)
├── settings.gradle.kts
└── gradle.properties                 # JVM heap, parallel, caching flags
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

## CI

Two workflows live in [`.github/workflows/`](.github/workflows/):

### `android.yml` — runs on every push and pull request

Single job: compile → unit tests → (lint on PRs) → debug APK. Cancels
superseded runs via `concurrency`, validates the Gradle wrapper checksum,
sets a 12-minute timeout.

### `release.yml` — tag-triggered release publishing

Triggers on `v*` tags (or manual dispatch). Builds a signed release APK,
computes a SHA-256 checksum, optionally reads release notes from
`CHANGELOG.md`, and creates a GitHub Release with the APK + checksum
attached. Pre-release tags (containing a dash) are auto-marked as
prerelease.

### Required secrets for release signing

Configure under **Settings → Secrets and variables → Actions → New repository secret**:

| Secret                     | Description                                              |
| -------------------------- | -------------------------------------------------------- |
| `HERMES_KEYSTORE_BASE64`   | `base64 -w0 hermes.jks` (binary keystore as text)        |
| `HERMES_KEYSTORE_PASSWORD` | Keystore password                                        |
| `HERMES_KEY_ALIAS`         | Key alias inside the keystore (e.g. `hermes`)            |
| `HERMES_KEY_PASSWORD`      | Password for that key alias                              |

### Cutting a release

```bash
git tag v0.2.0
git push origin v0.2.0
```

## Connecting to a server

1. Run `hermes-webui` on a machine you control.
2. Expose it via Cloudflare Tunnel or Tailscale (real HTTPS recommended).
3. Set `HERMES_WEBUI_PASSWORD` on the server.
4. Open Hermez, enter server URL + password.

The app persists the server URL in `EncryptedSharedPreferences`; the auth
cookie is held in a shared `AcceptAllCookiesStorage` for the lifetime of the
HTTP client. Cleartext HTTP is allowed for localhost/LAN/Tailscale connections.

## Upstream compatibility

Endpoint shapes are decoded tolerantly — unknown JSON fields are ignored,
missing fields default to `null`, and timestamps are handled as float Unix
epochs. The app has been tested against a live `hermes-webui` server.

## License

MIT.
