# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added — Chat & Streaming Rewrite (see hermez-chat-rewrite.pdf)
- `StreamState` sealed interface — explicit state machine for the
  streaming lifecycle (Idle → Starting → Streaming → Completing → Idle,
  plus Cancelling and Failed). Replaces the ad-hoc boolean `isStreaming`
  flag with first-class cancellation semantics.
- `ChatStream.reattachStream` — restored the function BUG-12 deleted
  as "dead code". Now wired into the SSE reconnect protocol: on
  reconnect, ask the server what state the stream is in (active /
  completed / failed) and resume accordingly.
- `Last-Event-ID` header on SSE reconnect — the server resumes from
  the next event instead of replaying from the start (no duplicate
  tokens on flaky networks).
- `ChatViewModel.refresh(sessionId)` — manual refresh action that
  forces `loadSession` regardless of whether the session is already
  loaded. Wired to a Refresh icon button in the chat TopAppBar.
- `ChatViewModel.allMessages` — combined view of pending (optimistic)
  + persisted (server-authoritative) messages, deduped by
  content+timestamp. Pending user messages appear instantly; on
  stream completion they're replaced by the server's version with a
  real `message_id`.
- `MessageDao.getMessageCountFlow` — reactive message count for the
  "Load more (N)" indicator. Updates automatically when `loadSession`
  inserts new messages.
- `SessionRepository.loadSessionLock` (Mutex) — serializes concurrent
  `loadSession` calls from the stream-completion and stopStreaming
  paths.
- 46 new unit tests covering StreamState (10 tests), ChatStream
  reattach/cancel idempotency (18 tests, ktor-client-mock), and
  ChatViewModel pure-logic helpers (18 tests).
- Refresh icon button in chat TopAppBar (CHAT-9 fix).
- Spinner during the Starting state (chat.start in flight, no
  stream_id yet) — better UX than a blank screen.

### Added (previous)
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

### Changed — Chat & Streaming Rewrite
- **Single source of truth:** ChatViewModel.messages is now a derived
  StateFlow that flatMapLatests into `sessionRepository.getMessages()`.
  There is no separate in-memory list — Room is the only writer.
  Pagination adjusts the LIMIT clause via `_visibleCount`, not by
  prepending to a list. (CHAT-7 fix)
- **Server-authoritative persistence:** the client never invents
  message IDs. On stream completion, `loadSession` fetches the
  server's version (with real `message_id`s). The Room Flow observer
  picks up the new messages. (CHAT-2, CHAT-6 fix)
- **Optimistic UI:** user messages go into `pendingMessages` (in-memory
  StateFlow, NOT Room). If the stream fails to start, the pending
  message is removed — no orphan. (CHAT-6 fix)
- **Cancellation protocol:** `stopStreaming` and `handleCancellation`
  run cleanup in a `withContext(NonCancellable)` block — `cancelStream`
  is always called (server stops generating), `loadSession` fetches
  the partial response. (CHAT-1 fix)
- **StreamEnd via for-loop break:** replaced the `StreamEndSignal`
  exception-for-control-flow with a `for` loop and `break`. (CHAT-10 fix)
- **Reasoning uses StringBuilder:** same O(1) append pattern as content.
  (CHAT-5 fix)
- **Flicker-free transition:** the StreamingBubble stays visible during
  the Completing state (between StreamEnd and loadSession returning).
  (CHAT-3 fix)
- **Smart auto-scroll:** only scrolls if the user is already near the
  bottom (within 2 items of the end). (CHAT-8 fix)
- **Memoized normalizeMessageContent:** wrapped in `remember(message.content)`
  so JSON parsing only runs when content changes, not on every scroll
  frame. (CHAT-11 fix)
- **Idempotent cancelStream:** 404 (stream already gone) and 409
  (stream already completed) are treated as success. (CHAT-1 fix)
- **CancellationException re-thrown:** the SSE collector never swallows
  cancellation — it propagates to the channelFlow and terminates cleanly.
- `ChatMessage` and `ToolCallInfo` moved from `app/ui/chat` to
  `core/data` so `StreamState` (in `core/network`) can reference them.
  They're domain models and belong in core anyway.
- `persistLocalMessages` removed entirely — it was the source of CHAT-2
  (duplicates from fake IDs) and CHAT-6 (orphans on start failure).

### Changed (previous)
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

### Fixed — Chat & Streaming Rewrite (all 12 bugs from hermez-chat-rewrite.pdf)
- **CHAT-1** (Critical): Stop button no longer loses messages or leaves
  the server streaming. Cleanup runs in NonCancellable; cancelStream is
  called; loadSession fetches the partial response.
- **CHAT-2** (Critical): No more duplicate messages on navigation.
  Removed `persistLocalMessages` (the fake-ID path); only the server's
  real IDs are written to Room.
- **CHAT-3** (Critical): No more visible flicker on stream completion.
  The StreamingBubble stays visible during the Completing state until
  the persisted message arrives.
- **CHAT-4** (Critical): SSE reconnect no longer loses or duplicates
  tokens. `reattachStream` + `Last-Event-ID` header resume from the
  last event the client received.
- **CHAT-5** (High): `streamingReasoning` now uses StringBuilder (was
  O(N) per event → O(N²) total for long thinking traces).
- **CHAT-6** (High): No more orphaned user messages when startChat
  fails. Pending messages are in-memory only; removed if the stream
  fails to start.
- **CHAT-7** (High): `loadMoreMessages` results no longer get
  overwritten by the Room Flow observer. Pagination adjusts the LIMIT
  clause, not the list.
- **CHAT-8** (High): Auto-scroll no longer yanks the user back to the
  bottom when they've scrolled up to read older messages.
- **CHAT-9** (Medium): Manual refresh button added — forces loadSession
  to fetch new messages that arrived on the server while away.
- **CHAT-10** (Medium): Removed `StreamEndSignal` exception-for-control-
  flow. The SSE collector uses a `for` loop with `break`.
- **CHAT-11** (Medium): `normalizeMessageContent` no longer runs on
  every recomposition. Wrapped in `remember(message.content)`.
- **CHAT-12** (Low): Token event deduplication via `Last-Event-ID`
  cursor. Reconnects resume from the next event, not the start.

### Fixed (previous)
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
