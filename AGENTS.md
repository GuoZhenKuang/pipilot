# PiPilot 贡献者与智能体指南

## 架构不变量

- 保持「Android → 经过认证的 WebSocket Bridge → 每个工作目录一个隔离的 `pi --mode rpc` 进程」的架构不变。
- 不得把 Bridge 迁移到 Pi SDK，也不得移除 cwd/会话控制锁。
- Pi 0.80.6 是最低且经过测试的运行时版本。
- 只使用有文档记载的 RPC 命令。活动读取使用 `get_entries` 与 `get_tree`；内部扩展仅用于会话树导航，因为 Pi 0.80.6 没有导航类 RPC 命令。
- 遇到未知会话条目时，只触发一次显式的全量重建。绝不要猜测私有会话文件的行为。

## 模块

- `app`：Compose UI、界面状态、主机/令牌存储与会话控制器。
- `core-rpc`：类型化的 Pi 命令/事件，以及脱敏后的兼容性测试夹具。
- `core-net`：WebSocket 传输、经过认证的 Bridge 控制、请求关联、重连与条目游标。
- `core-sessions`：缓存会话索引的模型与仓库。
- `bridge`：认证、WebSocket 信封、锁、进程生命周期、非活动会话索引与内部扩展。
- `benchmark`：由设备侧持有的 macrobenchmark/基线性能档案脚手架。

回归测试放在所属模块旁边。协议夹具放在 `core-rpc/src/test/resources/rpc`，且必须经过脱敏。

## 必需命令

Gradle 与编译使用 JDK 25；detekt 稳定 CLI 保留 JDK 21 工具链；安装 Android SDK platform 37.0 / build-tools 37.0.0。Node 24 LTS+、pnpm 10、Pi 0.80.6+。

```bash
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
```

在不启动设备的情况下编译设备测试：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

除非操作者明确拥有并主动要求，否则不要运行 connected/device 验收测试。

## 密钥与敏感信息

- 绝不打印、展示、记录、提交或复制令牌、认证头、`.env` 内容、凭据或私有会话。
- 令牌使用 Android Keystore AES-GCM 加密，不得进入普通偏好存储或系统备份。
- 不要添加签名凭据。release 构建使用仓库安全的未签名/默认配置。

## 计划协议

1. 阅读 `plans/README.md`、完整的当前计划、引用的文档以及 Pi 当前文档。
2. 编辑前运行计划漂移检查，并把状态表中的行标记为 `IN PROGRESS`。
3. 行为变更须附带特征化/回归测试。
4. 运行针对性检查、完整计划门禁以及 `git diff --check`。
5. 使用小粒度的 Conventional Commits 提交。绝不推送、合并或改写历史。
6. 只有非设备门禁全部通过，才能把计划标记为 DONE。仅设备的验证记录为 `PENDING — operator-owned`，并附可执行步骤与证据字段。
