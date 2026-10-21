# 03 · Architecture

## 当前唯一正式起步架构

**Java 21 / Spring Boot 4.1.x 模块化单体 + MySQL 8.4 权威数据库。** 一个 Maven 项目，一个应用进程，按业务领域分包。先不分五个 Spring Boot 服务。管理面与模型运行面区分安全配置和线程/并发资源，Provider 由独立 Adapter 转发。

数据流：

1. Agent + Employee 独立凭证认证。
2. Employee×Agent 与 Agent×Model 授权。
3. Governance 数据/Model 政策前置判定。
4. Quota 根据风险敞口和资金顺序在短 MySQL 事务中原子预留。
5. Gateway 选择同逻辑 Model 的健康 Deployment，与 Provider 通信（JSON/SSE）。
6. Billing 依据可信 usage 独立事务结算、差额释放、记录 Outbox；不可信 usage 进入待核对。
7. FinOps 消费结算后事件形成汇总和告警，不参与同步资金扣减。

## 五个候选业务边界

| 领域 | 责任、唯一数据归属 |
|---|---|
| Gateway Runtime | 请求编排、SSE、入站流控、路由、Provider I/O |
| Identity & Control | Team/Employee/Agent/Model/Provider 注册、Key、权限和管理配置 |
| Quota & Billing | 周余额、Team 池、授额、保底/紧急池、预留/结算、权威账本与 Outbox |
| Governance | 敏感数据分类、政策版本/发布、风险决策与最小审计 |
| Analytics & FinOps | 已确认事件的成本投影、趋势、异常与通知 |

**当前只是五个单体模块，不是五个已运行微服务。** Quota 的表只能由其领域内写，FinOps 不改员工余额。安全审计和消息中禁止默认记录原始 Prompt。

## 一致性与失败

资金行采用固定排序 SELECT FOR UPDATE 短事务；授额的 Team 扣池和 Grant 创建同事务；预留和结算不是一个覆盖 Provider 网络 I/O 的长事务。相同 invocationId/审批 grantId 建立唯一幂等约束。用量未知保留待核对资金风险，不能转为 0。Outbox 与账务同一事务，RabbitMQ 故障只积压待发布事件，消费方按 eventId 幂等。

SSE 流式数据由 Gateway 本地写出，不为每个 Token 访问远程 Quota/Policy；后续拆服务时使用可信策略版本快照和单请求级额度预留/结算。权限被撤销的失效时效必须明确，安全策略不能在失效时默认越权。

## 演进门槛

先在一个进程跑通完整业务 → 两份**同一单体副本**做共享限流与 SSE 压测 → 分析 CPU/内存/线程、数据库热点、网关与管理接口的不同资源特征 → 有证据才拆。

自然候选的拆分顺序为 Gateway vs Control、独立 Quota、Governance 和 Analytics；可能最终只需要 2～3 个服务。拆分必须说明当前限制、单体优化替代、吞吐与故障隔离收益、新增 RPC/数据一致性复杂度，签署 ADR 并通过故障演练。10k 员工不等于 10k 并发请求。

## 技术引入

Redis+Lua：多副本限流且确实存在共享状态需求后。RabbitMQ：已结算用量异步统计与通知，当 Outbox/定时任务不够时。Micrometer/Prometheus/Grafana：P8 压力和延迟。Spring Cloud/Nacos/WebFlux/ES/ClickHouse 不因“简历技术数”强制出现。详细见 04。
