# 11 · Testing and Operations

## 测试金字塔

单元测试：价格换算与舍入、周起点时区、消费顺序、Grant 到期、权限策略、路由策略、异常状态机。
MySQL/Testcontainers 集成测试：Flyway 从空库构建、原子预留/结算、两请求争同一余额、同一 grantId 重放、死锁重试策略、唯一业务键和索引查询。
HTTP/E2E：使用可控 Fake Provider 记录真实到达请求数；有效双身份成功、权限拒绝/资金拒绝/安全拒绝均必须 **0 次 Provider 出站**；正常 JSON、SSE 分片、断连与断线待核对。
RabbitMQ 测试：发布前应用崩溃、Publisher Confirm 失败、消费者重复 ACK 之前崩溃、DLQ 和回放；账务不重复扣，聚合不重复增加。
Redis 测试：两个网关实例共享速率配额，Lua 原子操作，Redis 不可用时按明确策略 fail closed 或有界服务降级；绝不影响权威 MySQL 余额。
权限测试：撤销员工 Key、Agent Key、禁用员工、白名单移除、敏感数据合规拦截，不泄漏密钥与 Prompt。

## CI

GitHub Actions（P1 开始）：JDK21 Maven Wrapper、格式/编译、单测、Testcontainers MySQL 集成测试、迁移验证、依赖漏洞及基础静态检查；提交前本地同命令运行。CI 结果须记录 commit、测试数量、通过/失败/跳过，而不是只称“能跑”。

## 本地部署

Docker Compose 按阶段演进：P1 MySQL + app；P7 Redis + 双副本模拟；P8 Prometheus/Grafana；P11 RabbitMQ + Worker。秘钥来自不入库的环境变量/开发本地 secret 文件（Git 忽略），所有示例凭证不可部署至真实供应商。README 的启动命令由 P1 本地 AI 实际创建后更新为代码旁运行文档；docs 的维护仍由总控完成。

## 可观测指标

AIGate 入口 QPS、拒绝理由、inflight、SSE open streams、first-token latency、end-to-end p50/p95/p99、Provider 模型/Deployment 故障/超时和使用成本、Quota reservation/unknown backlog、Grant 发行金额、Outbox lag、RabbitMQ 消费积压。指标标签不可用未限基数的 employeeId/requestId；可用 traceId 关联日志但不能作为 Metrics 标签。

## 压测与可靠性

对比 1 实例、2 实例模块化单体和可选微服务部署；明确客户端负载模型、真实连接数量、请求长度/Token、Provider mock 延迟、硬件/CPU/内存、数据库连接池和索引。用可控数据证明响应延迟、资源占用、资金正确性和故障隔离收益，不能宣称凭空支撑“万人员工并发”。

故障演练：Provider 超时、部分流断开、重复消费、Redis 失联、RabbitMQ 停机、DB 锁竞争与慢 SQL、策略过期/撤销、应用重启后 Outbox 重发。确认每种场景真实权威账本与安全不变量。

## Phase Closeout 必交证据

功能检查、代码 Review、测试/日志、架构边界评审、技术债 ID、progress、architecture/ADR（必要时）、核心知识与可独立解释的面试回答。总控批准后才推进下一个 Phase。
