# Changelog

All notable changes to OConnector will be documented in this file.

## [1.2.0] - 2026-05-11

### Added

- **Side panel** — swipe left anywhere on screen to open a file browser and model info panel. Navigate project directories, view model/provider details, and track context window usage.
- **File preview** — tap any `.md` or `.txt` file in the file browser to preview its content inline without leaving the chat. Uses `GET /file/content?path=` API.
- **Todo task panel** — overlay panel shows active AI task progress. Badge count in toolbar. Auto-dismisses and sends notification when all tasks complete.
- **Ask/Confirm bubbles** — when the AI needs permission (tool confirmation) or asks a question, inline bubbles appear between messages and input bar. Permission bubble has Allow Once / Always Allow / Reject. Question bubble has selectable options, custom text input, Submit/Dismiss. Input is blocked while AI waits for response.
- **Sequential question answering** — when the AI asks multiple questions at once, they are presented one at a time with Back/Next navigation. Progress bar shows current step. All answers collected and submitted together.
- **Sequential permission confirmation** — multiple permission requests are queued and presented one at a time. After confirming/rejecting one, the next appears automatically.
- **Multi-select questions** — questions with `multiple: true` allow selecting several options at once.
- **Undo/Redo** — undo the last user message (soft-hides messages + rolls back file changes on server). Redo restores reverted messages. Toolbar buttons appear when applicable. Undone text is restored to the input box for easy re-editing.
- **Per-prompt model selection** — choose a different AI model for each message using a nested `{ providerID, modelID }` selector. Sends `ModelRef` in the prompt request so the server uses the exact model you picked.
- **Context usage display** — shows the current context token consumption (e.g. "32K") alongside the model selector in the input bar, sourced from the latest assistant message's `input` tokens.
- **Manual update check button** — a refresh button on the connection page lets you manually trigger an update check at any time, in addition to the automatic startup check.
- **Update check mirror sources** — GitHub + `gh-proxy.com` CDN mirrors checked concurrently with Channel racing. If any source responds, the update is detected — no longer blocked by GitHub connectivity issues.
- **Version filtering** — update check skips pre-release versions (e.g. `v1_pr1`); only stable `vX.Y.Z` releases are considered.
- **Help page expansion** — added "Model Selection", "Context Usage", "Undo/Redo", and "Update Check" sections (EN/ZH).
- **Notification deep link** — tapping a notification now navigates directly to the active chat session.
- **Child session filtering** — optional toggle to hide sub-sessions from the project list when you only want root conversations.
- **Comprehensive API documentation** — `OPENCODE_API.md` documents all REST endpoints, SSE events, and data models.

### Fixed

- **Streaming message flicker/disappear** — AI response would briefly vanish then reappear after completion. Root cause: placeholder item and final message had different LazyColumn keys, causing destroy+recreate. Replaced with a unified placeholder that uses the real message ID, so LazyColumn performs an in-place content update with zero visual discontinuity.
- **Streaming stalls and blocks** — added watchdog timer to detect and recover from stalled SSE streams. `sendMessage()` now aborts stale state on re-entry, and `initialize()` detects incomplete assistant messages on app restart.
- **Model selector not persisting** — server expects nested `{ "model": { "providerID": "...", "modelID": "..." } }` format. Fixed `SendMessageRequest` to wrap model selection in `ModelRef` DTO.
- **Context usage inaccurate** — previous implementation summed all historical tokens (double-counting). Now extracts only the latest assistant message's `input` tokens via reverse scan, and resets properly on session switch.
- **Session.idle race condition** — `session.idle` was clearing state before messages finished loading. Added delayed state cleanup with retry-based message fetch and assistant message existence verification before committing the idle transition.
- **Panel swipe conflict** — gesture detection competed with LazyColumn scroll. Replaced with cumulative drag threshold (40dp) that works from anywhere on screen.
- **Right panel overlaps status bar** — added `WindowInsets.statusBars` padding.
- **Update check fails in China** — added concurrent mirror sources with Channel racing so at least one source is reachable.
- **Gradle daemon hang** — disabled daemon in `gradle.properties`.

### Changed

- **Chat input bar redesign** — three-section piano-key style bar: model selector segment | context usage indicator | expandable text input + send button. Compact when collapsed, full-featured when expanded.
- **SSE pipeline optimization** — EventBus buffer 256 (DROP_OLDEST). Delta events 16ms coalescing window. Heartbeat timeout 45s. Incremental `message.updated` processing for all roles.
- **Dual-channel refresh** — 3s message polling alongside SSE as safety net. Sessions page: SSE + 30s polling. Fallback polling only activates after 15s SSE silence.
- **Memory optimization** — message list capped at 150 messages. Streaming text truncated at 10K characters.
- **Smart auto-scroll** — only scrolls to bottom when user is already near the bottom. Scrolling up to read history no longer interrupted.
- **ExpandableSegment default** — code/text segments default to expanded, preventing "content disappeared" feeling.
- **Panel animation** — `FastOutSlowInEasing(350ms)` for smoother slide.
- Internal refactoring: `ChatUiState` split into `SessionMetaState`, `StreamingDisplayState`, `ChatDisplayState`.

## [1.1.2] - 2026-05-07

### Added

- **Background SSE stability** — foreground service with `dataSync` type keeps the SSE connection alive when the app is in the background. A persistent notification ("OConnector is running") is displayed to comply with Android 14+ requirements.
- **In-app update check** — the app automatically checks GitHub Releases for new versions when you open the connection page. If an update is available, a download icon (⬇) appears next to the language button.
- **Update dialog** — tapping the download icon shows the changelog and a download button. The APK is downloaded via DownloadManager and installed through the system installer.
- **Help page: "Check for Updates"** section added (EN/ZH).

### Fixed

- **API 26-28 compatibility** — `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` requires API 29+. Added SDK version check to fall back to the 2-param `startForeground()` on older devices.
- **i18n for update UI** — UpdateDialog now uses localized strings from `AppLocale` instead of hardcoded English text.

### Changed

- Help page version number now uses `BuildConfig.VERSION_NAME` instead of a hardcoded string.
- APK no longer tracked in the git repository — distributed via GitHub Releases instead.
- `release/` directory added to `.gitignore`.

## [1.1.1] - 2026-05-07

### Added

- **HTTPS (TLS) support** — new "Use HTTPS (TLS)" toggle on the connection screen. Enable it when connecting through an HTTPS reverse proxy (e.g. Lucky, Nginx, Caddy). The app will use `https://` instead of `http://` for all API and SSE requests.
- **Self-signed certificate support** — when TLS is enabled, an additional "Allow untrusted certificates" toggle appears. Turn it on to connect to servers using self-signed certificates (common in home LAN setups). Both settings are persisted across app restarts.

### Fixed

- **Chinese/non-ASCII path encoding** — loading sessions from directories containing Chinese characters (e.g. `C:\Users\...\论文阅读`) would fail with `Unexpected char 0x8bba in x-opencode-directory`. The `x-opencode-directory` HTTP header now properly URL-encodes the path value, making it compatible with RFC 7230 (ASCII-only headers).

## [1.1.0] - 2026-05-05

### Added

- **Multi-project auto-discovery** — the app now queries all known OpenCode projects in parallel and merges sessions from every project directory. Works regardless of which directory `opencode serve` was started from.

### Fixed

- **Dark mode** — was completely non-functional (theme wrapper was never applied to the activity). Added a manual 🌙/☀️ toggle button in the top bar, persisted in DataStore.
- **Todo button visibility** — the Todo button was hidden when the list was empty. Now always visible (badge when items exist, plain icon when empty). Also added `message.part.completed` SSE handler for real-time Todo updates.
- **Tool call rendering** — sequential tool calls were overwriting each other because `putSegment()` only matched by type. Added `callID` tracking so each tool call gets its own segment.
- **Out-of-memory crash** — 108MB allocation crash when loading long sessions. Added `?limit=50` to the messages API. Removed the broken `truncateLargeJsonStrings()` which corrupted JSON escape sequences and caused messages/sessions to disappear silently.
- **Session sorting** — sessions were sorted by creation time. Now sorted by last updated time.
- **Status bar color** — status bar and navigation bar now match the surface color in both light and dark modes.
- **SSE streaming freeze** — streaming responses would randomly stall and require manual refresh. Root cause: `trySend()` in `channelFlow` silently dropped SSE events when the main thread collector was busy. Replaced with `send()` which provides proper backpressure. Also batched per-delta logging (every 50th event) to reduce JNI overhead on the main thread.

### Changed

- Server startup script no longer requires starting from a specific directory.
- `listSessions()` now accepts an optional `scope` parameter for server-side project filtering.

## [1.0.0] - 2026-04-30

### Added

- Initial release.
- Connect to OpenCode server via IP + port.
- Browse projects and sessions.
- Real-time AI chat with SSE streaming.
- Tool call rendering (thinking bubbles, tool summaries).
- Todo task panel overlay.
- Agent picker (switch between AI agents).
- Session fork / delete.
- EN/ZH bilingual toggle.
- Password encrypted storage.
- SSE auto-reconnection with exponential backoff.
