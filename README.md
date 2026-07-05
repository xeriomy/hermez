# Hermez

Android client for self-hosted [hermes-webui](https://github.com/nesquena/hermes-webui).

## Status

Early scaffold — Tasks 1-2 done. See [implementation plan](../.hermes/plans/2026-07-04-hermex-android.md).

## Distribution

GitHub Releases APK (primary). No Play Services dependency.

## Building

Requires Android Studio (AGP 8.7+) or Gradle 8.9+ with JDK 21.

```bash
./gradlew assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Release builds locally

Without signing env vars, `assembleRelease` falls back to the debug signing
config so the artifact is still installable on emulators. To produce a properly
signed release APK locally:

```bash
export HERMES_KEYSTORE_FILE=/path/to/hermes.jks
export HERMES_KEYSTORE_PASSWORD=...
export HERMES_KEY_ALIAS=...
export HERMES_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

## CI

Two workflows live in [`.github/workflows/`](.github/workflows/):

- **`android.yml`** — runs on every push and pull request. Runs lint + unit
  tests, then assembles both debug and release APKs and uploads them as
  artifacts (retained 7 / 14 days).
- **`release.yml`** — triggered by a `v*` tag (or manual dispatch). Builds a
  signed release APK, computes a SHA-256 checksum, and creates a GitHub
  Release with the APK and checksum attached.

### Required secrets for release signing

Configure these in **Settings → Secrets and variables → Actions → Secrets**:

| Secret                     | Description                                              |
| -------------------------- | -------------------------------------------------------- |
| `HERMES_KEYSTORE_BASE64`   | `base64`-encoded `.jks` keystore (so it survives YAML)   |
| `HERMES_KEYSTORE_PASSWORD` | Keystore password                                        |
| `HERMES_KEY_ALIAS`         | Key alias inside the keystore                            |
| `HERMES_KEY_PASSWORD`      | Password for that key alias                              |

Generate the base64 value with:

```bash
base64 -w0 hermes.jks  # linux
base64 -i hermes.jks   # macOS
```

### Optional variables

Configure in **Settings → Secrets and variables → Actions → Variables**:

| Variable            | Description                                                          |
| ------------------- | -------------------------------------------------------------------- |
| `RELEASE_NOTES_PATH`| Path to a markdown file whose contents become the release body. Defaults to `CHANGELOG.md` if present. |

### Cutting a release

```bash
git tag v0.2.0
git push origin v0.2.0
```

The `release.yml` workflow picks the tag up, builds the signed APK, and
publishes a GitHub Release. Pre-release tags (anything containing a dash,
e.g. `v1.0.0-rc1`) are automatically marked as pre-release on GitHub.

## Connecting

1. Run `hermes-webui` on a machine you control.
2. Expose it via Cloudflare Tunnel or Tailscale (real HTTPS required).
3. Set `HERMES_WEBUI_PASSWORD` on the server.
4. Open Hermez, enter server URL + password.

## License

MIT
