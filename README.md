English | [中文](README_zh.md)

# OConnector

An Android client for the [OpenCode](https://opencode.ai) AI coding assistant. Connect to your PC, chat with AI, manage sessions across all projects, and track task progress — all from your phone.

![Android](https://img.shields.io/badge/Android-8.0%2B-34A853?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)
![License](https://img.shields.io/badge/License-MIT-blue)

## Features

- **Multi-project discovery** — automatically finds and lists sessions from all projects, regardless of which directory the server was started from
- Real-time AI chat with SSE streaming
- Tool call rendering (thinking bubbles, tool summaries)
- Todo task panel overlay
- Agent picker (switch between AI agents)
- Session fork / delete
- Manual dark mode toggle (🌙/☀️ button)
- EN/ZH bilingual toggle (in-app)
- Password encrypted storage (EncryptedSharedPreferences)
- SSE auto-reconnection with exponential backoff

## Download & Install

### Download APK

1. Go to [release/app-release.apk](release/app-release.apk)
2. Click **Download**
3. Open the file on your phone, allow "Install from unknown sources"
4. Open OConnector and connect to your PC

> Requires Android 8.0+ (API 26)

### Build from Source

```bash
gradlew.bat assembleDebug     # Windows
./gradlew assembleDebug       # macOS / Linux
```

The APK will be at `app/build/outputs/apk/debug/`.

## Getting Started

### Prerequisites

| Component | Requirement |
|-----------|-------------|
| **Android** | 8.0 (API 26) or higher |
| **PC** | Windows / macOS / Linux with [OpenCode](https://opencode.ai) installed |
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

You can start the server from any directory — OConnector will discover all projects automatically.

### Network Setup

| Scenario | How |
|----------|-----|
| **Same WiFi** | Phone and PC on the same router. Get PC IP with `ipconfig` (Windows) or `ifconfig` (macOS/Linux) |
| **USB Tethering** | Connect phone via USB, enable USB tethering. PC gets a new adapter at `192.168.42.x` |
| **Remote** | Use [Tailscale](https://tailscale.com) or ZeroTier. Enter PC's Tailscale IP (`100.x.x.x`) in the app |

**Verify**: Open `http://<PC_IP>:4096` in your phone's browser.

**Firewall**: Allow inbound port 4096.

### Usage

1. Open OConnector → enter PC IP and port → tap **Connect**
2. The session list shows all projects and sessions across your PC
3. Tap a project to see its sessions, tap a session to enter chat
4. Send messages, switch agents, track todos

## Tech Stack

| Technology | Purpose |
|------------|---------|
| Kotlin 2.0 + Jetpack Compose (Material3) | UI |
| Ktor HTTP/SSE Client 3.x | API + streaming |
| Hilt | Dependency injection |
| kotlinx.serialization | JSON |
| DataStore + EncryptedSharedPreferences | Storage |

## Project Structure

```
app/src/main/java/com/opencode/remote/
├── data/
│   ├── api/          # REST + SSE clients
│   ├── dto/          # Data models
│   ├── datastore/    # Preferences
│   └── repository/   # Repository pattern
├── ui/
│   ├── connection/   # Connect screen
│   ├── sessions/     # Project + session list
│   ├── chat/         # Chat + streaming + tools
│   ├── theme/        # Material3 theme
│   └── strings/      # i18n
└── AppNavigation.kt  # Nav graph
```

## FAQ

### Can't see all projects?

OConnector auto-discovers all projects. Make sure the server is running and the phone can reach it. Check the server console for errors.

### Session list is empty for a project?

The project may not have any sessions yet. Tap the FAB to create one.

### Can multiple phones connect?

Yes. The OpenCode server supports multiple clients.

## Security

- Only use on trusted local networks
- Use Tailscale / ZeroTier for remote access (encrypted)
- Do not expose port to the public internet
- Passwords encrypted locally via EncryptedSharedPreferences

## Contributing

1. Fork → branch → commit → push → PR
2. Ensure code compiles
3. Describe changes in the PR

## License

[MIT License](LICENSE)
