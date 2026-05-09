# AGENTS.md - OConnector

## 项目概述

OConnector 是一个 Android 客户端，用于连接 OpenCode 服务器，浏览项目与会话、进行聊天、查看任务进度，并支持 HTTPS/TLS 连接与中英文界面切换。

## 技术栈

- Kotlin 1.9.22
- Android Gradle Plugin 8.2.2
- Jetpack Compose Material3
- Hilt
- Ktor Client + SSE
- kotlinx.serialization
- DataStore + EncryptedSharedPreferences

## 架构引导

- `app/src/main/java/com/opencode/remote/data/`：API、DTO、仓库、SSE、DataStore 等数据层
- `app/src/main/java/com/opencode/remote/ui/`：连接页、会话页、聊天页、帮助页、主题与字符串资源
- `app/src/main/java/com/opencode/remote/service/`：前台服务
- `app/src/test/java/com/opencode/remote/`：单元测试
- `build.gradle.kts`：顶层插件版本
- `settings.gradle.kts`：插件仓库与依赖仓库声明

## 构建 & 验证

- 基本构建：`./gradlew --no-daemon assembleDebug`
- 单元测试：`./gradlew --no-daemon testDebugUnitTest`
- 依赖 Android SDK：设置 `ANDROID_HOME` 与 `ANDROID_SDK_ROOT`
- 调试包输出：`app/build/outputs/apk/debug/app-debug.apk`
- 查看任务帮助：`./gradlew --no-daemon help`

## 项目内工具

- `./gradlew`：Gradle Wrapper，项目标准构建入口
- `scripts/start-server.sh`：macOS/Linux 启动 OpenCode 服务脚本
- `scripts/start-server.bat`：Windows 启动 OpenCode 服务脚本

## 环境配置

- 需要 Android SDK（容器内可用路径通常由 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 提供）
- 调试构建目标为 Android 8.0+（API 26+）

## 约定 & 规范

- UI 文案同时维护英文与中文版本
- 会话列表相关逻辑集中在 `ui/sessions/`
- 新增偏好项时优先放入 `ConnectionPreferences`

## 已知问题 & 决策记录

- 会话列表支持通过顶部眼睛按钮隐藏子会话，基于 `parentID` 做本地过滤，不修改服务端协议
- 项目内会话菜单现支持重命名、分享/取消分享、复制分享链接、复制会话 ID、Fork、删除；重命名走 `PATCH /session/{id}`，分享走 `/session/{id}/share`
- 聊天页顶栏显示上下文占用百分比：取最近一条带 tokens 的 assistant 消息，并用 `/provider` 返回的 `model.limit.context` 计算百分比
- 聊天页顶栏支持直接重命名当前会话；Agent 选择会按 sessionId 持久化，并优先从最近实际使用的 assistant agent 恢复
- 登录页会读取已保存配置并在 `auto_login_enabled` 为 true 时自动连接；仅当用户显式点击登出时才关闭自动恢复
- 上下文占用现在除了右上角环形按钮外，还会直接显示在聊天页标题下方；拿不到 `model.limit.context` 时退化为显示 token 总量，不再完全隐藏
- 容器环境下若 Java 默认 truststore 自动装配异常，构建时可显式指定 JVM truststore；这属于运行环境问题，不属于项目源码问题
