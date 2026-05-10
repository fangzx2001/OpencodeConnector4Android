# Changelog

All notable changes to OConnector will be documented in this file.

## [1.2.0] - 2026-05-10

### Added

- **Side panel** — swipe from right edge (or tap 📁 toolbar button) to open a file browser and model info panel. Navigate project directories, view model/provider details, and track context window usage.
- **Todo task panel** — overlay panel shows active AI task progress. Badge count in toolbar. Auto-dismisses and sends notification when all tasks complete.
- **Ask/Confirm bubbles** — when the AI needs permission (tool confirmation) or asks a question, inline bubbles appear between messages and input bar. Permission bubble has Allow Once / Always Allow / Reject. Question bubble has selectable options, custom text input, Submit/Dismiss. Input is blocked while AI waits for response.
- **Notification deep link** — tapping a notification now navigates directly to the active chat session.
- **Test infrastructure** — Robolectric + Compose test rule setup for UI testing.

### Fixed

- **Todo auto-dismiss** — panel auto-closes when all tasks are completed; completion notification sent.
- **Notification navigation** — session tracking ensures deep links open the correct chat.
- **Gradle daemon hang** — disabled daemon in `gradle.properties` to prevent shell timeout after builds.

### Changed

- Internal refactoring: `ChatUiState` split into `SessionMetaState`, `StreamingDisplayState`, `ChatDisplayState` for cleaner state management.
- New DTOs: `ToolRef`, `QuestionInfoDto`, `PermissionReplyPayload`, `QuestionReplyPayload` for ask/confirm protocol.
- 11 new i18n strings (EN + ZH) for permission and question bubble UI.

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
