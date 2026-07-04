# Hermez

Android client for self-hosted [hermes-webui](https://github.com/nesquena/hermes-webui).

## Status

Early scaffold — Tasks 1-2 done. See [implementation plan](../.hermes/plans/2026-07-04-hermex-android.md).

## Distribution

GitHub Releases APK (primary). No Play Services dependency.

## Building

Requires Android Studio (AGP 8.7+) or Gradle 8.9+.

```bash
./gradlew assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Connecting

1. Run `hermes-webui` on a machine you control.
2. Expose it via Cloudflare Tunnel or Tailscale (real HTTPS required).
3. Set `HERMES_WEBUI_PASSWORD` on the server.
4. Open Hermez, enter server URL + password.

## License

MIT