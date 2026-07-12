# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Slide-out navigation drawer (Claude/Gemini style) with recent sessions,
  new chat button, archived sessions, settings, and server info
- Chat streaming with SSE — tokens stream in real time, persisted to Room
  on stream completion
- Claude-style composer with bordered surface, + button (upload + options),
  model chip, send/stop button
- File upload via `POST /api/upload` (multipart/form-data) with attachment
  preview chips
- File browser — browse directories and preview text files on the server
- Session actions — rename, delete, pin, archive (with dialogs)
- Archived sessions screen with unarchive/delete
- Settings screen — server URL, logout (with confirmation), clear cache
- Config pickers — model, workspace, profile (fetched from server)
- Markdown rendering for assistant messages (code blocks, lists, tables)
- Message selection (native Android text selection) + copy action buttons
- Smart auto-scroll (only scrolls if near bottom)
- Long-press to copy message text
- Timestamps on message bubbles
- "Load more" pagination for older messages
- Custom app icon (Hermex logo with wing detail on purple background)
- Dark theme (Material 3 dark color scheme)
- Edge-to-edge with proper status bar + navigation bar padding
- Friendly error messages (DNS, connection refused, timeout, TLS, HTTP status)

### Changed
- Chat streaming uses separate streamingContent StateFlow (O(1) per token)
  instead of O(N) list copy + Markdown re-parse
- Message loading is local-first (Room Flow observer) — messages appear
  instantly from cache, server fetch runs in background
- getMessages query returns newest N (not oldest N) via subquery
- MessageEntity primary key changed from autoGenerate Int to messageId
  (deduplication via OnConflictStrategy.REPLACE)
- loadSession no longer wipes cached messages before re-inserting
- SharedHttpClient — single process-wide HttpClient with shared cookie storage
- Network security config — system CAs only (removed user CA trust)
- CI merged from 3 jobs to 1 (faster, ~5 min)
- versionCode uses GITHUB_RUN_NUMBER for monotonic increases

### Fixed
- Duplicate messages on chat reopen (messageId is now the primary key)
- Streamed messages vanishing on navigation (persisted to Room on stream end)
- Archived sessions wiped on refresh (preserve local archived/pinned state)
- App crashing on launch (ViewModels converted to AndroidViewModel)
- Login not working (ViewModel scoping bug — repositories passed from Activity)
- Cleartext HTTP blocked by Android network security policy
- URL parsing failures for bare host:port (auto-prepend http://)
- JSON timestamp parse errors (timestamps are floats, not Longs)
- Content going under status bar (enableEdgeToEdge + statusBarsPadding)

## [0.1.0] — 2026-07-05

Initial buildable scaffold. Tasks 1–5 and 10 of the implementation plan.

[Unreleased]: https://github.com/xeriomy/hermez/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/xeriomy/hermez/releases/tag/v0.1.0
