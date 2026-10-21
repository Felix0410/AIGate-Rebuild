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
