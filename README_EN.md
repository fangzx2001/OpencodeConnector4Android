English | [中文](README.md)

# OConnector

An Android client that connects to an OpenCode AI coding assistant running on your PC. Chat with AI, manage coding sessions, and track task progress from your phone.

![Android](https://img.shields.io/badge/Android-8.0%2B-34A853?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)
![License](https://img.shields.io/badge/License-MIT-blue)

---

## Table of Contents

- [Download & Install](#download--install)
- [Features](#features)
- [Screenshots](#screenshots)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Start OpenCode Server](#start-opencode-server)
  - [Network Setup](#network-setup)
  - [Build & Install](#build--install)
- [Usage](#usage)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [API Reference](#api-reference)
- [FAQ](#faq)
- [Security Notes](#security-notes)
- [Contributing](#contributing)
- [License](#license)

---

## Download & Install

### Download APK directly (no build needed)

Download the latest APK from the `release/` directory:

1. Go to [release/app-release.apk](release/app-release.apk)
2. Click the **Download** button to save the APK
3. Open the downloaded file on your phone and allow "Install from unknown sources"
4. Once installed, open OConnector and start using it

> Minimum requirement: Android 8.0 (API 26)

### Build from source

To build from source, see [Getting Started](#getting-started) below.

---

## Features

- Connect to OpenCode server via IP + port
- Browse projects and sessions
- Real-time AI chat with SSE streaming
- Tool call rendering (thinking bubbles, tool summaries)
- Todo panel overlay
- Agent picker (switch between AI agents)
- Session fork / delete
- EN/ZH bilingual toggle (in-app)
- Dark mode (follows system setting)
- Password encrypted via EncryptedSharedPreferences
- SSE auto-reconnection with exponential backoff

## Screenshots

<!-- Add screenshots here -->

---

## Getting Started

### Prerequisites

| Component | Requirement |
|-----------|-------------|
| **Android** | 8.0 (API 26) or higher |
| **PC OS** | Windows / macOS / Linux |
| **PC Software** | OpenCode (with `opencode` available globally) |
| **Dev Tools** (build only) | Android Studio Hedgehog (2023.1+) + JDK 17 |
| **Network** | Phone and PC on the same LAN, or connected via Tailscale / ZeroTier |

### Start OpenCode Server

**Using the startup script (recommended)**:

```batch
scripts\start-server.bat       # Windows
bash scripts/start-server.sh   # macOS / Linux
```

**Manual start**:

```bash
opencode serve --hostname=0.0.0.0 --port=4096
```

| Parameter | Description |
|-----------|-------------|
| `--hostname=0.0.0.0` | Listen on all network interfaces (allow external connections) |
| `--port=4096` | Server port (customizable, must match APP config) |

> Do not close the terminal after starting the server. You should see `Server listening on http://0.0.0.0:4096` when it starts successfully.

### Network Setup

**Option 1: Same WiFi (easiest)**

Connect both your phone and PC to the same router. Find your PC's LAN IP with `ipconfig` (Windows) or `ifconfig` (macOS/Linux).

**Option 2: USB Tethering**

Connect your phone to PC via USB and enable USB tethering. A new network adapter will appear on the PC, usually with an IP like `192.168.42.x`.

**Option 3: Remote connection (different networks)**

Use Tailscale or ZeroTier to create a virtual LAN. Enter the PC's Tailscale IP (`100.x.x.x`) in the app.

**Verify connectivity**: Open `http://<PC_IP>:4096` in your phone's browser. If you get a response, the connection works.

**Firewall**:

- **Windows**: Control Panel → Windows Defender Firewall → allow inbound port 4096
- **macOS**: System Settings → Network → Firewall → add exception
- **Linux**: `sudo ufw allow 4096`

### Build & Install

**Option 1: Android Studio (recommended)**

1. Open the project root directory in Android Studio
2. Wait for Gradle sync to finish
3. Connect your phone via USB, enable Developer Options → USB Debugging
4. Click the Run button

**Option 2: Command line**

```bash
./gradlew assembleDebug       # macOS / Linux
gradlew.bat assembleDebug     # Windows
```

The APK will be at `app/build/outputs/apk/debug/`. Transfer it to your phone and install.

---

## Usage

### Connect to Server

1. Open the app and go to the connection screen
2. Enter your PC's IP address (e.g. `192.168.1.100`)
3. Keep the default port `4096`
4. Tap **Connect**

On success, you'll be taken to the session list. On failure, an error message will appear.

### Session List

- Shows all OpenCode sessions on your PC
- Each card displays the title, status (RUNNING / COMPLETED / IDLE), agent, and message count
- Colored dots indicate status: running / completed / error / idle
- Tap a card to enter the chat, or tap the FAB to create a new session
- Use the overflow menu on each item to fork or delete

### Chat

- Type in the bottom input bar and tap send
- AI responses stream in token by token
- Blue bubbles = your messages, gray bubbles = AI replies, dark gray = tool calls
- Gray bubble + blinking cursor = currently generating
- Top toolbar: back, Todo panel, stop generation, refresh

### Todo Panel

Tap the Todo button in the toolbar to open the overlay panel. View task status (completed / in progress / pending).

---

## Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.0 | Language |
| Jetpack Compose | - | UI framework (Material3) |
| Ktor HTTP Client | 3.x | REST API communication |
| Ktor SSE Client | 3.x | SSE event streaming |
| Hilt | - | Dependency injection |
| kotlinx.serialization | - | JSON serialization |
| DataStore | - | Key-value storage |
| EncryptedSharedPreferences | - | Password encryption |
| MVVM | - | Architecture pattern |

---

## Project Structure

```
app/src/main/java/com/opencode/remote/
├── OConnectorApp.kt              # Application entry (@HiltAndroidApp)
├── di/
│   └── AppModule.kt              # Hilt DI module
├── data/
│   ├── api/
│   │   ├── OConnectorApiClient.kt    # REST API client (file: OpenCodeApiClient.kt)
│   │   ├── OConnectorSseClient.kt    # SSE event client (file: OpenCodeSseClient.kt)
│   │   └── dto/
│   │       ├── CommonDtos.kt     # File/Project/Agent DTOs
│   │       ├── SessionDtos.kt    # Session/Message/Todo DTOs
│   │       └── EventDtos.kt      # SSE event DTOs
│   ├── datastore/
│   │   └── ConnectionPreferences.kt  # Encrypted prefs + DataStore
│   └── repository/
│       └── OConnectorRepository.kt   # Interface + Impl (file: OpenCodeRepository.kt)
└── ui/
    ├── AppNavigation.kt          # Navigation routes
    ├── MainActivity.kt           # Main Activity
    ├── theme/
    │   ├── Color.kt              # Material3 colors
    │   ├── Theme.kt              # Theme config
    │   └── Type.kt               # Typography
    ├── connection/
    │   ├── ConnectionScreen.kt   # Connection UI
    │   └── ConnectionViewModel.kt
    ├── sessions/
    │   ├── SessionsScreen.kt     # Session list UI
    │   └── SessionsViewModel.kt
    ├── chat/
    │   ├── ChatScreen.kt         # Chat UI (entry point)
    │   ├── ChatComponents.kt     # Message/input composables
    │   ├── ChatViewModel.kt      # Chat logic + streaming
    │   ├── MarkdownRenderer.kt   # Markdown parsing + display
    │   ├── OverlayComponents.kt  # Todo/Agent picker panels
    │   └── ToolSummarizer.kt     # Tool call summaries
    ├── components/
    │   └── ErrorSnackbar.kt      # Shared error UI
    ├── help/
    │   └── HelpScreen.kt         # In-app help
    └── strings/
        └── Strings.kt            # EN/ZH i18n
```

---

## API Reference

The OpenCode server exposes REST API endpoints and an SSE event stream.

### Session Operations

| Endpoint | Method | Params | Returns | Usage |
|----------|--------|--------|---------|-------|
| `/session/list` | GET | — | `SessionListResponse` | Load session list |
| `/session/create` | POST | — | `CreateSessionResponse` | Create new session |
| `/session/get` | GET | `?id=` | `SessionInfo` | Load session details |
| `/session/status` | GET | `?id=` | `SessionStatusResponse` | Get running status |
| `/session/fork` | POST | `?id=` | `CreateSessionResponse` | Fork a session |
| `/session/delete` | DELETE | `?id=` | — | Delete a session |
| `/session/abort` | POST | `?id=` | — | Abort generation |

### Message Operations

| Endpoint | Method | Params | Returns | Usage |
|----------|--------|--------|---------|-------|
| `/session/messages` | GET | `?id=` | `MessageListResponse` | Load message history |
| `/session/prompt` | POST | `?id=` + body | `SendPromptResponse` | Sync send |
| `/session/prompt_async` | POST | `?id=` + body | `SendPromptAsyncResponse` | Async send (primary) |
| `/session/revert` | POST | `?id=&message_id=` | — | Revert message |

### Tasks & Context

| Endpoint | Method | Params | Returns | Usage |
|----------|--------|--------|---------|-------|
| `/session/todo` | GET | `?id=` | `TodoListResponse` | Load Todo list |
| `/session/children` | GET | `?id=` | `SessionListResponse` | Get child sessions |
| `/session/diff` | GET | `?id=` | `DiffResponse` | Get code diff |
| `/session/summarize` | POST | `?id=` | `SummarizeResponse` | AI summarize session |

### Files & Project

| Endpoint | Method | Params | Returns | Usage |
|----------|--------|--------|---------|-------|
| `/file/list` | GET | `?path=` | `FileListResponse` | Browse project files |
| `/file/read` | GET | `?path=` | `FileReadResponse` | Read file contents |
| `/project/current` | GET | — | `ProjectCurrentResponse` | Get project info |
| `/app/agents` | GET | — | `AgentListResponse` | List available agents |

### SSE Event Stream

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/global/event` | GET | Real-time event stream (Content-Type: text/event-stream) |

| Event Type | Trigger | Handling |
|-----------|---------|----------|
| `message_delta` | AI outputs tokens | Accumulate into streaming text bubble |
| `message_complete` | Message generation done | Reset streaming state, refresh list |
| `session_status` | Session status changed | Update status bar text |
| `error` | Error occurred | Show error message |
| `tool_execution` | Tool call | Render tool call summary |

---

## FAQ

### Connection fails with "Unable to connect"

1. Is `opencode serve --hostname=0.0.0.0 --port=4096` running on the PC?
2. Is the IP address correct? (Check with `ipconfig` on the PC)
3. Is the firewall blocking port 4096?
4. Are the phone and PC on the same network?

### Session list is empty

This is normal. Tap the FAB to create a new session.

### No response after sending a message

1. Check if the PC terminal shows log output
2. Try stopping and resending
3. Verify the OpenCode API key is configured on the PC

### App crashes

1. Confirm Android version is 8.0 or higher
2. Go to system Settings → Apps → OConnector → Clear data, then retry

### Can multiple phones connect at the same time?

Yes. The OpenCode server supports multiple clients. However, operating the same session from multiple devices may cause conflicts.

---

## Security Notes

- Only use on a trusted local network
- Use Tailscale / ZeroTier for encrypted remote access
- Do not expose the port to the public internet
- Do not use on public WiFi
- `usesCleartextTraffic=true` allows HTTP (no HTTPS needed on LAN), Tailscale provides its own encryption
- Passwords are encrypted locally via EncryptedSharedPreferences

---

## Contributing

Contributions are welcome.

1. Fork this repository
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Commit your changes: `git commit -m "Add your feature"`
4. Push to the branch: `git push origin feature/your-feature`
5. Open a Pull Request

Please make sure the code compiles and describe your changes and motivation in the PR.

---

## License

This project is licensed under the [MIT License](https://opensource.org/licenses/MIT).

```
MIT License

Copyright (c) 2026 OConnector Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
