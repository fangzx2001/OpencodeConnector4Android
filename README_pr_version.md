English | [中文](#中文说明)

# OConnector — PR Version Branch

> This branch (`pr_version`) is a dedicated integration branch for community pull requests.
> It is **not** the main development line. All PR features land here first.

## What is this branch?

`pr_version` is maintained separately from `main`. It starts from the same `main` baseline and accumulates merged community contributions. Each PR integration is documented below.

This allows the project maintainer to keep their own development pace on `main` while still incorporating community contributions in a clean, trackable way.

## Rule for this branch

**Every PR merged into this branch MUST update this README** with a new section under "Included PRs" documenting what was added.

---

## Included PRs

### PR #9 — `feat: improve Android chat controls and session management`

| Item | Detail |
|------|--------|
| **Author** | [BAYUNZIYUE](https://github.com/BAYUNZIYUE) |
| **Source** | [`BAYUNZIYUE/OConnector-X`](https://github.com/BAYUNZIYUE/OConnector-X) → `feat/oconnector-x-chat-updates` |
| **Merged** | 2026-05-11 |
| **Commits** | 7 (all authored by BAYUNZIYUE, preserved in git history) |
| **Changes** | 18 files, +2859 / -165 lines |

#### Features added:

- **Session management actions** — rename sessions (`PATCH /session/{id}`), share/unshare sessions (`POST /session/{id}/share`), copy share link, copy session ID
- **Chat configuration overrides** — per-session agent selection, model selection (`provider/model`), and variant selection, all persisted to DataStore
- **Draft persistence** — unsent message text is saved per session and restored when returning to a chat
- **Improved message sending** — tries `POST /session/{id}/message` first (newer servers), falls back to `POST /session/{id}/prompt_async` (legacy)
- **Provider/model info with limits** — `GET /provider` returns model context/output limits; context usage displayed as percentage in chat title bar
- **Todo SSE sync** — `todos` field in SSE events for inline task updates
- **Bottom-follow controls** — `BottomFollowCoordinator` with Force/Auto scroll lock modes
- **Auto-login** — `auto_login_enabled` preference for automatic reconnection on app start
- **Summarize session** — API endpoint for session summarization (backend ready)
- **Input field clear button** — trailing icon to clear text in ChatInputBar
- **AGENTS.md** — project guidance document for AI-assisted development
- **Robust JSON parsing** — manual fallback parsing for agent and provider endpoints that may return varying JSON shapes
- **Message model/provider tracking** — `MessageInfoData` now includes `providerID`, `modelID`, `variant` fields with resolution helpers

#### Files changed:

| File | Change |
|------|--------|
| `AGENTS.md` | New — project guidance |
| `OpenCodeApiClient.kt` | +210 lines — new endpoints, send fallback, manual JSON parsing |
| `CommonDtos.kt` | +33 lines — `ProvidersResponseDto`, `ProviderModelInfo`, `ModelLimitInfo` |
| `EventDtos.kt` | +1 line — `todos` field |
| `SessionDtos.kt` | +67 lines — share, rename, summarize DTOs, message model fields |
| `ConnectionPreferences.kt` | +106 lines — per-session agent/model/variant/draft persistence |
| `OpenCodeRepository.kt` | +49 lines — new session ops, provider caching |
| `BottomFollowCoordinator.kt` | New — scroll behavior coordinator |
| `ChatComponents.kt` | +29 lines — input clear button |
| `ChatScreen.kt` | +546 lines — major UI changes |
| `ChatSelectionState.kt` | New — message selection state |
| `ChatViewModel.kt` | +754 lines — config persistence, model sending, todo sync |
| `OverlayComponents.kt` | +391 lines — overlay UI |
| `ConnectionViewModel.kt` | +45 lines — auto-login |
| `SessionsScreen.kt` | +130 lines — session management UI |
| `SessionsViewModel.kt` | +157 lines — session rename/share/copy |
| `Strings.kt` | +194 lines — i18n for all new features |
| `MessageParsingTest.kt` | New — unit tests for DTO parsing |

---

## Relationship to other branches

| Branch | Purpose |
|--------|---------|
| `main` | Maintainer's primary development branch |
| `pr_test` | PR review/testing target |
| `pr_version` | **This branch** — accumulated community PR integrations |

---

## Build

Same as main branch:

```bash
gradlew.bat assembleDebug     # Windows
./gradlew assembleDebug       # macOS / Linux
```

---

<a id="中文说明"></a>

## 中文说明

# OConnector — PR 版本分支

> 此分支（`pr_version`）是社区 Pull Request 的专用集成分支。
> 它**不是**主开发线。所有 PR 功能先合并到这里。

## 分支规则

**每个合并到此分支的 PR 必须更新此 README**，在「已合并的 PR」下新增一个章节记录所添加的内容。

---

## 已合并的 PR

### PR #9 — `feat: 改进 Android 聊天控制和会话管理`

| 项目 | 详情 |
|------|------|
| **作者** | [BAYUNZIYUE](https://github.com/BAYUNZIYUE) |
| **来源** | [`BAYUNZIYUE/OConnector-X`](https://github.com/BAYUNZIYUE/OConnector-X) → `feat/oconnector-x-chat-updates` |
| **合并日期** | 2026-05-11 |
| **提交数** | 7（全部由 BAYUNZIYUE 原始创作，git 历史中完整保留） |
| **变更** | 18 个文件，+2859 / -165 行 |

#### 新增功能：

- **会话管理操作** — 重命名会话（`PATCH /session/{id}`）、分享/取消分享会话、复制分享链接、复制会话 ID
- **聊天配置覆盖** — 按会话持久化 agent 选择、模型选择和变体选择
- **草稿持久化** — 未发送消息文本按会话保存，返回聊天时自动恢复
- **改进消息发送** — 优先尝试新路由，失败后回退到旧版端点
- **Provider/Model 信息及限制** — 模型上下文/输出限制；上下文占用以百分比显示在标题栏
- **Todo SSE 同步** — SSE 事件内联任务更新
- **底部跟随控制** — Force/Auto 滚动锁定模式
- **自动登录** — 启动时自动重连
- **会话摘要** — 会话摘要 API（后端就绪）
- **输入框清除按钮** — ChatInputBar 清除文本
- **AGENTS.md** — AI 辅助开发项目指引
- **健壮 JSON 解析** — agent/provider 端点手动回退解析
- **消息模型/Provider 追踪** — `MessageInfoData` 新增字段

---

## 与其他分支的关系

| 分支 | 用途 |
|------|------|
| `main` | 项目负责人的主要开发分支 |
| `pr_test` | PR 审阅/测试目标 |
| `pr_version` | **本分支** — 累积的社区 PR 集成 |
