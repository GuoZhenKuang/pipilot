# PiPilot 文档

本目录包含 PiPilot 应用与 Bridge 的面向维护者/使用者的文档。

## 文档索引

### 维护中的文档

- [产品路线图](roadmap.md)
- [产品总览、演示与截图（README）](../README.md)
- [架构总览（Mermaid 图）](architecture.md)
- [架构决策记录（ADR）](adr/README.md)（历史决策档案，保留英文）
- [代码库导读](codebase.md)
- [自定义扩展](extensions.md)
- [Bridge 协议参考](bridge-protocol.md)
- [测试指南](testing.md)
- [依赖与工具链矩阵](dependency-matrix.md)（说明已译，表格为验证记录原文）
- [发布验证](release.md)
- [引导与故障恢复](onboarding.md)

### 历史档案（记录接管前上游阶段的验收/调研，可能描述已被取代的行为）

- [性能基线](perf-baseline.md)
- [历史最终验收报告](final-acceptance.md)
- [复兴验收清单](revival-acceptance.md)
- [Pi RPC 兼容性记录](pi-rpc-compatibility.md)
- [Pi 上游能力评估](pi-upstream-opportunities.md)
- [历史任务清单](priority-task-list.md)

## 说明

- 上面「维护中的文档」为当前有效的文档。
- `docs/ai/` 与 `docs/spikes/` 存放历史规划、进度与调研产物，可能描述已被取代的行为，不是当前的实现指引。
- 日常开发与维护建议按以下顺序阅读：
  1. [产品总览（README）](../README.md)
  2. [架构总览](architecture.md)
  3. [代码库导读](codebase.md)
  4. [Bridge 协议参考](bridge-protocol.md)
  5. [测试指南](testing.md)
