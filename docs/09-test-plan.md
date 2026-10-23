# 测试策略与验收计划

## 1. 分层

| 测试层 | 目的 | 工具/证据 |
|---|---|---|
| 纯单元 | Decimal/CAS、RuleMatcher、CanonicalDTO、状态机 | JUnit 5，明确边界/非法输入 |
| 数据库集成 | 事务锁、真实FK/CHECK/UNIQUE、Flyway升级/回滚 | MySQL8.4/Testcontainers，真实SQL和并发线程 |
| 双服务E2E | Gateway外部路由、Core认证和事务、禁路由负例 | 两独立Spring Boot进程、真实HTTP |
| Redis/Provider | 同slotLua、runId、租约/SSE/断线/ACK丢失 | 真实Redis + FakeProvider + 故障注入 |
| 消息/读模型 | Outbox confirm/return、RabbitMQ手动ACK/重放、FinOps Manifest | P11真实RabbitMQ+两DB |
| 多实例/故障 | Gateway×2/Core×2、Redis重启、MySQL恢复、P12全局BLOCK | P12演练及测量证据 |

## 2. 阶段门禁

P1真实双服务和V1六表；P2运行双Key；P3密文长度和价格指针；P4双账资金；P5 Grant；P6 Policy安全；P7 JSON/SSE Provider唯一出站与Redis租约；P8选路；P9/P10降级和紧急；P11真实MQ/FinOps；P12多副本恢复。**不能要求P7系统已运行才批准P1开发，也不能在P7交付时把Provider E2E延后。**

## 3. 完整案例目录

| 测试范围 | 详尽场景 |
|---|---|
| P1～P3 | [cases-p01-p03](./tests/cases-p01-p03.md) |
| P4～P7 | [cases-p04-p07](./tests/cases-p04-p07.md) |
| P8～P12 | [cases-p08-p12](./tests/cases-p08-p12.md) |
| 跨Phase新增审查回归 | [design-audit-regressions](./tests/design-audit-regressions.md) |

旧案例按规则归档，新的R-AUD和D52～D56属于实际阶段测试规格，全部NOT RUN。

## 4. 证据记录

每个case记录：caseId、Phase、Git SHA、JDK/Boot/MySQL/Redis/MQ具体镜像digest、执行命令、开始时间、passed/failed/skipped、网络ProviderHTTP发送次数、账务预留/已结/UNKNOWN、日志与trace的脱敏证明。真实尚未运行时仅标NOT_RUN或BLOCKED，不得填写0通过伪造测试结论。