# Contributing to Hermez

Thanks for your interest in contributing! Hermez is a native Android client for the self-hosted [hermes-webui](https://github.com/nesquena/hermes-webui) server.

## Build

Requires **JDK 21** and Gradle 8.9+ (or Android Studio Koala+).

```bash
./gradlew :app:assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Code style

- Kotlin, Jetpack Compose, Material 3
- Dark theme only (for now)
- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- 4-space indentation, no tabs

## Pull requests

1. Fork the repo and create a branch: `git checkout -b my-feature`
2. Make your changes. Test against a running `hermes-webui` server if your change touches networking.
3. Run `./gradlew :app:compileDebugKotlin` to verify it compiles.
4. Open a PR with a clear description of what changed and why.

## Upstream compatibility

Hermez talks to the [hermes-webui](https://github.com/nesquena/hermes-webui) HTTP API. Do not invent endpoints — verify against the running server or the server source. All `Codable`/`@Serializable` models decode tolerantly (unknown fields are ignored, missing fields default to `null`).

## Testing

There are currently no tests (see audit finding DX-1). If you're adding a feature, consider adding a unit test for the repository or ViewModel you're touching. The `ktor-client-mock` and `kotlinx-coroutines-test` dependencies are already declared in `core/build.gradle.kts`.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
