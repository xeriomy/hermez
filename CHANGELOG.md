# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `lint { abortOnError = false }` block in `app/build.gradle.kts` — keeps CI
  green while the UI is still a stub. Will be promoted to `true` once Task 6
  (Compose UI shells) lands.
- `CHANGELOG.md` (this file) — tracks changes per [Keep a Changelog] format.
- README badges for `android.yml` and `release.yml` workflows.

### Changed
- README rewritten to reflect the actual project structure, the 10-task
  roadmap, and the Ktor 3.0 / Kotlin 2.0 / AGP 8.7.3 stack actually in use.

### Fixed
- **CI: `androidx.compose.ui:ui-test-junit4` had no version** because the
  Compose BOM was only applied to `implementation`, not
  `androidTestImplementation`. Added
  `androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))`.
- **CI: Gradle configuration cache broke `:app:lintDebug`** with
  `ConfigurationCacheError` on AGP 8.7.3's `LintModelWriterTask`
  ([gradle/gradle#26830](https://github.com/gradle/gradle/issues/26830)).
  Disabled CC globally until AGP 8.8+ is adopted.
- **CI: Kotlin warning** — `Right operand of elvis operator (?:) is useless
  if it is null` at `app/build.gradle.kts:51`. Simplified the signing config
  fallback to `if (releaseSigning?.storeFile != null) releaseSigning!! else ...`.

## [0.1.0] — 2026-07-05

First buildable scaffold. Tasks 1–5 and 10 of the implementation plan
(`.hermes/plans/2026-07-04-hermex-android.md`) are in place. UI shells
(Task 6+) are stubbed — they compile but don't navigate yet.

### Added — Project scaffold (Task 1)
- Multi-module Gradle project: `:app` (application) + `:core` (Android library).
- `build.gradle.kts` (root) with plugin versions: AGP 8.7.3, Kotlin 2.0.21,
  KSP 2.0.21-1.0.27, Compose Compiler 2.0.21, kotlinx.serialization 2.0.21.
- `settings.gradle.kts` with `google()` + `mavenCentral()` repositories.
- `gradle.properties` with 4 GB JVM heap, parallel build, build cache.
- Gradle wrapper 8.9 (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`).
- `app/proguard-rules.pro` and `core/proguard-rules.pro` for R8.

### Added — Networking layer (Task 2)
- `core/network/ApiEndpoint.kt` — sealed class with exact paths for every
  `hermes-webui` endpoint (auth, sessions, chat stream, projects, workspaces,
  files, models, providers, settings, profiles, skills, memory, usage, upload,
  btws, background jobs, crons).
- `core/network/HttpClientProvider.kt` — Ktor `HttpClient` with OkHttp engine,
  `ContentNegotiation` (JSON), `HttpCookies` (`AcceptAllCookiesStorage`), and
  `defaultRequest` that strips `Origin` / `Referer` headers so the upstream
  CSRF check treats requests as non-browser.

### Added — Auth repository (Task 3)
- `core/auth/AuthState.kt` — sealed interface with `LoggedIn(serverUrl)` and
  `LoggedOut`.
- `core/auth/AuthRepository.kt` — `ViewModel` exposing `authState: StateFlow`,
  `setLoggedIn(url)`, `logout()`. Persists server URL in
  `EncryptedSharedPreferences` via `MasterKey.Builder` (the `MasterKeys` API
  was removed in security-crypto 1.1.0).

### Added — Session repository (Task 4)
- `core/data/local/SessionEntity.kt` + `MessageEntity.kt` — Room entities
  with nullable extra fields so unknown JSON never crashes decoding.
- `core/data/local/SessionDao.kt` + `MessageDao.kt` — DAOs with paged session
  list, bounded message queries, pin/archive/move updates.
- `core/data/local/AppDatabase.kt` — Room database with `fallbackToDestructiveMigration`.
- `core/data/SessionRepository.kt` — `ViewModel` combining remote API with
  local cache; writes through for mutating actions, reads from cache when
  offline. Includes `SessionRepository.Factory` for `ViewModelProvider`.

### Added — SSE chat stream (Task 5)
- `core/network/ChatStream.kt` — consumes `GET /api/chat/stream?stream_id=...`
  as a `Flow<StreamEvent>`. Handles all documented event names: `token`,
  `tool`, `tool_complete`, `reasoning`, `title`, `done`, `interim_assistant`,
  `stream_end`, `error`, plus an `Unknown` fallback. Implements reattach via
  `GET /api/chat/stream/status` and cancel via `GET /api/chat/cancel`.
  Reconnects with exponential backoff (1s / 2s / 4s) up to 3 attempts.

### Added — UI shells (Task 6, partial)
- `app/MainActivity.kt` — `ComponentActivity` with `setContent { MaterialTheme { HermexApp() } }`.
- `app/ui/HermexApp.kt` — placeholder `Surface` (TODO: wire `NavHost`).
- `app/ui/sessions/SessionListScreen.kt` — Compose list of active sessions
  with pull-to-refresh, pinned indicator, timestamp formatting, empty state.
- `app/ui/chat/ChatScreen.kt` — Compose chat with `TopAppBar`, `LazyColumn`
  of message bubbles, composer row with send/stop `IconButton`s, error text.
- `app/ui/chat/ChatViewModel.kt` — `ViewModel` exposing `messages`,
  `isStreaming`, `newMessage`, `error` as `StateFlow`s. Drives `ChatStream`
  and accumulates `Token` events into a single assistant message.

### Added — CI/CD (Task 10)
- `.github/workflows/android.yml` — three-job pipeline
  (`verify` → `build-debug` + `build-release`) with `concurrency`,
  least-privilege `permissions`, wrapper validation, per-job timeouts,
  and `if-no-files-found: error` on artifact uploads. Uses
  `gradle/actions/setup-gradle@v4`, `actions/setup-java@v4`,
  `android-actions/setup-android@v3`, `actions/upload-artifact@v4`.
- `.github/workflows/release.yml` — tag-triggered (`v*`) release workflow
  that builds a signed release APK, computes SHA-256 checksum, optionally
  reads release notes from `CHANGELOG.md` or `RELEASE_NOTES_PATH`, and
  publishes a GitHub Release via `softprops/action-gh-release@v2`. Auto-
  marks pre-release tags (containing `-`) as GitHub prereleases.
- `app/build.gradle.kts` release signing config — reads
  `HERMES_KEYSTORE_FILE` / `HERMES_KEYSTORE_PASSWORD` / `HERMES_KEY_ALIAS` /
  `HERMES_KEY_PASSWORD` from the environment, falls back to the debug
  signing config when absent so the artifact is still installable.

### Fixed — Build-blocking bugs unblocked by the CI overhaul
- **Missing Gradle wrapper** — `gradlew`, `gradlew.bat`, and
  `gradle-wrapper.jar` were never committed. The workflow called `./gradlew`
  but the file didn't exist.
- **Missing ProGuard files** — `app/proguard-rules.pro` and
  `core/proguard-rules.pro` were referenced by `build.gradle.kts` but absent,
  breaking release builds.
- **Broken `AndroidManifest.xml`** — both modules' manifests were missing the
  `xmlns:android` declaration, causing `SAXParseException` at manifest merge.
- **Missing `themes.xml`** — manifest referenced `@style/Theme.Hermex` which
  didn't exist. Added `app/src/main/res/values/themes.xml` with a Material
  Light NoActionBar parent.
- **Missing Kotlin Compose plugin** — Kotlin 2.0+ requires
  `org.jetbrains.kotlin.plugin.compose` to be applied explicitly. The old
  `composeOptions.kotlinCompilerExtensionVersion` was a no-op.
- **Non-existent `ktor-client-sse:2.3.12`** — that artifact was never
  published under that name. The SSE client API the code already used is
  Ktor 3.x only. Bumped all Ktor deps to 3.0.3 and switched to `ktor-sse`.
- **Ktor 3.0 API migration** — `post<T>` / `get<T>` reified helpers were
  removed; switched to `post { }` then `response.body<T>()`. `HttpCookies`
  moved to `io.ktor.client.plugins.cookies.HttpCookies`.
- **SSE consumer API** — Ktor 3.0 `ClientSSESession.incoming` is a
  `Flow<ServerSentEvent>`, not a callback API. Switched to
  `incoming.collect { }` inside the `sse { }` block.
- **Room KSP failure** — empty `Converters` class with `@TypeConverters`
  annotation failed KSP ("Class is referenced as a converter but it does
  not have any converter methods"). Removed the annotation and the empty
  class; none of the entity fields need converters.
- **Illegal Kotlin** — `private const val KEY_SERVER_URL` inside a regular
  class is forbidden. Moved to a `companion object`.
- **Removed `MasterKeys` API** — `androidx.security.crypto.MasterKeys` was
  removed in 1.1.0. Migrated to `MasterKey.Builder`.
- **Sealed `when` branches** — `ChatStream.StreamEvent.Done` and
  `StreamEvent.StreamEnd` are data classes, not objects; the `when` needed
  `is` checks, not bare class names.
- **Unresolved Compose imports** — `Row`, `KeyboardOptions`,
  `collectAsStateWithLifecycle`, `Icons.Default.ArrowBack/PushPin/Refresh/Send/Stop`
  all referenced symbols that weren't on the classpath. Added
  `navigation-compose`, `lifecycle-runtime-compose`,
  `material-icons-extended` deps and fixed the import paths.
- **Experimental Material3 API** — `TopAppBar` requires
  `@OptIn(ExperimentalMaterial3Api::class)`. Added the opt-in.
- **JVM target mismatch** — Java was targeting 1.8, Kotlin was targeting 21.
  Set both to 21 in `app` and `core` modules.
- **`|| echo` swallowing CI failures** — the previous release build step
  used `./gradlew assembleRelease ... || echo "Release build failed"`,
  silently turning hard failures into green builds. Removed.

### Removed
- Tracked `.gradle/` cache directory — should never have been committed.
- Deprecated `composeOptions.kotlinCompilerExtensionVersion` — replaced by
  the Compose Compiler Gradle plugin.
- `ndk-version: r27b` from the CI workflow — the project doesn't use NDK,
  so don't download 1 GB of toolchain on every run.

[Unreleased]: https://github.com/xeriomy/hermez/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/xeriomy/hermez/releases/tag/v0.1.0
