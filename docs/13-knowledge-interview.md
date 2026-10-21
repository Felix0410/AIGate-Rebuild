# 13 · Knowledge Map and Interview

用户学习目标不是亲手写完每行代码，而是**可以解释每个关键技术为何存在、如何执行、有什么代价和怎么验证**。本地 AI 实现代码，总控进行知识讲解与 Code Review。

| Phase | 重点技术 | 面试问题 |
|---|---|---|
| P1 | Maven、Spring MVC/DI、MyBatis、Flyway、Testcontainers | 如何从零构建可测试的 Java 项目？ |
| P2 | Spring Security、HTTP、Provider Adapter、密钥保护 | 如何管理外部 Agent 与供应商密钥？ |
| P3 | SQL 索引、分页、日志/状态、事务边界 | 如何记录成功、超时、失败模型请求？ |
| P4 | 双身份认证、RBAC、FilterChain、Principal | 有 Agent Key 为什么仍需真实 Employee Credential？ |
| P5 | ACID、InnoDB 行锁、幂等、DECIMAL、并发 | 两个并发请求如何确保不重复扣分、不超扣？ |
| P6 | 状态机、事务、grant idempotency | 跨团队发放与消费如何各自计账？ |
| P7 | Redis/Lua、分布式限流、公平性 | 两个 Gateway 实例为什么不能用 JVM 本地限流？ |
| P8 | HTTP SSE、Servlet async、负载路由、Micrometer | SSE 断线如何取消上游并保持正确账务？ |
| P9 | 优先级/并发信号量、时间窗预算 | 紧急任务如何在有限资源内获得优先级？ |
| P10 | 内容规则、版本化发布、缓存失效 | 有模型使用权限为何不能发送任意敏感数据？ |
| P11 | RabbitMQ、Outbox、ACK、幂等、最终一致 | MQ 故障为什么不能改变权威积分结果？ |
| P12 | JVM/JFR、压测、SLO、RPC、故障隔离 | 万名员工场景什么时候才值得拆微服务？ |

教学顺序固定：**业务背景 → 当前问题 → 技术是什么 → 为什么出现 → 原理 → 执行流程 → 替代方案 → AIGate 使用 → 阅读代码**。对比 MySQL vs Redis 余额、RabbitMQ vs Kafka、Spring MVC SSE vs WebFlux、单体双副本 vs 拆服务。

每 Phase 形成一个口头表达：业务目标、旧方案局限、新方案、收益、代价、失败策略和可复现测试数字。**尚未真实部署或压测的内容不能写成已实施性能指标**。保留 CI 测试数、负载环境、p95/p99、有效/失败/未知用量样本作为证据。
