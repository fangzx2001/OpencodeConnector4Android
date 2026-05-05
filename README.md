[English](README_EN.md) | 中文

# OConnector

Android 远程控制端，连接 PC 上运行的 OpenCode AI 编程助手。在手机上聊天、管理会话、追踪任务进度。

![Android](https://img.shields.io/badge/Android-8.0%2B-34A853?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)
![License](https://img.shields.io/badge/License-MIT-blue)

---

## 目录

- [下载安装](#下载安装)
- [功能特性](#功能特性)
- [截图](#截图)
- [快速开始](#快速开始)
  - [环境要求](#环境要求)
  - [启动 OpenCode 服务器](#启动-opencode-服务器)
  - [网络配置](#网络配置)
  - [构建并安装 APP](#构建并安装-app)
- [使用方法](#使用方法)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [API 参考](#api-参考)
- [常见问题](#常见问题)
- [安全提示](#安全提示)
- [贡献](#贡献)
- [许可证](#许可证)

---

## 下载安装

### 直接下载 APK（免构建）

从仓库的 `release/` 目录下载最新的 APK 文件：

1. 前往 [release/app-release.apk](release/app-release.apk)
2. 点击 **Download** 按钮下载 APK
3. 在手机上打开下载的文件，允许「安装未知来源应用」
4. 安装完成后打开 OConnector 即可使用

> 最低系统要求：Android 8.0 (API 26)

### 从源码构建

如需自行构建，请参考下方 [快速开始](#快速开始) 章节。

---

## 功能特性

- 连接 OpenCode 服务器（IP + 端口）
- 浏览项目与会话列表
- 实时 AI 聊天，SSE 流式输出
- 工具调用渲染（思考气泡、工具摘要）
- Todo 任务面板浮层
- Agent 选择器（切换不同 AI Agent）
- 会话 Fork / 删除
- 中英双语切换（APP 内切换）
- 暗色模式（跟随系统）
- 密码加密存储（EncryptedSharedPreferences）
- SSE 自动重连（指数退避）

## 截图

<!-- Add screenshots here -->

---

## 快速开始

### 环境要求

| 组件 | 要求 |
|------|------|
| **手机系统** | Android 8.0 (API 26) 及以上 |
| **PC 系统** | Windows / macOS / Linux |
| **PC 需安装** | OpenCode（全局可用 `opencode` 命令） |
| **开发工具**（仅构建时需要）| Android Studio Hedgehog (2023.1+) + JDK 17 |
| **网络** | 手机和 PC 在同一局域网，或通过 Tailscale / ZeroTier 互联 |

### 启动 OpenCode 服务器

**使用启动脚本（推荐）**：

```batch
scripts\start-server.bat       # Windows
bash scripts/start-server.sh   # macOS / Linux
```

**手动启动**：

```bash
opencode serve --hostname=0.0.0.0 --port=4096
```

| 参数 | 说明 |
|------|------|
| `--hostname=0.0.0.0` | 监听所有网络接口（允许外部连接） |
| `--port=4096` | 服务端口（可自定义，需与 APP 配置一致） |

> 启动后不要关闭终端窗口。看到 `Server listening on http://0.0.0.0:4096` 表示成功。

### 网络配置

**场景一：同一 WiFi（最简单）**

手机和 PC 连接同一个路由器。PC 上运行 `ipconfig`（Windows）或 `ifconfig`（macOS/Linux）获取局域网 IP。

**场景二：USB 网络共享**

手机通过 USB 连接 PC，开启"USB 网络共享"。PC 上会多出一个网络适配器，IP 通常为 `192.168.42.x`。

**场景三：远程连接（不同网络）**

使用 Tailscale / ZeroTier 组建虚拟局域网，APP 中输入 PC 的 Tailscale IP（`100.x.x.x`）。

**验证连通性**：手机浏览器访问 `http://<PC_IP>:4096`，有响应说明网络已通。

**防火墙**：

- **Windows**：控制面板 → Windows Defender 防火墙 → 允许端口 4096 入站
- **macOS**：系统设置 → 网络 → 防火墙 → 添加例外
- **Linux**：`sudo ufw allow 4096`

### 构建并安装 APP

**方法一：Android Studio（推荐）**

1. 用 Android Studio 打开项目根目录
2. 等待 Gradle 同步完成
3. USB 连接手机，开启「开发者选项 → USB 调试」
4. 点击运行按钮

**方法二：命令行构建**

```bash
./gradlew assembleDebug       # macOS / Linux
gradlew.bat assembleDebug     # Windows
```

构建完成后，APK 位于 `app/build/outputs/apk/debug/`。

---

## 使用方法

### 连接服务器

1. 打开 APP，进入连接页
2. 输入 PC 的 IP 地址（如 `192.168.1.100`）
3. 端口保持默认 `4096`
4. 点击 **连接服务器**

连接成功自动跳转到会话列表，失败会显示错误提示。

### 会话列表

- 显示 PC 上所有 OpenCode 会话
- 会话卡片显示标题、状态（RUNNING / COMPLETED / IDLE）、Agent、消息数量
- 彩色圆点标识状态：运行中 / 已完成 / 错误 / 空闲
- 点击卡片进入聊天，右下角新建会话
- 每条右侧菜单可 Fork 或删除

### 聊天

- 底部输入框发送消息，AI 回复逐字流式显示
- 蓝色气泡 = 你的消息，灰色气泡 = AI 回复，深灰气泡 = 工具调用
- 灰色气泡 + 闪烁光标 = 正在生成
- 顶部工具栏：返回、Todo 面板、停止生成、刷新

### Todo 面板

点击顶部 Todo 按钮展开浮层面板，查看任务状态（已完成 / 进行中 / 待处理）。

---

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Kotlin | 2.0 | 开发语言 |
| Jetpack Compose | - | UI 框架 (Material3) |
| Ktor HTTP Client | 3.x | REST API 通信 |
| Ktor SSE Client | 3.x | SSE 事件流 |
| Hilt | - | 依赖注入 |
| kotlinx.serialization | - | JSON 序列化 |
| DataStore | - | 键值存储 |
| EncryptedSharedPreferences | - | 密码加密 |
| MVVM | - | 架构模式 |

---

## 项目结构

```
app/src/main/java/com/opencode/remote/
├── OConnectorApp.kt              # Application 入口 (@HiltAndroidApp)
├── di/
│   └── AppModule.kt              # Hilt DI 模块
├── data/
│   ├── api/
│   │   ├── OConnectorApiClient.kt    # REST API 客户端 (文件: OpenCodeApiClient.kt)
│   │   ├── OConnectorSseClient.kt    # SSE 事件流客户端 (文件: OpenCodeSseClient.kt)
│   │   └── dto/
│   │       ├── CommonDtos.kt     # 文件/项目/Agent DTO
│   │       ├── SessionDtos.kt    # 会话/消息/Todo DTO
│   │       └── EventDtos.kt      # SSE 事件 DTO
│   ├── datastore/
│   │   └── ConnectionPreferences.kt  # 加密存储 + DataStore
│   └── repository/
│       └── OConnectorRepository.kt   # 接口 + 实现 (文件: OpenCodeRepository.kt)
└── ui/
    ├── AppNavigation.kt          # 导航路由
    ├── MainActivity.kt           # 主 Activity
    ├── theme/
    │   ├── Color.kt              # Material3 颜色
    │   ├── Theme.kt              # 主题配置
    │   └── Type.kt               # 排版系统
    ├── connection/
    │   ├── ConnectionScreen.kt   # 连接页 UI
    │   └── ConnectionViewModel.kt
    ├── sessions/
    │   ├── SessionsScreen.kt     # 会话列表 UI
    │   └── SessionsViewModel.kt
    ├── chat/
    │   ├── ChatScreen.kt         # 聊天页 UI（入口）
    │   ├── ChatComponents.kt     # 消息/输入组合件
    │   ├── ChatViewModel.kt      # 聊天逻辑 + 流式处理
    │   ├── MarkdownRenderer.kt   # Markdown 解析与渲染
    │   ├── OverlayComponents.kt  # Todo/Agent 选择面板
    │   └── ToolSummarizer.kt     # 工具调用摘要
    ├── components/
    │   └── ErrorSnackbar.kt      # 共享错误 UI
    ├── help/
    │   └── HelpScreen.kt         # 应用内帮助
    └── strings/
        └── Strings.kt            # 中英国际化
```

---

## API 参考

OpenCode 服务器提供 REST API 和 SSE 事件流。

### 会话操作

| 端点 | 方法 | 参数 | 返回 | 用途 |
|------|------|------|------|------|
| `/session/list` | GET | — | `SessionListResponse` | 加载会话列表 |
| `/session/create` | POST | — | `CreateSessionResponse` | 创建新会话 |
| `/session/get` | GET | `?id=` | `SessionInfo` | 加载会话详情 |
| `/session/status` | GET | `?id=` | `SessionStatusResponse` | 获取运行状态 |
| `/session/fork` | POST | `?id=` | `CreateSessionResponse` | Fork 会话 |
| `/session/delete` | DELETE | `?id=` | — | 删除会话 |
| `/session/abort` | POST | `?id=` | — | 中止生成 |

### 消息操作

| 端点 | 方法 | 参数 | 返回 | 用途 |
|------|------|------|------|------|
| `/session/messages` | GET | `?id=` | `MessageListResponse` | 加载历史消息 |
| `/session/prompt` | POST | `?id=` + body | `SendPromptResponse` | 同步发送 |
| `/session/prompt_async` | POST | `?id=` + body | `SendPromptAsyncResponse` | 异步发送（主用） |
| `/session/revert` | POST | `?id=&message_id=` | — | 回退消息 |

### 任务与上下文

| 端点 | 方法 | 参数 | 返回 | 用途 |
|------|------|------|------|------|
| `/session/todo` | GET | `?id=` | `TodoListResponse` | 加载 Todo 列表 |
| `/session/children` | GET | `?id=` | `SessionListResponse` | 获取子会话 |
| `/session/diff` | GET | `?id=` | `DiffResponse` | 获取代码 diff |
| `/session/summarize` | POST | `?id=` | `SummarizeResponse` | AI 总结会话 |

### 文件与项目

| 端点 | 方法 | 参数 | 返回 | 用途 |
|------|------|------|------|------|
| `/file/list` | GET | `?path=` | `FileListResponse` | 浏览项目文件 |
| `/file/read` | GET | `?path=` | `FileReadResponse` | 读取文件内容 |
| `/project/current` | GET | — | `ProjectCurrentResponse` | 获取项目信息 |
| `/app/agents` | GET | — | `AgentListResponse` | 列出可用 Agent |

### SSE 事件流

| 端点 | 方法 | 说明 |
|------|------|------|
| `/global/event` | GET | 实时事件流（Content-Type: text/event-stream） |

| 事件类型 | 触发时机 | 处理方式 |
|---------|---------|---------|
| `message_delta` | AI 逐 token 输出 | 累加到流式文本气泡 |
| `message_complete` | 消息生成完毕 | 重置流式状态，刷新列表 |
| `session_status` | 会话状态变更 | 更新状态栏文本 |
| `error` | 发生错误 | 显示错误提示 |
| `tool_execution` | 工具调用 | 渲染工具调用摘要 |

---

## 常见问题

### 连接失败，提示"无法连接"

1. PC 上 `opencode serve --hostname=0.0.0.0 --port=4096` 是否已运行？
2. IP 地址是否正确（PC 上 `ipconfig` 查看）？
3. 防火墙是否拦截了端口 4096？
4. 手机和 PC 是否在同一网络？

### 会话列表为空

正常现象。点击右下角新建会话创建即可。

### 发送消息后无回复

1. 确认 PC 终端有日志输出
2. 尝试停止后重发
3. 检查 PC 上 OpenCode 的 API Key 是否已配置

### APP 闪退

1. 确认 Android >= 8.0
2. 系统设置 → 应用 → OConnector → 清除数据后重试

### 可以多台手机同时连接吗？

可以。OpenCode 服务器支持多客户端，但多台设备操作同一会话可能冲突。

---

## 安全提示

- 仅在受信任的局域网内使用
- 远程访问使用 Tailscale / ZeroTier 加密通道
- 不要将端口暴露在公网上
- 不要在公共 WiFi 下使用
- `usesCleartextTraffic=true` 允许 HTTP（局域网内无需 HTTPS），Tailscale 自带加密
- 密码通过 EncryptedSharedPreferences 加密存储在本地

---

## 贡献

欢迎参与 OConnector 的开发。

1. Fork 本仓库
2. 创建功能分支：`git checkout -b feature/your-feature`
3. 提交改动：`git commit -m "Add your feature"`
4. 推送到分支：`git push origin feature/your-feature`
5. 提交 Pull Request

请确保代码能通过编译，并在 PR 中描述改动内容和动机。

---

## 许可证

本项目基于 [MIT License](https://opensource.org/licenses/MIT) 开源。

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
