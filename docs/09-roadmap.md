# 09 · Roadmap — 全部重做，Phase 0～12

**全新项目**：旧代码、旧 P1–P3 已完成标记全部失效；重新从 P0 设计和新 P1 验收。Phase 编号沿用之前已确认的 P4～P12 业务顺序，P1～P3 也全部重做。每个阶段包含 Business Goal、Scope、Main Flow、Architecture、Tasks、Acceptance Criteria、Knowledge Points；具体工作包见 10。

| Phase | Business Goal | Scope / 主流程 | 架构与关键知识 | 可验证的完成条件 |
|---|---|---|---|---|
| **P0 当前** | 完整设计 | 产品/需求/业务/领域/数据库/API/技术/模块/工作包/验收冻结 | 文档即合同，ADR | 全部 docs 完整、内部一致，用户签署设计后才编码 |
| P1 | 构建可运行单体 | Boot 项目、MySQL/Flyway、Team/Employee CRUD、Docker/CI | MVC、MyBatis、DI、事务、Testcontainers | REST CRUD/E2E、migration、CI 全绿 |
| P2 | 纳管模型和已有 Agent | Agent/Application、Key、Provider、逻辑 Model、Deployment、Adapter，同步代理 | 密钥保护、HTTP 网络、分层 | 正确 Key 正常代理，错误 Key/模型/端点拒绝 |
| P3 | 调用链可观测记录 | 可信元数据，状态/时长/异常，分页/保留 | 事务边界、索引、审计最小化 | 成功/失败/超时均记录，查询和清理测试 |
| P4 | 实际 Employee 双身份 | Employee Key、Employee×Agent/Agent×Model ACL、管理角色 | Spring Security 双身份 | 伪造 employeeId 不可越权，各类 Key 禁用/撤销生效 |
| P5 | 可核对员工积分结算 | 价格版本、Token、周额度、预留/结算、对账 | MySQL ACID、行锁、幂等、decimal | 并发不超扣、周重置幂等、未知用量待核对 |
| P6 | 团队资源发放 | Team Pool、申请审批、主动跨团队 Grant、过期 | 事务/状态机/唯一流水 | 成功发放扣池一次，跨团队归属与过期正确 |
| P7 | 有成本边界的流量治理 | 公司保底、限流/普通/紧急基础档、Redis 共享速率 | Redis+Lua、多实例/公平性 | 双实例共享限额、企业硬预算不可突破 |
| P8 | SSE + 多 Deployment 路由 | 同 Model 负载/权重/健康路由、stream、指标告警 | HTTP SSE、长连接/线程、Micrometer/Grafana | SSE 断连取消、正确待核对、路由压测/仪表盘 |
| P9 | 紧急任务保障 | 审批指定 Task/Employee/Agent、有限预算和优先资源 | 队列、信号量、调度策略 | 不越权/不透支、过期失效、拥塞公平 |
| P10 | 企业 AI 内容安全 | 数据分级、敏感规则、版本/发布、异常与元数据审计 | 规则匹配、快照一致性 | 不安全请求在出站前阻断，敏感日志无原文 |
| P11 | 企业 FinOps | 员工用量、Team 发放、Provider 费用三口径，趋势/告警 | Outbox、RabbitMQ、幂等聚合、读模型 | MQ 中断重放、重复消费不多计、三口径对账 |
| P12 | 生产可靠性与架构演进 | CI/容器、负载与故障、JFR、必要的微服务抽取 | JVM、分布式故障、RPC/ADR | 1vs2副本、可能的2～5服务对比，有可复现基准 |

## 每 Phase 提交条件

1. Business Goal/Scope/Main Flow 已在 10 中固定，本地 AI 不擅自改；
2. API+Schema+测试与本 Phase 一致；
3. 运行环境一键启动，至少覆盖 Happy Path 与资金/安全关键失败；
4. CI 和 Testcontainers 完整通过，记录确切测试命令与统计；
5. 总控完成 Code Review、架构检查、测试审查、技术债、progress、必要 ADR、知识和面试表达；
6. 用户**明确确认完成该 Phase**后才启动下一 Phase。

## 重要实施原则

整个业务设计先完整写好，但不在 P1 就落地 Redis/MQ/微服务等后期技术。P4 基础身份安全在 P10 内容规则之前实施；未进入 P10 前只允许用于受控开发演示，不能当作企业生产全功能上线。P11 由已经存在的 P5 账本构建衍生报表。P12 允许根据实测结论**不拆微服务**；五个业务领域只是候选。
