# Changelog

All notable changes to OConnector will be documented in this file.

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
