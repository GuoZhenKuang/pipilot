# PiPilot 代码库导读

本文介绍 PiPilot 项目的组织方式、数据在系统中的流转路径，以及如何安全地进行改动。

系统可视化图示见[架构总览（Mermaid 图）](architecture.md)。
持久化的决策依据见[架构决策记录](adr/README.md)。

## 目录

- [系统总览](#系统总览)
- [仓库布局](#仓库布局)
- [模块职责](#模块职责)
- [关键运行时流程](#关键运行时流程)
  - [1) 连接与恢复会话](#1-连接与恢复会话)
  - [2) 发送提示与流式事件](#2-发送提示与流式事件)
  - [3) 重连与重新同步](#3-重连与重新同步)
  - [4) 会话树与导航](#4-会话树与导航)
  - [5) 会话一致性监控与同步](#5-会话一致性监控与同步)
- [Bridge 控制模型](#bridge-控制模型)
- [Android 侧状态管理](#android-侧状态管理)
- [测试策略](#测试策略)
- [常见改动场景](#常见改动场景)
- [参考文件](#参考文件)

## 系统总览

```text
Android 应用 (Compose)
    │ WebSocket（信封: { channel, payload }）
    ▼
Bridge (Node.js)
    │ stdin/stdout JSON RPC
    ▼
pi --mode rpc
    + 内部扩展（pi-mobile-tree、pi-mobile-open-stats）
```

应用从不直接与 pi 进程通信，而是与 Bridge 通信。Bridge 负责：

- 认证与客户端身份
- 每个 cwd 管理一个 pi 子进程
- 对每个 cwd/会话强制单客户端控制锁
- 转发 RPC 事件与 Bridge 控制消息

这一保留边界记录在 [ADR-0004](adr/ADR-0004-retain-rpc-subprocess-boundary.md)：每个 cwd 一个隔离的 `pi --mode rpc` 进程。

## 仓库布局

| 路径 | 用途 |
|---|---|
| `app/` | Android UI、ViewModel、主机/会话交互 |
| `core-rpc/` | Kotlin RPC 命令/事件模型与解析 |
| `core-net/` | WebSocket 传输、信封路由、重连/重同步 |
| `core-sessions/` | 会话索引模型、缓存、仓库逻辑 |
| `bridge/` | Node Bridge 服务、协议、进程管理、扩展 |
| `benchmark/` | Android macrobenchmark 模块与基线性能档案脚手架 |
| `docs/` | 面向人的项目文档 |
| `docs/ai/` | 规划/进度产物 |

## 模块职责

### `app/`（Android 应用）

- Compose 界面与浮层
- `ChatViewModel`：聊天时间线、命令面板、扩展对话框/小组件、会话树/统计/模型面板
- `RpcSessionController`：基于 `PiRpcConnection` 的高层会话操作
- 主机管理与令牌存储

### `core-rpc/`

- `RpcCommand` 密封模型：对外命令
- `RpcIncomingMessage` 密封模型：入站事件/响应
- `RpcMessageParser`：按线上 `type` 映射到类型化事件类

### `core-net/`

- `WebSocketTransport`：带外发队列的自动重连传输
- `PiRpcConnection`：
  - 把 socket 消息包装为信封协议
  - 路由 bridge 与 rpc 两类通道
  - 执行握手（`bridge_hello`、设置 cwd、获取控制）
  - 暴露 `rpcEvents`、`bridgeEvents` 与 `resyncEvents`

### `core-sessions/`

- 按主机隔离的会话索引状态与筛选
- 缓存优先渲染 + 后台刷新
- 按主机的刷新合并、限流与失败退避
- 内存与文件两级缓存实现

### `bridge/`

- `server.ts`：WebSocket 服务、令牌校验、协议分发、健康端点
- `process-manager.ts`：按 cwd 的转发器 + 控制锁
- `rpc-forwarder.ts`：pi 子进程生命周期/重启/退避
- `session-indexer.ts`：流式 JSONL 索引，共享修订快照、有界尾部新鲜度、会话树缓存与有界淘汰
- `extensions/`：内部移动端 Bridge 扩展

## 关键运行时流程

### 1) 连接与恢复会话

1. 应用创建 `PiRpcConnectionConfig`（`url`、`token`、`cwd`、`clientId`）。
2. Bridge 返回 `bridge_hello`；应用设置 cwd 并获取控制。
3. 在保留的聊天目的地渲染之前，`RpcSessionController` 先发布 `isSwitching = true` 的新活动会话代次，让旧会话内容立即隐藏。
4. 对选定会话，控制器发送有文档记载的 `switch_session`，刷新权威活动路径，重置条目投影，再发布落定代次。
5. 控制器主导的引导先 `get_state`，再使用当前 `get_entries` 投影；只有在没有安全投影时才回退到有文档记载的 `get_messages`。
6. `ChatViewModel` 用代次标记引导与会话树工作，切换后拒绝迟到的结果。

### 2) 发送提示与流式事件

1. 用户在 `ChatViewModel` 发送提示
2. `RpcSessionController.sendPrompt()` 发送 `prompt`
3. Bridge 把 RPC 载荷转发给活动 cwd 进程
4. pi 发出流式事件（`message_update`、工具事件、`agent_end` 等）
5. `ChatViewModel` 更新时间线与流式状态

### 3) 重连与重新同步

`WebSocketTransport` 以指数退避自动重连。

重连后 `PiRpcConnection` 会：

- 等待新的 `bridge_hello`
- 按需重新设置 cwd / 重新获取控制
- 在 `get_state + get_entries` 后发出 `RpcResyncSnapshot`，以最后条目 ID 作为重连游标

这保证了网络中断后时间线与流式标志的一致性。

### 4) 会话树与导航

会话树的展示与导航有各自的数据权威：

- 活动会话使用 Pi 有文档记载的 `get_tree`。后台权威刷新运行时，应用可把上一次缓存结果标记为过期。
- 非活动会话浏览使用 Bridge 经过校验、按修订版本键控的 JSONL 会话树缓存。
- 会话切换会取消或按代次拒绝迟到的树结果。

导航仍是唯一的内部扩展路径，因为 Pi 0.80.6 没有树导航 RPC 命令：

1. 应用发送 `bridge_navigate_tree { entryId }`。
2. Bridge 校验 cwd/控制与请求的条目 ID。
3. Bridge 发送 RPC `prompt`：`/pi-mobile-tree <entryId> <statusKey>`。
4. 扩展发出 `setStatus(statusKey, JSON 载荷)`。
5. Bridge 返回脱敏后的 `bridge_tree_navigation_result`。
6. 应用更新编辑草稿并刷新权威会话树。

### 5) 会话一致性监控与同步

Bridge 观察到的变更会推送 `bridge_session_invalidated` 触发即时游标重同步。为覆盖终端或其他外部直接编辑，`ChatViewModel` 在聊天页前台活跃期间每 60 秒做一次兜底新鲜度检查。

Bridge 基于文件修订与有界的解析/尾部数据计算脱敏指纹。应用对指纹变化的分类处理：

- 处于本地变更宽限窗口内：更新基线；
- 空闲且无明确他端占用：静默刷新；
- 忙碌且无明确他端占用：延迟到空闲再刷新；
- 明确的他端锁占用：展示一次限流的、可操作的冲突提示与 **立即同步**；
- 重载失败：展示可操作的恢复错误。

未知游标、分支移动、会话替换、不支持的条目或无效投影只触发一次显式全量重建。应用绝不把指纹变化单独当作冲突证据。

## Bridge 控制模型

Bridge 用锁所有权防止冲突写入。

- 锁粒度：cwd（可选 sessionPath）
- 只有锁所有者能向该 cwd 发送 RPC 流量
- 非所有者收到 `bridge_error`（`control_lock_required` 或 `control_lock_denied`）

这在多个移动客户端同时连接时保护会话完整性。

## Android 侧状态管理

主状态所有者：`ChatViewModel`（`StateFlow<ChatUiState>`）。

重要子状态：

- 连接与流式状态
- 时间线（有界窗口历史 + 实时更新）
- 命令面板与斜杠命令元数据
- 扩展对话框/通知/小组件/标题
- bash 对话框状态
- 统计/模型/会话树底部面板状态
- 延迟的新鲜度刷新、明确的他端冲突与同步进行中状态

高层设计：

- 传输/网络关注点留在 `core-net` + `RpcSessionController`
- 渲染关注点留在 Compose 界面
- 事件到状态的逻辑留在 `ChatViewModel`

## 测试策略

### Android

- 以 ViewModel 为核心的单元测试在 `app/src/test/...`
- 覆盖命令筛选、扩展工作流处理、时间线行为、队列语义

### Bridge

- Vitest 套件在 `bridge/test/...`
- 覆盖认证、非法载荷、控制锁、重连、树导航、健康端点

### 命令

```bash
# Android 完整非设备门禁
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin

# Bridge 质量与生产依赖门禁
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
```

## 常见改动场景

### 端到端新增一条 RPC 命令

1. 在 `core-rpc/RpcCommand.kt` 添加命令模型
2. 在 `core-net/RpcCommandEncoding.kt` 添加编码映射
3. 在 `RpcSessionController` 添加控制器方法
4. 从 ViewModel/UI 调用
5. 在 app + bridge 添加测试（若涉及 Bridge 控制）

### 新增一条 Bridge 控制消息

1. 在 `bridge/src/server.ts` 添加消息处理
2. 在 Android 添加载荷解析/调用点（`PiRpcConnection.requestBridge` 调用处）
3. 在 `docs/bridge-protocol.md` 补充协议文档
4. 在 `bridge/test/server.test.ts` 添加测试

### 新增一个内部扩展工作流

参照 `docs/extensions.md` 的清单执行。

## 参考文件

- `app/src/main/java/top/guozk/pipilot/chat/ChatViewModel.kt`
- `app/src/main/java/top/guozk/pipilot/sessions/RpcSessionController.kt`
- `core-net/src/main/kotlin/top/guozk/pipilot/corenet/PiRpcConnection.kt`
- `core-net/src/main/kotlin/top/guozk/pipilot/corenet/WebSocketTransport.kt`
- `core-rpc/src/main/kotlin/top/guozk/pipilot/corerpc/RpcCommand.kt`
- `core-rpc/src/main/kotlin/top/guozk/pipilot/corerpc/RpcIncomingMessage.kt`
- `bridge/src/server.ts`
- `bridge/src/process-manager.ts`
- `bridge/src/session-indexer.ts`
