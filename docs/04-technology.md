# 04 · Technology Strategy

## 固定基础栈

Java 21、Spring Boot 4.1.x、Spring MVC、Spring Security、Validation、MyBatis-Plus（选 Boot4 兼容版本并在 P1 验证）、MySQL 8.4、Flyway、Maven、JUnit5、Testcontainers。HTTP Provider Adapter 用 RestClient；流式先用 MVC Servlet async/SseEmitter。统一 JSON 错误码、结构化日志、Spring Boot Actuator。

| 技术 | 是什么 / 解决什么 | 不用它 / 替代方案 | 选择理由 / 引入复杂度 | 阶段 |
|---|---|---|---|---|
| Spring Security | 身份链和方法/端点授权，保证 Employee+Agent 双认证 | 自写 Filter 易遗漏；生产 OIDC 需企业身份服务 | 与 Boot 集成；双凭证/角色细节需充分测试 | P1/P4 |
| MySQL 事务 | 原子 Team 授额、积分预留/结算 | Redis 余额不能替代权威 ACID；NoSQL 代价更大 | 单账本 + 行锁 + 幂等；需要处理索引/死锁/热点 | P1/P5 |
| Redis+Lua | 多实例共享速率/并发状态并原子更新 | 单实例 JVM 限流或外部网关共享规则 | 适合 P7 全局限流；带来 Redis 宕机/缓存/脚本维护 | P7 |
| RabbitMQ | Exchange/Queue/ACK 消息投递 | 同步通知或 Outbox 轮询 | 先选为**设计默认**；匹配审批通知和 FinOps 异步统计；会有重试/DLQ/幂等/积压 | P11，P6 有实需可提前 |
| Kafka | 可保留/重放的高吞吐事件日志 | RabbitMQ + MySQL 原始权威流水可重建报表 | 暂不强制，若大量多消费者历史重算出现再比较 | 条件 |
| SSE | HTTP 持续推送模型增量消息 | 同步 JSON 简单但响应慢；WebFlux 非阻塞另需学习 | 先 MVC SseEmitter；长连接、断开、结算复杂 | P8 |
| Micrometer/Prometheus/Grafana | 指标、时间序列和仪表盘 | 仅看日志难分析 p99/模型压力 | 标签基数、抓取与告警维护 | P8（Actuator P1） |
| OpenTelemetry | 跨服务追踪与上下文 | 单体 traceId 日志可先满足 | 真拆服务时值得加入，增加采样与传播复杂性 | P12 |
| Docker/Compose | 环境复制与故障演练 | 各人本地装数据库难重复 | 早用，配置和 secrets 需管理 | P1 |
| GitHub Actions | 自动化构建、集成测试 | 手工测试不稳定 | 保证本地 AI 快速产码也能持续回归 | P1 |
| Spring Cloud | 服务调用/负载/故障治理组件 | 直连 HTTP 与 Compose 可能足够 | 只有服务拆分和运维规模提出真实问题时采用 | P12 条件 |
| Elasticsearch/ClickHouse | 搜索/分析专用存储 | 现有 MySQL 过滤/聚合可能已足够 | 不为简历强加；需真实性能数据支持 | 暂不采用 |

## RabbitMQ vs Kafka vs RocketMQ

当前消息以团队通知、消费完成后的统计事件、异常告警和失败重试为主，**RabbitMQ 是本轮架构默认**。当确有高吞吐、多个消费者持久重放大量事件时再评估 Kafka；RocketMQ 需验证与 Spring Boot4.1 的 Starter 兼容。不能为了技术数量同时安装三种 MQ。

RabbitMQ 引入要同时完成：MySQL 同事务 Outbox、Publisher Confirm、持久化队列、消费者 ACK、唯一 eventId 幂等、受控重试/死信、MQ 停机后的补发及数据重建测试。“MQ 保证财务 exactly once”是不成立的。

## 新技术评审七问

每次引入必须在 ADR 中答：是什么、解决哪个真实问题、当前为什么需要、不用会怎样、可选方案、为何选择、附加复杂度。默认先最小可运行，再验证真实瓶颈。

**这些是目标选型，不是当前已安装依赖。** P12 五服务只是候选，不可默认为必交付五进程。
