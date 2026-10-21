# AIGate 文档目录

## 项目定位

AIGate 是企业内部 AI 模型统一接入与治理平台。现有 Agent 通过网关访问获准的模型。平台负责身份认证、权限、调用计量、员工积分、团队预算、限流、流式响应、安全策略和成本分析。

## 文档索引

- 00-overview.md：业务目标与边界。
- 01-requirements.md：完整功能清单。
- 02-business-rules.md：计费和访问控制规则。
- 03-architecture.md：总体技术架构。
- 04-technology.md：技术栈及选型依据。
- 05-domain-model.md：领域实体和不变量。
- 06-database-design.md：数据库结构和事务。
- 07-api-design.md：HTTP 接口和流式协议。
- 08-module-design.md：Java 模块划分。
- 09-roadmap.md：阶段路线及验收。
- 10-task-contracts.md：编码执行合同。
- 11-testing-operations.md：测试与运维。
- 12-progress.md：项目进度。
- 13-knowledge-interview.md：技术学习与面试资料。
- 14-local-ai-protocol.md：本地编码 AI 工作协议。
- adr/：架构决策记录。

## 开发原则

设计文档是权威来源。实施工作由本地编码 AI 按确定的工作包完成；它不自行改变产品需求或选择新技术。先实现最小可运行主流程，再逐步扩展并用测试验证。每个阶段完成后，经过检查才进入下一阶段。

目前所有文档描述的是新项目目标，并不代表功能已经实现。

## 新仓库迁移状态（2026-10-21）

- 当前新仓库 main 全部文件位于 docs/；旧仓库 `Felix0410/AIGate` 保留原样作归档。
- 已补充 **06a-billing-transactions.md**（资金预留/结算/故障时序）、**07a-contract-details.md**（运行时协议）、**10a-acceptance-matrix.md**（安全/资金/SSE/MQ 集成验收）、**15-implementation-defaults.md**（可执行技术默认），以及 **16-security-auth.md**（管理引导和双凭证安全合同）、**17-admin-api-contract.md**（管理 API 路径及 DTO）。后续增加文档均由总控管理。
- 当前为 **P0 完整设计草案，未正式冻结**。虽然业务领域和大多数实现约束已设计，P1 开发仍然 **NOT AUTHORIZED**：正式编码前需检查接口与数据约束一致性、把 P1 工作包拆成足够精细的单次执行任务并经用户批准。
- 本地 AI 不得因阅读到整个 P0～P12 全量蓝图而一次性实施所有 Phase。用户学习重点为理解业务、技术原理、架构演进和测试证据，代码可由本地 AI 快速实现。

### 索引补充

| 文档 | 内容 |
|---|---|
| 06a-billing-transactions.md | 关键账务事务、费用预留/结算和待核对 |
| 07a-contract-details.md | Provider Runtime DTO、SSE 与事件数据结构 |
| 10a-acceptance-matrix.md | 各 Phase 不变量与失败用例矩阵 |
| 15-implementation-defaults.md | 业务已确认事项以外的实施默认 |
| 16-security-auth.md | 管理身份、Agent+Employee 双身份及角色授权合同 |
| 17-admin-api-contract.md | 管理 API 路径、请求/响应 DTO 和权限 |
