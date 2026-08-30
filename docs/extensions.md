# 自定义扩展（PiPilot）

PiPilot 使用 **pi 内部扩展** 提供标准 RPC 命令不具备的移动端专属工作流。

这些扩展由 Bridge 在启动 `pi --mode rpc` 时加载。

## 目录

- [总览](#总览)
- [扩展文件位置](#扩展文件位置)
- [运行时加载](#运行时加载)
- [扩展 1：`pi-mobile-tree`](#扩展-1-pi-mobile-tree)
- [扩展 2：`pi-mobile-open-stats`](#扩展-2-pi-mobile-open-stats)
- [Android 客户端集成](#android-客户端集成)
- [扩展 UI 方法支持](#扩展-ui-方法支持)
- [如何新增内部扩展](#如何新增内部扩展)
- [故障排查](#故障排查)
- [参考文件](#参考文件)

## 总览

自定义扩展刻意保持为三端之间的**内部管道**：

- Node Bridge（`bridge/`）
- pi 运行时
- Android 客户端（`app/`）

它们用于：

1. 以结构化结果实现原位会话树导航
2. 触发移动端专属工作流动作（当前为：打开统计面板）

这些命令不应出现在移动端命令面板的用户可见命令中。

## 扩展文件位置

- `bridge/src/extensions/pi-mobile-tree.ts`
- `bridge/src/extensions/pi-mobile-workflows.ts`

## 运行时加载

Bridge 把两个扩展注入每个 pi RPC 子进程：

- `--extension bridge/src/extensions/pi-mobile-tree.ts`
- `--extension bridge/src/extensions/pi-mobile-workflows.ts`

实现位置：

- `bridge/src/server.ts`（`createPiRpcForwarder(...args)`）

## 扩展 1：`pi-mobile-tree`

**命令名：** `pi-mobile-tree`
**用途：** 由 Bridge 执行会话树导航并返回结构化状态载荷。

### 参数

`/<command> <entryId> <statusKey>`

- `entryId`：必需，目标树节点 ID
- `statusKey`：必需，必须以 `pi_mobile_tree_result:` 开头

参数非法时命令直接退出，无副作用。

### 行为

1. `waitForIdle()`
2. `navigateTree(entryId, { summarize: false })`
3. 导航未被取消时，通过 `ctx.ui.setEditorText(...)` 更新编辑器文本
4. 通过 `ctx.ui.setStatus(statusKey, JSON.stringify(payload))` 发出结果
5. 立即用 `ctx.ui.setStatus(statusKey, undefined)` 清除状态键

### 结果载荷结构

```json
{
  "cancelled": false,
  "editorText": "retry this branch",
  "currentLeafId": "entry-42",
  "sessionPath": "/home/user/.pi/agent/sessions/...jsonl",
  "error": "optional"
}
```

发生异常时设置 `error`，Bridge 视导航为失败。

## 扩展 2：`pi-mobile-open-stats`

**命令名：** `pi-mobile-open-stats`
**用途：** 发出工作流动作，打开 Android 应用中的统计面板。

### 行为

- 接受可选的动作参数
- 默认动作：`open_stats`
- 未知的动作被静默拒绝

接受时发出：

- 状态键：`pi-mobile-workflow-action`
- 状态文本：`{"action":"open_stats"}`

随后立即清除状态键。

## Android 客户端集成

Android 客户端把它们视为内部 Bridge 机制。

### 内部命令常量

定义于 `ChatViewModel`：

- `pi-mobile-tree`
- `pi-mobile-open-stats`

通过过滤内部名称，把它们从可见的斜杠命令结果中隐藏。

### 内置命令映射

| 移动端命令 | 行为 |
|---|---|
| `/tree` | 直接打开移动端会话树面板 |
| `/stats` | 尝试内部 `/pi-mobile-open-stats`，不可用时回退到本地统计面板 |
| `/model` | 打开模型选择器 |
| `/session` | 打开统计/会话总览面板 |
| `/compact` | 对当前会话执行压缩 |
| `/export` | 把当前会话导出为 HTML |
| `/import` | 打开 Android 文档选择器，把 JSONL 会话导入当前运行时 |
| `/copy` | 复制最新助手回复到剪贴板 |
| `/fork` | 打开会话树面板并引导选择分叉条目 |
| `/new` | 开始新会话 |
| `/name <新名称>` | 重命名当前会话 |
| `/settings` | 提示前往设置页 |
| `/hotkeys`、`/resume`、`/share`、`/reload`、`/changelog`、`/scoped-models` | 明确标记为移动端不可用 |

### 工作流状态处理

`ChatViewModel` 监听满足以下条件的 `extension_ui_request`：

- `method = setStatus`
- `statusKey = pi-mobile-workflow-action`

载荷动作为 `open_stats` 时打开统计面板。

非工作流状态键显示在扩展状态条中，可在设置里隐藏。

## 扩展 UI 方法支持

PiPilot 当前处理这些 `extension_ui_request` 方法：

| 方法 | 客户端行为 |
|---|---|
| `select` | 显示单选对话框 |
| `confirm` | 显示确认对话框 |
| `input` | 显示文本输入对话框 |
| `editor` | 显示多行编辑对话框 |
| `notify` | 显示临时通知 |
| `setStatus` | 处理内部工作流键（`pi-mobile-workflow-action`） |
| `setWidget` | 更新编辑器上/下方的扩展小组件 |
| `setTitle` | 更新聊天标题 |
| `set_editor_text` | 替换输入框文本 |

相关模型类型：

- `core-rpc/.../ExtensionUiRequestEvent`
- `core-rpc/.../ExtensionErrorEvent`

## 如何新增内部扩展

按此清单安全接入：

1. **创建扩展文件** 于 `bridge/src/extensions/`
2. **注册命令** 并使用明确的内部名称（以 `pi-mobile-` 为前缀）
3. **在 Bridge 中加载**（`bridge/src/server.ts` 的转发器参数）
4. **定义状态键契约**（若通过 `setStatus` 通信）
5. **隐藏内部命令** 于 `ChatViewModel.INTERNAL_HIDDEN_COMMAND_NAMES`
6. **接通客户端处理**（事件解析 + UI 更新 + 回退行为）
7. **添加测试**
   - Bridge 行为（`bridge/test/server.test.ts`）
   - ViewModel 行为（`app/src/test/...`）
8. **在本文件记录载荷结构**

## 故障排查

### `/stats` 无反应

检查：

- `get_commands` 包含 `pi-mobile-open-stats`
- 扩展已由 Bridge 通过子进程参数加载
- `setStatus` 事件载荷的 action 恰为 `open_stats`

### 树导航返回 `tree_navigation_failed`

检查：

- `get_commands` 包含 `pi-mobile-tree`
- 发出的状态键以 `pi_mobile_tree_result:` 开头
- 扩展在 `statusText` 中返回了合法 JSON 载荷

### 内部命令出现在命令面板

检查 `ChatViewModel.INTERNAL_HIDDEN_COMMAND_NAMES` 是否包含：

- `pi-mobile-tree`
- `pi-mobile-open-stats`

## 参考文件

- `bridge/src/extensions/pi-mobile-tree.ts`
- `bridge/src/extensions/pi-mobile-workflows.ts`
- `bridge/src/server.ts`
- `app/src/main/java/top/guozk/pipilot/chat/ChatViewModel.kt`
- `app/src/main/java/top/guozk/pipilot/ui/chat/ChatOverlays.kt`
- `bridge/test/server.test.ts`
- `app/src/test/java/top/guozk/pipilot/chat/ChatViewModelWorkflowCommandTest.kt`
