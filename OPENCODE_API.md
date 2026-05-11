# OpenCode Server API Reference

> Version: v1.14.x (verified from OConnector Android client)
> Base URL: `http://<host>:<port>` or `https://<host>:<port>` (with reverse proxy)

---

## Table of Contents

1. [Server Setup](#1-server-setup)
2. [Authentication](#2-authentication)
3. [Common Headers & Parameters](#3-common-headers--parameters)
4. [REST API Endpoints](#4-rest-api-endpoints)
   - [Session Management](#41-session-management)
   - [Message Operations](#42-message-operations)
   - [Todo](#43-todo)
   - [Project](#44-project)
   - [Agent](#45-agent)
   - [File System](#46-file-system)
   - [Provider / Model](#47-provider--model)
   - [Permission / Question](#48-permission--question)
5. [SSE Event Stream](#5-sse-event-stream)
6. [Data Models (DTOs)](#6-data-models-dtos)
7. [Token Usage Reporting](#7-token-usage-reporting)

---

## 1. Server Setup

```bash
opencode serve --hostname=0.0.0.0 --port=4096
```

| Option | Description |
|--------|-------------|
| `--hostname` | Bind address. `0.0.0.0` = all interfaces |
| `--port` | Port number. Default: `4096` |

### Environment Variables

| Variable | Description |
|----------|-------------|
| `OPENCODE_SERVER_PASSWORD` | Enable auth (password required) |
| `OPENCODE_SERVER_USERNAME` | Auth username. Default: `opencode` |

---

## 2. Authentication

When `OPENCODE_SERVER_PASSWORD` is set, all requests require **HTTP Basic Auth**.

```
Authorization: Basic <base64("username:password")>
```

- Default username: `opencode` (overridable via `OPENCODE_SERVER_USERNAME`)
- If no password is set on the server, auth header is not required

---

## 3. Common Headers & Parameters

### Directory Scoping

OpenCode scopes sessions by **project directory**. Many endpoints accept:

| Transport | Key | Example |
|-----------|-----|---------|
| Query parameter | `?directory=<path>` | `?directory=D%3A%5CProjects%5Cmyapp` |
| HTTP header | `x-opencode-directory: <URL-encoded path>` | `x-opencode-directory: D%3A%5CProjects%5Cmyapp` |

**When both are provided**, the server uses the query parameter. The header is a fallback for servers that don't parse query parameters on certain routes.

**Special case — global project**: When `worktree="/"` or `worktree=null`, use `?scope=project` to skip directory matching and return all sessions for that project ID.

### Content Type

All REST requests use `Content-Type: application/json`. Responses are JSON.

---

## 4. REST API Endpoints

### 4.1 Session Management

#### `GET /session?list` — List Sessions

Returns sessions for a specific project directory.

```
GET /session?list&directory=<path>&scope=<scope>
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `list` | string | yes | Empty value. Presence triggers list mode |
| `directory` | string | no | Project directory to filter by |
| `scope` | string | no | `"project"` = skip directory matching |

**Response**: `List<SessionInfo>` (JSON array)

```json
[
  {
    "id": "ses_abc123",
    "slug": "my-session",
    "projectID": "proj_xyz",
    "directory": "D:\\Projects\\myapp",
    "path": "/some/path",
    "title": "Fix login bug",
    "version": "1",
    "summary": { "additions": 45, "deletions": 12, "files": 3 },
    "permission": [
      { "permission": "edit", "action": "allow", "pattern": "*.ts" }
    ],
    "parentID": null,
    "time": {
      "created": 1715123456,
      "updated": 1715124567,
      "initialized": 1715123460,
      "completed": null
    },
    "revert": null
  }
]
```

#### `GET /project` — List All Projects

Returns all known projects (each project = one directory where `opencode serve` was started or has sessions).

```
GET /project
```

**Response**: `List<ProjectInfo>`

```json
[
  { "id": "global", "worktree": "/", "time": { "created": 1715123456 }, "sandboxes": [] },
  { "id": "proj_abc", "worktree": "D:\\Projects\\myapp", "time": null, "sandboxes": [] }
]
```

> **Tip**: Use this to discover all projects, then call `GET /session?list&directory=<worktree>` for each.

#### `POST /session` — Create Session

```
POST /session?directory=<path>
Body: {}
```

| Parameter | Location | Description |
|-----------|----------|-------------|
| `directory` | query + header | Project directory |

**Response**: `CreateSessionResponse`

```json
{
  "id": "ses_new123",
  "slug": "new-session",
  "title": null,
  "projectID": "proj_abc",
  "directory": "D:\\Projects\\myapp",
  "path": "/some/path",
  "version": "1",
  "time": { "created": 1715123456 }
}
```

#### `GET /session/{id}` — Get Session

```
GET /session/{id}?directory=<path>
```

**Response**: `SessionInfo` (same structure as list item)

#### `DELETE /session/{id}` — Delete Session

```
DELETE /session/{id}?directory=<path>
```

**Response**: `200 OK` (no body)

#### `POST /session/{id}/fork` — Fork Session

Creates a new session that continues from the current session's state.

```
POST /session/{id}/fork?directory=<path>
Body: {}
```

**Response**: `CreateSessionResponse`

#### `POST /session/{id}/abort` — Abort Current Generation

Stops the AI from continuing the current response.

```
POST /session/{id}/abort?directory=<path>
```

**Response**: `200 OK`

#### `POST /session/{id}/revert` — Undo Last User Message

Soft-hides the last user message and rolls back file changes on the server.

```
POST /session/{id}/revert?directory=<path>
Body: { "messageID": "msg_xyz789" }
```

**Response**: `SessionInfo` (updated session with `revert` field set)

```json
{
  "id": "ses_abc123",
  "...",
  "revert": {
    "messageID": "msg_xyz789",
    "partID": "prt_abc",
    "snapshot": "snap_123",
    "diff": "--- a/file.ts\n..."
  }
}
```

#### `POST /session/{id}/unrevert` — Redo (Restore Reverted)

Restores the previously reverted messages and file snapshot.

```
POST /session/{id}/unrevert?directory=<path>
Body: {}
```

**Response**: `SessionInfo` (with `revert` cleared)

---

### 4.2 Message Operations

#### `GET /session/{id}/message` — List Messages

Returns messages for a session. Supports cursor-based pagination.

```
GET /session/{id}/message?limit=50&directory=<path>
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `limit` | int | all | Max messages to return (cursor-based) |
| `directory` | string | null | Project directory |

> **Important**: Without `?limit=N`, ALL messages including huge tool outputs are returned. Always use limit to prevent OOM.

**Response**: `List<MessageInfo>`

```json
[
  {
    "info": {
      "id": "msg_001",
      "role": "user",
      "sessionID": "ses_abc",
      "parentID": null,
      "agent": null,
      "mode": null,
      "model": { "providerID": "anthropic", "modelID": "claude-sonnet-4-20250514" },
      "time": { "created": 1715123456, "completed": null },
      "finish": null,
      "cost": null,
      "tokens": null,
      "summary": null
    },
    "parts": [
      { "type": "text", "text": "Fix the login bug in auth.ts" }
    ]
  },
  {
    "info": {
      "id": "msg_002",
      "role": "assistant",
      "sessionID": "ses_abc",
      "agent": "code",
      "mode": "primary",
      "model": { "providerID": "anthropic", "modelID": "claude-sonnet-4-20250514" },
      "time": { "created": 1715123460, "completed": 1715123500 },
      "finish": "stop",
      "cost": 0.0034,
      "tokens": {
        "total": 128456,
        "input": 30000,
        "output": 3456,
        "reasoning": 1200,
        "cache": {
          "read": 95000,
          "write": 0
        }
      },
      "summary": true
    },
    "parts": [
      { "type": "text", "text": "I'll fix the login bug..." },
      {
        "type": "tool",
        "tool": "edit",
        "callID": "call_abc",
        "text": "Edited auth.ts",
        "state": {
          "status": "completed",
          "input": { "filePath": "src/auth.ts" },
          "output": "File edited successfully",
          "title": "Edit auth.ts"
        }
      },
      { "type": "text", "text": "The fix is applied." }
    ]
  }
]
```

#### `POST /session/{id}/prompt_async` — Send Message (Legacy)

Sends a user message asynchronously. HTTP returns immediately; AI output is delivered via SSE events.

```
POST /session/{id}/prompt_async?directory=<path>
```

**Request body**:

```json
{
  "parts": [{ "type": "text", "text": "Hello, help me with..." }],
  "agent": "code"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `parts` | array | yes | Message parts (always 1 text part) |
| `parts[].type` | string | yes | Always `"text"` |
| `parts[].text` | string | yes | The user's message |
| `agent` | string | no | Agent name. Null = server default |

**Response**: `204 No Content` (AI response comes via SSE)

> **Note**: Newer OpenCode builds may also support `POST /session/{id}/message` with the same body. Use `prompt_async` as the fallback.

---

### 4.3 Todo

#### `GET /session/{id}/todo` — Get Todo List

```
GET /session/{id}/todo?directory=<path>
```

**Response**: `List<TodoItem>`

```json
[
  { "content": "Fix auth.ts login bug", "status": "completed", "priority": "high" },
  { "content": "Add unit tests", "status": "in_progress", "priority": "medium" },
  { "content": "Update docs", "status": "pending", "priority": "low" }
]
```

---

### 4.4 Project

#### `GET /project/current` — Get Current Project

```
GET /project/current
```

**Response**: `ProjectInfo`

```json
{
  "id": "global",
  "worktree": "/",
  "time": { "created": 1715123456 },
  "sandboxes": []
}
```

#### `GET /project` — List All Projects

```
GET /project
```

**Response**: `List<ProjectInfo>` (array)

---

### 4.5 Agent

#### `GET /agent` — List Agents

Returns all available AI agents.

```
GET /agent
```

**Response**: `List<AgentInfo>`

```json
[
  {
    "name": "code",
    "mode": "primary",
    "description": "Main coding agent",
    "hidden": false,
    "model": { "modelID": "claude-sonnet-4-20250514", "providerID": "anthropic" }
  },
  {
    "name": "compaction",
    "mode": "primary",
    "description": "Context compaction agent",
    "hidden": true,
    "model": null
  },
  {
    "name": "ask",
    "mode": "subagent",
    "description": "Question-answering agent",
    "hidden": false,
    "model": null
  }
]
```

| Field | Values | Description |
|-------|--------|-------------|
| `mode` | `"primary"`, `"subagent"` | Primary agents can run independently; subagents are invoked by primary agents |
| `hidden` | boolean | `true` = internal utility agent, should not be shown in UI picker |

> **Note**: The server may return agents as a JSON array directly, or wrapped in `{"agents": [...]}`, `{"items": [...]}`, or `{"data": [...]}`.

---

### 4.6 File System

#### `GET /file?path=...` — List Files

Returns file/directory nodes at the given path.

```
GET /file?path=<path>&directory=<projectDir>
```

| Parameter | Description |
|-----------|-------------|
| `path` | Directory path to list. `"."` = project root |
| `directory` | Project directory scope |

**Response**: `List<FileNode>`

```json
[
  { "name": "src", "path": "src", "absolute": "D:\\Projects\\app\\src", "type": "directory", "ignored": false },
  { "name": "package.json", "path": "package.json", "absolute": "D:\\Projects\\app\\package.json", "type": "file", "ignored": false },
  { "name": "node_modules", "path": "node_modules", "absolute": "...", "type": "directory", "ignored": true }
]
```

| Field | Values |
|-------|--------|
| `type` | `"file"` or `"directory"` |
| `ignored` | `true` if in `.gitignore` or similar |

---

### 4.7 Provider / Model

#### `GET /config/providers` — List Providers & Models

Returns available LLM providers and their models.

```
GET /config/providers
```

**Response**: `ProviderList`

```json
{
  "providers": [
    {
      "id": "anthropic",
      "name": "Anthropic",
      "models": {
        "claude-sonnet-4-20250514": {
          "id": "claude-sonnet-4-20250514",
          "name": "Claude Sonnet 4"
        },
        "claude-haiku-4-20250514": {
          "id": "claude-haiku-4-20250514",
          "name": "Claude Haiku 4"
        }
      }
    }
  ],
  "default": {
    "anthropic": "claude-sonnet-4-20250514"
  }
}
```

> **Note**: `models` is a **Map** (JSON object keyed by model ID), not an array.

---

### 4.8 Permission / Question

#### `POST /permission/{requestId}/reply` — Reply to Permission Request

```
POST /permission/{requestId}/reply?directory=<path>
```

**Request body**:

```json
{
  "reply": "once",
  "message": "Optional reason for rejection"
}
```

| `reply` value | Description |
|---------------|-------------|
| `"once"` | Allow this one time |
| `"always"` | Always allow this pattern |
| `"reject"` | Deny the request |

#### `POST /question/{requestId}/reply` — Reply to Question

```
POST /question/{requestId}/reply?directory=<path>
```

**Request body**:

```json
{
  "answers": [["option_label"]]
}
```

`answers` is `List<List<String>>` — an array of answer arrays (one per question). For single-select questions, each inner array has one element.

#### `POST /question/{requestId}/reject` — Dismiss Question

```
POST /question/{requestId}/reject?directory=<path>
Body: {}
```

---

## 5. SSE Event Stream

### Endpoint

```
GET /global/event
Accept: text/event-stream
Cache-Control: no-cache
Authorization: Basic <base64> (if auth enabled)
```

### Event Format

Each SSE `data:` line contains a complete JSON event:

```
data: {"directory":"D:\\Projects\\myapp","project":"global","payload":{"type":"message.part.delta","properties":{"sessionID":"ses_abc","delta":"Hello"}}}
```

Server also sends heartbeat comments:

```
: ping
```

### Top-Level Structure

```json
{
  "directory": "D:\\Projects\\myapp",
  "project": "global",
  "payload": {
    "type": "<event_type>",
    "properties": { ... }
  }
}
```

### Event Types

#### `server.connected` — Initial Handshake

Emitted when the SSE connection is established.

```json
{
  "payload": {
    "type": "server.connected",
    "properties": {}
  }
}
```

#### `message.updated` — Message Created/Updated

A new message is created or its metadata changed. For assistant messages, this signals that the AI has started responding.

```json
{
  "payload": {
    "type": "message.updated",
    "properties": {
      "sessionID": "ses_abc",
      "messageID": "msg_002",
      "info": {
        "id": "msg_002",
        "role": "assistant",
        "agent": "code",
        "model": { "providerID": "anthropic", "modelID": "claude-sonnet-4-20250514" }
      }
    }
  }
}
```

**Key fields in `properties.info`**:
- `role`: `"user"` or `"assistant"`
- `agent`: Agent name (e.g. `"code"`)
- `model`: Which provider/model was used

#### `message.part.delta` — Streaming Text Chunk (Incremental)

Incremental text chunk during streaming. **Append** to the current segment.

```json
{
  "payload": {
    "type": "message.part.delta",
    "properties": {
      "sessionID": "ses_abc",
      "messageID": "msg_002",
      "partID": "prt_001",
      "callID": null,
      "delta": "Hello, I'll help",
      "field": null
    }
  }
}
```

| Field | Description |
|-------|-------------|
| `delta` | **Incremental** text to append |
| `field` | `"reasoning"` for thinking content, `null` for regular text |
| `callID` | Tool call ID (for tool output deltas) |

#### `message.part.updated` — Part Full Text So Far

The server sends the **full accumulated text** for a part. Useful for state recovery.

```json
{
  "payload": {
    "type": "message.part.updated",
    "properties": {
      "sessionID": "ses_abc",
      "messageID": "msg_002",
      "partID": "prt_001",
      "callID": null,
      "part": {
        "type": "text",
        "text": "Hello, I'll help you fix the bug.",
        "messageID": "msg_002"
      }
    }
  }
}
```

For tool parts:

```json
{
  "payload": {
    "type": "message.part.updated",
    "properties": {
      "sessionID": "ses_abc",
      "messageID": "msg_002",
      "callID": "call_abc",
      "part": {
        "type": "tool",
        "tool": "edit",
        "text": "Edited src/auth.ts",
        "state": {
          "status": "completed",
          "input": { "filePath": "src/auth.ts" },
          "output": "File edited",
          "title": "Edit auth.ts"
        }
      }
    }
  }
}
```

#### ~~`message.completed`~~ — Does NOT Exist as a Server Event

> **Correction**: `message.completed` does **not** exist as a distinct server-sent event. The server never emits an event with `type: "message.completed"`.
>
> Message completion is signaled by `message.updated` where `info.finish` is non-null (e.g. `"stop"`, `"error"`) or `info.time.completed` is non-null. Clients should detect completion via `message.updated`, not by waiting for a separate `message.completed` event.

#### `session.idle` — Session Turn Complete

The AI has finished its turn. This is the **primary signal** to stop streaming UI and reload messages.

```json
{
  "payload": {
    "type": "session.idle",
    "properties": {
      "sessionID": "ses_abc"
    }
  }
}
```

> **Note**: `session.idle` only carries `{ sessionID }`. No token data, no message data, no usage information is included in the payload.

**Client actions on `session.idle`**:
1. Stop streaming UI (hide stop button, clear streaming segments)
2. Reload messages from REST API (`GET /session/{id}/message`)
3. Update context usage from reloaded messages (token data is on individual messages, not on the session)
4. Refresh todo list

#### Which SSE Events Carry Token Data

| SSE Event | Token Location | When Available |
|-----------|---------------|----------------|
| `message.updated` | `properties.info.tokens` | When an assistant message is updated (including on completion) |
| `message.part.updated` | `properties.part.tokens` | On `step-finish` parts (per-step token usage) |
| `session.idle` | — | **No token data** |
| `message.part.delta` | — | **No token data** |
| `message.part.completed` | — | **No token data** |

> Token data is only attached to assistant messages. User messages always have `tokens: null`.

#### `session.updated` / `session.status` — Session Metadata Changed

Session metadata (title, status, etc.) was updated.

```json
{
  "payload": {
    "type": "session.updated",
    "properties": {
      "sessionID": "ses_abc",
      "info": {
        "id": "ses_abc",
        "title": "New Title",
        "time": { "completed": 1715124000 }
      }
    }
  }
}
```

#### `session.compacted` — Context Compaction Completed

The server automatically compacted the session context to free up space.

```json
{
  "payload": {
    "type": "session.compacted",
    "properties": {
      "sessionID": "ses_abc"
    }
  }
}
```

**Client actions**: Reload messages, recalculate context usage.

#### `session.diff` — Code Diff Generated

A code diff was generated (file changes).

```json
{
  "payload": {
    "type": "session.diff",
    "properties": {
      "sessionID": "ses_abc",
      "text": "--- a/auth.ts\n+++ b/auth.ts\n..."
    }
  }
}
```

#### `session.error` — Server Error

An error occurred during session processing.

```json
{
  "payload": {
    "type": "session.error",
    "properties": {
      "sessionID": "ses_abc",
      "error": "API rate limit exceeded"
    }
  }
}
```

#### `todo.updated` — Todo List Changed

Todo items were updated. Either contains the new list directly or signals a reload.

```json
{
  "payload": {
    "type": "todo.updated",
    "properties": {
      "sessionID": "ses_abc",
      "todos": [
        { "content": "Fix bug", "status": "completed", "priority": "high" }
      ]
    }
  }
}
```

If `properties.todos` is null, reload via `GET /session/{id}/todo`.

#### `message.part.completed` — Single Part Finished

One part of a multi-part message has completed.

```json
{
  "payload": {
    "type": "message.part.completed",
    "properties": {
      "sessionID": "ses_abc",
      "messageID": "msg_002",
      "partID": "prt_001"
    }
  }
}
```

#### `sync` — Internal Synchronization

Internal server event. Can be safely ignored by clients.

#### Permission / Question SSE Events

The server can emit blocking events that require user interaction before the AI continues.

##### `permission.asked` — Permission Request

The AI wants to perform an action that requires approval.

```json
{
  "payload": {
    "type": "permission.asked",
    "properties": {
      "sessionID": "ses_abc",
      "id": "req_001",
      "permission": "edit",
      "patterns": ["src/auth.ts"],
      "always": ["*.md"],
      "tool": {
        "messageID": "msg_002",
        "callID": "call_abc"
      }
    }
  }
}
```

| Field | Description |
|-------|-------------|
| `id` | Request ID — use in `POST /permission/{id}/reply` |
| `permission` | Permission type: `"edit"`, `"bash"`, `"read"`, `"write"`, `"glob"` |
| `patterns` | File path patterns the AI wants to access |
| `always` | Patterns the user has previously set to "always allow" |
| `tool` | Which tool call triggered this request |

**Client action**: Show permission bubble. User chooses Allow Once / Always Allow / Reject. Call `POST /permission/{id}/reply`.

##### `question.asked` — Question(s) Asked

The AI is asking the user one or more questions before proceeding.

```json
{
  "payload": {
    "type": "question.asked",
    "properties": {
      "sessionID": "ses_abc",
      "id": "req_002",
      "questions": [
        {
          "question": "Which framework do you use?",
          "header": "Framework",
          "options": [
            { "label": "React", "description": "React with TypeScript" },
            { "label": "Vue", "description": "Vue 3 with Composition API" }
          ],
          "multiple": false,
          "custom": true
        }
      ],
      "tool": {
        "messageID": "msg_002",
        "callID": "call_def"
      }
    }
  }
}
```

| Field | Description |
|-------|-------------|
| `id` | Request ID — use in `POST /question/{id}/reply` or `POST /question/{id}/reject` |
| `questions` | Array of question objects |
| `questions[].multiple` | Can the user select multiple options? |
| `questions[].custom` | Can the user type a custom answer? |

**Client action**: Show question bubble. User selects options or types custom answer. Call `POST /question/{id}/reply` with `answers: [["selected_label"]]`.

---

## 6. Data Models (DTOs)

### SessionInfo

> **Note**: SessionInfo does **not** contain token or usage fields. Token data is only available on individual assistant messages via `MessageInfoData.tokens`. To compute total session token usage, sum `tokens` across all assistant messages from `GET /session/{id}/message`.

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique session ID |
| `slug` | string? | URL-friendly name |
| `projectID` | string? | Parent project ID |
| `directory` | string? | Project directory path |
| `path` | string? | Session file path |
| `title` | string? | Display title |
| `version` | string? | Schema version |
| `summary` | SessionSummary? | Code change stats |
| `permission` | SessionPermission[]? | Permission rules |
| `parentID` | string? | Parent session ID (for forks) |
| `time` | SessionTime? | Timestamps |
| `revert` | SessionRevert? | Undo state (non-null = has active undo) |

### SessionTime

| Field | Type | Description |
|-------|------|-------------|
| `created` | long? | Unix timestamp (seconds) |
| `updated` | long? | Last updated |
| `initialized` | long? | When first message was sent |
| `completed` | long? | When session was marked complete (> 0 = completed) |

### SessionRevert

| Field | Type | Description |
|-------|------|-------------|
| `messageID` | string? | The reverted message ID |
| `partID` | string? | The reverted part ID |
| `snapshot` | string? | File snapshot reference |
| `diff` | string? | Diff of changes to revert |

### MessageInfo

| Field | Type | Description |
|-------|------|-------------|
| `info` | MessageInfoData | Message metadata |
| `parts` | MessagePart[] | Message content parts |

### MessageInfoData

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Message ID |
| `role` | string | `"user"` or `"assistant"` |
| `sessionID` | string? | Parent session |
| `parentID` | string? | Parent message (for forks) |
| `agent` | string? | Agent name that generated this |
| `mode` | string? | `"primary"` or `"subagent"` |
| `model` | MessageModel? | Provider/model used |
| `time` | MessageTime? | Created/completed timestamps |
| `finish` | string? | Finish reason: `"stop"`, `"error"`, etc. |
| `cost` | double? | Dollar cost of this message |
| `tokens` | MessageTokens? | Token usage |
| `summary` | JsonElement? | Summary flag (boolean true or diff object) |

### MessageTokens

> **Correction**: The server sends cache tokens as nested `cache.read` / `cache.write`, not as flat top-level `cacheRead` / `cacheWrite`. Some older client implementations may still handle flat fields via `@SerialName` annotations for backward compatibility, but the server output uses the nested structure.

| Field | Type | Description |
|-------|------|-------------|
| `total` | int? | Total tokens (from AI SDK `usage.totalTokens`). Most reliable when present |
| `input` | int? | Non-cached input tokens (adjusted: `inputTokens - cacheRead - cacheWrite`) |
| `output` | int? | Output/completion tokens (excluding reasoning) |
| `reasoning` | int? | Reasoning/thinking tokens |
| `cache` | CacheTokens? | Cache token breakdown (nested object) |

**Token calculation priority**:
1. If `total > 0` → use `total`
2. Otherwise → sum: `input + output + reasoning + cache.read + cache.write`
3. `cache` is a nested object with `read` and `write` fields, not flat `cacheRead`/`cacheWrite`

### CacheTokens

| Field | Type | Description |
|-------|------|-------------|
| `read` | int? | Cached tokens read (prompt cache hit) |
| `write` | int? | Cached tokens written |

### MessagePart

| Field | Type | Description |
|-------|------|-------------|
| `type` | string | `"text"`, `"tool"`, `"reasoning"`, `"step-start"`, `"step-finish"` |
| `text` | string? | Part text content |
| `id` | string? | Part ID |
| `sessionID` | string? | Session reference |
| `messageID` | string? | Message reference |
| `time` | PartTime? | Start/end timestamps |
| `reason` | string? | Reason (for tool calls) |
| `tokens` | MessageTokens? | Part-level token usage |
| `cost` | double? | Part-level cost |
| `tool` | string? | Tool name (e.g. `"edit"`, `"bash"`, `"read"`, `"glob"`) |
| `callID` | string? | Unique tool call identifier |
| `state` | ToolState? | Tool execution state |

### ToolState

| Field | Type | Description |
|-------|------|-------------|
| `status` | string? | `"running"`, `"completed"`, `"error"` |
| `input` | JsonElement? | Tool input (varies by tool) |
| `output` | string? | Tool output (can be very long) |
| `metadata` | JsonElement? | Additional metadata |
| `title` | string? | Human-readable title |
| `time` | JsonElement? | Execution time (`{start, end}` or string) |

### ProviderInfo

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Provider ID (e.g. `"anthropic"`, `"openai"`) |
| `name` | string? | Display name |
| `models` | Map&lt;String, ModelInfo&gt; | Models keyed by ID (JSON object, not array) |

### ModelInfo

| Field | Type | Description |
|-------|------|-------------|
| `id` | string? | Model ID |
| `name` | string? | Display name |
| `providerID` | string? | Parent provider |
| `status` | string? | Connection status |

### TodoItem

| Field | Type | Description |
|-------|------|-------------|
| `content` | string | Task description |
| `status` | string | `"pending"`, `"in_progress"`, `"completed"`, `"cancelled"` |
| `priority` | string? | `"high"`, `"medium"`, `"low"` |

> **Note**: No `id` field. Use list index as identifier.

### FileNode

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | File/directory name |
| `path` | string | Relative path |
| `absolute` | string | Absolute path |
| `type` | string | `"file"` or `"directory"` |
| `ignored` | boolean | True if in .gitignore |

### ProjectInfo

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Project ID (e.g. `"global"`) |
| `worktree` | string? | Working directory path. `"/"` = global |
| `time` | ProjectTime? | Timestamps |
| `sandboxes` | ProjectSandbox[] | Sandbox environments |

### AgentInfo

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Agent name (e.g. `"code"`, `"ask"`) |
| `mode` | string? | `"primary"` or `"subagent"` |
| `description` | string? | What this agent does |
| `hidden` | boolean | `true` = internal agent, hide from picker |
| `model` | AgentModel? | Default model for this agent |

---

## 7. Token Usage Reporting

OpenCode reports token usage per-message (not per-session). There is **no dedicated endpoint** for context usage. It must be derived from assistant message token data.

### Where Token Data Lives

| Location | Field | Available |
|----------|-------|-----------|
| `GET /session/{id}` (SessionInfo) | — | **No tokens** |
| `GET /session/{id}/message` (MessageInfo) | `info.tokens` | On assistant messages only |
| SSE `message.updated` | `properties.info.tokens` | When assistant message updates |
| SSE `message.part.updated` | `properties.part.tokens` | On step-finish parts |
| SSE `session.idle` | — | **No tokens** — only `{ sessionID }` |

### Token Fields (MessageTokens)

```json
{
  "total": 128000,
  "input": 1000,
  "output": 500,
  "reasoning": 200,
  "cache": {
    "read": 300,
    "write": 100
  }
}
```

- `total` is the most reliable field when present (from AI SDK `usage.totalTokens`)
- When `total` is absent, sum: `input + output + reasoning + cache.read + cache.write`
- The server sends nested `cache.read` / `cache.write`, not flat `cacheRead` / `cacheWrite` at the top level
- Tokens are attached during the LLM stream's `finish-step` event (in `processor.ts`)
- Some providers may not report all fields. Use fallback summation when `total` is missing

### Token Calculation on Server (`getUsage()`)

Located in `packages/opencode/src/session/session.ts`. The server:

1. Reads `LanguageModelUsage` from the AI SDK stream
2. Extracts `inputTokens`, `outputTokens`, `reasoningTokens`, `cacheReadTokens`, `cacheWriteTokens`
3. For cache write: checks provider-specific metadata (Anthropic, Vertex, Bedrock, Venice)
4. Computes `adjustedInputTokens = inputTokens - cacheReadTokens - cacheWriteTokens`
5. Returns `{ cost, tokens: { total, input, output, reasoning, cache: { read, write } } }`

### Cost Calculation

Cost is computed from token counts multiplied by model pricing per million tokens. Available on:

- `MessageInfoData.cost` — cumulative cost for the message
- `MessagePart.cost` — per-step cost (on step-finish parts)

### TUI Interaction Warning

When the same session is actively used in both the TUI and another client simultaneously:

- The TUI may trigger `undo`/`redo` operations that change the message history
- The TUI may trigger `compact` operations that reset the session context
- These changes propagate via SSE events (`session.updated`, `session.compacted`)
- After TUI operations, the server may return different message lists or token data
- If TUI triggers an undo, the `session.revert.messageID` changes, and messages after that point become hidden
- If TUI triggers compaction, messages are summarized and token counts reset

---

## Appendix A: Streaming Flow (End-to-End)

```
1. User sends message
   → POST /session/{id}/prompt_async
   → 204 No Content (immediate return)

2. AI starts processing
   ← SSE: message.updated (role="assistant", sets pendingMessageId)
   ← SSE: message.part.delta (incremental text chunks, field=null → text)
   ← SSE: message.part.delta (field="reasoning" → thinking)
   ← SSE: message.part.updated (full text so far, for recovery)
   ← SSE: message.part.delta (tool output deltas)
   ← SSE: message.updated (with info.finish="stop" when message completes)
      → Token data available in info.tokens on this final message.updated

3. AI finishes turn
   ← SSE: session.idle (turn complete, no token data)
   → Client: GET /session/{id}/message (reload full message list)
   → Client: Sum tokens from assistant messages for usage tracking
   → Client: Refresh todo list
```

## Appendix B: Multi-Project Discovery

OpenCode scopes sessions by project directory. A single `GET /session?list` only returns sessions for ONE project.

```
Strategy to show ALL sessions across projects:

1. GET /project → discover all projects
2. For each project:
   - Real project (worktree="/some/path"):
     GET /session?list&directory=/some/path
   - Global project (worktree="/"):
     GET /session?list&directory=/&scope=project
     (scope=project skips directory matching)
3. Merge results, deduplicate by session ID
```

## Appendix C: SSE Reconnection

The SSE stream is long-lived but may disconnect. Client should:

1. Detect disconnection (read error or heartbeat timeout)
2. Close old HTTP client
3. Create new HTTP client
4. Reconnect with exponential backoff (5s → 10s → 20s → 30s cap)
5. Max retries before giving up (5 recommended)
6. Heartbeat: if no SSE line received within 45s, treat as disconnect
