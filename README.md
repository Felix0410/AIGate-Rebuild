# AIGate-Rebuild

企业统一 AI 调用与治理网关（Java 21 / Spring Boot）。项目以 P0～P12 分阶段开展，覆盖员工与 Agent 双身份、模型和供应商目录、周积分与企业采购预算、数据治理、JSON/SSE 调用、Redis 容量准入、FinOps 成本分析及多实例恢复。

**当前状态：P0 详细设计候选；P1～P12 尚未开始实现，实际 MySQL、Gateway、Redis、MQ 等兼容测试尚未运行。**

## 开发文档入口

[进入完整技术与开发文档](./docs/README.md)

文档已按产品、需求、架构、领域、数据库版本、API、协议、Phase、测试、ADR 分册组织。全部 62 张表的规范建表 SQL 设计位于 `docs/database/`。每个业务 Phase 的执行依据位于 `docs/phases/`。

**分工**：设计和正式文档由项目总控维护；本地 AI 负责用户批准后的具体代码、迁移、测试及运行证据。未经确认不进入下一Phase。
