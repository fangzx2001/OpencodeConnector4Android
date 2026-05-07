[English](README.md) | 中文

# OConnector

连接 [OpenCode](https://opencode.ai) AI 编程助手的 Android 客户端。在手机上与 AI 对话、管理所有项目的会话、追踪任务进度。

![Android](https://img.shields.io/badge/Android-8.0%2B-34A853?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)
![License](https://img.shields.io/badge/License-MIT-blue)

## 功能特性

- **多项目自动发现** — 自动发现并展示所有项目的会话，无需关心服务器从哪个目录启动
- **HTTPS (TLS) 支持** — 通过 HTTPS 反向代理（Lucky、Nginx、Caddy 等）连接，支持自签名证书
- **应用内更新检测** — 启动时自动检查 GitHub 是否有新版本，一键下载安装 APK
- **后台 SSE 保活** — 前台 Service 在 App 后台时保持连接不断开
- 实时 AI 聊天，SSE 流式输出
- 工具调用渲染（思考气泡、工具摘要）
- Todo 任务面板浮层
- Agent 选择器（切换不同 AI Agent）
- 会话 Fork / 删除
- 手动暗色模式切换（🌙/☀️ 按钮）
- 中英双语切换（APP 内切换）
- 密码加密存储（EncryptedSharedPreferences）
- SSE 自动重连（指数退避）
- **中文/非 ASCII 路径支持** — 支持包含中文、日文、韩文等 Unicode 字符的项目目录

## 下载安装

### 下载 APK

1. 前往 [GitHub Releases](https://github.com/fangzx2001/OpencodeConnector4Android/releases/latest)
2. 下载最新版本中的 `app-release.apk`
3. 在手机上打开下载的文件，允许「安装未知来源应用」
4. 安装完成后打开 OConnector，连接你的 PC

> 最低系统要求：Android 8.0+ (API 26)

### 从源码构建

```bash
gradlew.bat assembleDebug     # Windows
./gradlew assembleDebug       # macOS / Linux
```

构建完成后 APK 位于 `app/build/outputs/apk/debug/`。

## 快速开始

### 环境要求

| 组件 | 要求 |
|------|------|
| **手机** | Android 8.0 (API 26) 及以上 |
| **PC** | Windows / macOS / Linux，已安装 [OpenCode](https://opencode.ai) |
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

从任意目录启动即可，OConnector 会自动发现所有项目。

### 网络配置

| 场景 | 方法 |
|------|------|
| **同一 WiFi** | 手机和 PC 连接同一个路由器。PC 上 `ipconfig`（Windows）或 `ifconfig`（macOS/Linux）获取 IP |
| **USB 网络共享** | 手机通过 USB 连接 PC，开启 USB 网络共享。PC 新增适配器 IP 通常为 `192.168.42.x` |
| **远程连接** | 使用 [Tailscale](https://tailscale.com) 或 ZeroTier 组建虚拟局域网，输入 PC 的 Tailscale IP |

**验证**：手机浏览器访问 `http://<PC_IP>:4096`，有响应即连通。

**防火墙**：允许端口 4096 入站。

### 使用方法

1. 打开 OConnector → 输入 PC 的 IP 和端口 → 点击 **连接**
2. 会话列表自动展示 PC 上所有项目的会话
3. 点击项目查看其会话，点击会话进入聊天
4. 发送消息、切换 Agent、查看 Todo

### 通过 HTTPS 反向代理连接

如果你使用反向代理（Lucky、Nginx、Caddy 等）以 HTTPS 方式暴露 OpenCode：

1. 输入代理域名（如 `opencode.example.com`）和端口
2. 开启 **「使用 HTTPS (TLS)」** 开关
3. 如果代理使用自签名证书，同时开启 **「允许不受信任的证书」**
4. 点击 **连接**

> **提示**：没有域名的内网环境，也可以使用 Tailscale HTTPS 或配置本地 DNS。

## 技术栈

| 技术 | 用途 |
|------|------|
| Kotlin 2.0 + Jetpack Compose (Material3) | UI 框架 |
| Ktor HTTP/SSE Client 3.x | API 通信 + 流式推送 |
| Hilt | 依赖注入 |
| kotlinx.serialization | JSON 序列化 |
| DataStore + EncryptedSharedPreferences | 存储 |

## 项目结构

```
app/src/main/java/com/opencode/remote/
├── data/
│   ├── api/          # REST + SSE 客户端
│   ├── dto/          # 数据模型
│   ├── datastore/    # 偏好存储
│   ├── download/     # DownloadManager + FileProvider 安装
│   ├── github/       # GitHub Releases API + 版本比较
│   ├── repository/   # 仓库模式
│   └── sse/          # SSE 事件总线
├── service/          # 前台 Service
├── ui/
│   ├── connection/   # 连接页
│   ├── sessions/     # 项目 + 会话列表
│   ├── chat/         # 聊天 + 流式 + 工具调用
│   ├── help/         # 帮助页面
│   ├── theme/        # Material3 主题
│   └── strings/      # 国际化
└── AppNavigation.kt  # 导航图
```

## 常见问题

### 看不到所有项目？

OConnector 会自动发现所有项目。请确保服务器正在运行且手机可以访问。检查服务器控制台是否有错误。

### 某个项目下没有会话？

该项目可能还没有创建过会话，点击右下角按钮新建即可。

### 可以多台手机同时连接吗？

可以。OpenCode 服务器支持多客户端。

## 安全提示

- 仅在受信任的局域网内使用
- 远程访问使用 Tailscale / ZeroTier 加密通道
- 不要将端口暴露在公网上
- 密码通过 EncryptedSharedPreferences 加密存储在本地

## 贡献

1. Fork → 创建分支 → 提交 → 推送 → 发起 PR
2. 确保代码能通过编译
3. 在 PR 中描述改动内容

## 许可证

[MIT License](LICENSE)
