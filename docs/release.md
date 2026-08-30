# 发布验证

PiPilot 的签名凭据不入库。现在仓库提供两种发布方式：

1. **正式发布（推荐）**：推送 `v*` 标签（如 `v1.0.0`），GitHub Actions 会自动跑完整门禁、用配置在仓库 Secrets 中的密钥签名，并把 `pipilot-v<版本>.apk` 发布到 GitHub Releases，发布说明自动取自 `CHANGELOG.md` 对应小节。
2. **本地构建（未签名）**：不带凭据时 release 构建保持未签名/默认配置，仅用于静态验证。

```bash
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
```

计划 012 发布要点：共享引用是持久的、由 Bridge 管理的状态，不等于授权。请备份 `BRIDGE_STATE_DIR`；状态重置会使引用失效。`BRIDGE_SHARE_ORIGIN` 可选，必须按严格的已配置源审查。未申请 Android App Links 验证；自定义 scheme + 自托管落地页是受支持的路径。真机证据仍为 **PENDING — operator-owned**。

计划 013 发布要点：会话控制台跨主机缓存优先，索引刷新最多两台并发。置顶/隐藏状态只包含本地稳定键与展示密度；删除主机会清空其本地范围，不可用的已保存会话保持为可删除/可重试的占位。选中的工作区上下文按主机隔离，普通卡片与搜索不含完整 cwd 与会话路径。快捷回复为纯文本，复用既有控制器锁与期望活动键的投递守卫，绝不切走其他活动运行，发送后也不自动跳转。真机证据仍为 **PENDING — operator-owned**。

预期产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`（已配置凭据时为签名包，否则为未签名包）

对外分发前：

1. 阅读 `docs/dependency-matrix.md` 与 Pi 兼容策略。
2. 在操作者自有设备上完成 `docs/revival-acceptance.md`。
3. 确认签名密钥只存在于本地受保护目录或 CI Secrets。
4. 校验签名 APK 并留存校验和、版本号、提交与验收证据。
5. 绝不提交 keystore、口令、服务凭据或生成的签名配置。
