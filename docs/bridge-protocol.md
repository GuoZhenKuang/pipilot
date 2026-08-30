# Bridge 协议参考

本文描述 Android 客户端与 PiPilot Bridge 之间的 WebSocket 协议。

## 目录

- [传输与端点](#传输与端点)
- [认证](#认证)
- [信封格式](#信封格式)
- [连接握手](#连接握手)
- [Bridge 通道消息](#bridge-通道消息)
- [RPC 通道消息](#rpc-通道消息)
- [错误](#错误)
- [健康端点](#健康端点)
- [典型消息时序](#典型消息时序)
- [参考文件](#参考文件)

## 传输与端点

- 协议：WebSocket
- 端点：`ws://<host>:<port>/ws`
- 可选的重连身份：`?clientId=<uuid>`

所有消息都是 JSON 信封，通道为以下二者之一：

- `bridge`（控制面）
- `rpc`（pi RPC 载荷）

## 认证

必须携带有效的 Bridge 令牌。

支持的请求头：

- `Authorization: Bearer <token>`
- `x-bridge-token: <token>`

注意：

- 不接受查询字符串中的令牌
- 令牌无效 → WebSocket 升级时返回 HTTP 401

## 信封格式

```json
{
  "channel": "bridge",
  "payload": {
    "type": "bridge_ping"
  }
}
```

```json
{
  "channel": "rpc",
  "payload": {
    "id": "req-1",
    "type": "get_state"
  }
}
```

校验规则：

- 信封必须是 JSON 对象
- `channel` 必须是 `bridge` 或 `rpc`
- `payload` 必须是 JSON 对象

## 连接握手

WebSocket 连接后，Bridge 发送：

```json
{
  "channel": "bridge",
  "payload": {
    "type": "bridge_hello",
    "clientId": "...",
    "resumed": false,
    "cwd": null,
    "reconnectGraceMs": 30000,
    "message": "Bridge skeleton is running"
  }
}
```

以相同 `clientId` 重连时 `resumed` 可能为 `true`，并恢复上次的 `cwd`。

## Bridge 通道消息

### 请求 → 响应对照

| 请求 `payload.type` | 响应 `payload.type` | 说明 |
|---|---|---|
| `bridge_ping` | `bridge_pong` | 存活检查 |
| `bridge_list_sessions` | `bridge_sessions` | 返回分组的会话元数据 |
| `bridge_get_session_tree` | `bridge_session_tree` | 需要 `sessionPath`；支持筛选 |
| `bridge_get_session_freshness` | `bridge_session_freshness` | 返回新鲜度指纹 + 锁占用元数据 |
| `bridge_import_session_jsonl` | `bridge_session_imported` | 需要控制锁；把 JSONL 会话写入活动运行时会话目录并切换过去 |
| `bridge_navigate_tree` | `bridge_tree_navigation_result` | 需要控制锁；使用内部扩展命令 |
| `bridge_set_cwd` | `bridge_cwd_set` | 为客户端设置活动 cwd 上下文 |
| `bridge_acquire_control` | `bridge_control_acquired` | 获取 cwd/会话的写锁 |
| `bridge_release_control` | `bridge_control_released` | 释放已持有的锁 |
| `bridge_get_or_create_session_share` | `bridge_session_share` | 为一个唯一已索引会话生成认证的稳定不透明引用 |
| `bridge_resolve_session_share` | `bridge_session_share_resolved` | 认证查询；重复/已删除/已撤销身份统一解析为不可用 |
| `bridge_revoke_session_share` | `bridge_session_share_revoked` | 认证的所有者操作；下次创建会生成新引用 |

当通过活动 pi 进程观察到变更、发生会话导入/切换或树导航时，Bridge 还会向控制客户端推送 `bridge_session_invalidated { reason }`。客户端应立即执行游标同步。

### 稳定会话共享

Bridge 读取第一个有界长度的 Pi 会话头部，以其有文档记载的 `id` 作为内部身份。有效的重复 ID 仍会列出，但不可共享、不可解析。文件移动后映射保持不变，因为引用指向 ID 而非路径。

上述三个共享操作是仅元数据的认证 Bridge 操作，不会绕过 cwd/控制锁。解析返回唯一一条当前 `SessionRecord`；恢复它使用常规的 cwd 设置、控制锁、`switch_session`、游标同步以及切换后的 `get_state.sessionId` 校验。

引用是 16 字节随机数据，编码为不带填充的 base64url（22 字符）。版本化的仅所有者状态保存在 `${BRIDGE_STATE_DIR}/share-references.json`，通过同步临时文件 + 原子重命名写入。其中不包含令牌、路径、cwd、标题、预览或对话内容。状态损坏/版本不受支持时返回 `share_state_unavailable`，正常的列表与 RPC 不受影响。撤销是持久且显式的；删除/重置状态会使链接失效，需要操作者修复恢复。

配置 `BRIDGE_SHARE_ORIGIN` 为严格 HTTP(S) 源时，落地页为 `/s/v1/<reference>`，只包含通用的「在应用中打开」指引。不使用请求 `Host` 头，返回 `no-store`、no-referrer、`nosniff`、防框架与严格 CSP 头。自定义 scheme 兜底为 `pimobile://open/v1/<reference>?host=<authority>&port=<port>&tls=<0|1>`。

### `bridge_get_session_tree` 筛选器

允许的取值：

- `default`
- `all`
- `no-tools`
- `user-only`
- `labeled-only`

未知筛选 → `bridge_error`（`invalid_tree_filter`）。

Bridge 拒绝超过 `BRIDGE_WEBSOCKET_MAX_PAYLOAD_BYTES`（默认 16 MiB）的 WebSocket 消息。

### `bridge_get_session_freshness`

请求载荷：

```json
{
  "type": "bridge_get_session_freshness",
  "sessionPath": "/.../session.jsonl"
}
```

响应载荷：

```json
{
  "type": "bridge_session_freshness",
  "sessionPath": "/.../session.jsonl",
  "cwd": "/.../project",
  "fingerprint": {
    "mtimeMs": 1730000000000,
    "sizeBytes": 2048,
    "entryCount": 42,
    "lastEntryId": "m42",
    "lastEntriesHash": "..."
  },
  "lock": {
    "cwdOwnerClientId": "client-a",
    "sessionOwnerClientId": "client-a",
    "isCurrentClientCwdOwner": true,
    "isCurrentClientSessionOwner": true
  }
}
```

### `bridge_import_session_jsonl`

请求载荷：

```json
{
  "type": "bridge_import_session_jsonl",
  "fileName": "shared-session.jsonl",
  "content": "{\"type\":\"session\",...}\n{...}\n"
}
```

响应载荷：

```json
{
  "type": "bridge_session_imported",
  "sessionPath": "/.../shared-session.jsonl"
}
```

注意：

- 需要 cwd 上下文 + 控制锁
- 把上传的 JSONL 写入 Bridge 会话目录
- 把活动 pi 运行时切换到导入的会话
- 文件名在服务端脱敏并去重，防止路径穿越与覆盖
- UTF-8 内容受 `BRIDGE_IMPORT_MAX_BYTES`（默认 10 MiB）限制；超限返回 `import_payload_too_large` 且不断开连接

### `bridge_navigate_tree`

请求载荷：

```json
{
  "type": "bridge_navigate_tree",
  "entryId": "entry-42"
}
```

响应载荷：

```json
{
  "type": "bridge_tree_navigation_result",
  "cancelled": false,
  "editorText": "retry from here",
  "currentLeafId": "entry-42",
  "sessionPath": "/.../session.jsonl"
}
```

## RPC 通道消息

`rpc` 通道原样转发 pi RPC 命令/事件。Android 用 `get_entries` 做活动会话同步、用 `get_tree` 获取活动拓扑。跨项目会话列表与非活动会话树浏览仍由 Bridge 负责。树导航也仍由 Bridge 负责，因为 Pi 0.80.6 没有导航类 RPC 命令。

### 发送 RPC 载荷的前置条件

客户端必须已完成：

1. cwd 上下文（`bridge_set_cwd`）
2. 控制锁（`bridge_acquire_control`）

否则 Bridge 返回 `bridge_error`，代码为 `control_lock_required`。

### 转发行为

- 请求载荷转发给该 cwd 专属 pi 子进程的 stdin
- pi stdout 事件包装为 `{ channel: "rpc", payload: ... }`
- 事件只投递给该 cwd 的控制客户端

## 错误

Bridge 错误统一使用：

```json
{
  "channel": "bridge",
  "payload": {
    "type": "bridge_error",
    "code": "error_code",
    "message": "可读的错误说明"
  }
}
```

常见代码：

- `malformed_envelope`
- `unsupported_bridge_message`
- `missing_cwd_context`
- `invalid_cwd`
- `invalid_session_path`
- `invalid_tree_filter`
- `invalid_tree_entry_id`
- `invalid_import_payload`
- `control_lock_required`
- `control_lock_denied`
- `invalid_rpc_payload`
- `rpc_forward_failed`
- `tree_navigation_failed`
- `session_index_failed`
- `session_tree_failed`
- `session_freshness_failed`
- `session_import_failed`
- `import_payload_too_large`

## 健康端点

可选 HTTP 端点：

- `GET /health`
- 由 `BRIDGE_ENABLE_HEALTH_ENDPOINT=true` 启用

响应包含：

- 运行时长
- 进程管理统计
- 已连接/可重连的客户端计数

关闭时 `/health` 返回 404。

## 典型消息时序

一次典型 RPC 会话的最小时序：

1. 携带认证令牌连接 WebSocket
2. 接收 `bridge_hello`
3. 发送 `bridge_set_cwd`
4. 发送 `bridge_acquire_control`
5. 发送 `rpc` 命令载荷（`get_state`、`prompt` 等）
6. 接收 `rpc` 事件与 `response` 载荷
7. 可选发送 `bridge_release_control`

## 参考文件

- `bridge/src/protocol.ts`
- `bridge/src/server.ts`
- `bridge/src/process-manager.ts`
- `core-net/src/main/kotlin/top/guozk/pipilot/corenet/PiRpcConnection.kt`
- `bridge/test/server.test.ts`
