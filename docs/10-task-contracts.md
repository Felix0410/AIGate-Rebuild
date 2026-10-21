# 10 · Task Contracts（本地 AI 严格执行）

> 这是总任务目录，不是同时开工许可证。每次总控只授权 **一个 Phase 的一个工作包**。本地 AI 根据 01～09 和本文件实施，不自行选择表、API、架构或新依赖。遇到文档冲突必须回报具体文件与字段。

## P1 基础设施与组织

T01 新建 Java21 + Spring Boot4.1 Maven 单模块，MVC/Security/MyBatis-Plus/Flyway/MySQL/Testcontainers 基础配置，Docker Compose MySQL 和最小 CI。
T02 建 Team、Employee 表与 Flyway V1；CRUD、禁用与员工主 Team 校验；API 与 07 对齐。
T03 集成测试/安全基本权限/输入边界/迁移重建，一次性提交。**验收**：干净电脑 Docker+Maven 可运行，CI 全绿。

## P2 模型注册与代理

T01 Agent/Application + 单次可见高熵 Agent Key 摘要存储、撤销；Provider/Model/Deployment、加密凭证、白名单基础 schema。
T02 OpenAICompatible Provider Adapter + RestClient，同步 chat completion；受控 provider URL、超时、错误映射。
T03 E2E fake Provider：有效 Key 可代理、无 Key 拒绝、禁用 Deployment 拒绝、不会泄漏 Provider 凭证。

## P3 调用记录

T01 Invocation 状态/耗时/agentId/模型/部署/traceId 元数据，成功/失败/超时记录，原文不默认存库。
T02 管理分页过滤、保留策略/删除分批、索引与集成测试。记录失败不得冒充 P5 权威计费。

## P4 双身份与模型权限

T01 员工 Key 高熵签发/撤销和独立 SecurityPrincipal；Agent Key+Employee Key 两者必备。
T02 Employee-Agent 授权与 Agent-Model 白名单校验，权限错误早于 Provider 出站；管理员权限与测试。
T03 E2E 伪造 employeeId、跨 Agent/Model、过期 Key、角色越权、并发撤销。此阶段结束前只用于无收费/测试额度模拟。

## P5 权威积分

T01 双价格版本、Token 分类和真实 usage 解析；周额度桶及周一 00:00 周期键，配置化时区。
T02 账本/Reservation/Allocation/Settlement/未知用量 Reconciliation 数据表与 Service；锁固定顺序，DECIMAL 精度，单事务预留。
T03 Provider 回包结算、差额释放、失败分类、幂等拒绝重复出站；并发扣费和时区边界 E2E。用户用量未知先挂账而非编 0。

## P6 Team Grant

T01 Team 池、审批申请与主动发放，批准同事务扣 Team 和创建 Grant + ledger，跨团队合规。
T02 Grant 过期/优先消费、重复审批幂等、团队内部发放支出记录与安全权限；冲突及并发测试。

## P7 保底与限流

T01 普通积分用尽自动切安全保底 Model，企业总保底与员工每周保底双硬上限；禁外部付费透支。
T02 JVM 单实例限流基线 + Docker 两个网关实例验证共享问题；真实需要时引入 Redis/Lua，并实现三档有界速率/并发/排队；Redis 故障行为测试。
T03 受控自部署基础 Model 存在时的独立限额路径，若不存在正确拒绝；安全不可绕过。

## P8 SSE 与模型路由

T01 模型多 Deployment 健康、权重/延迟/负载/成本选择与失败保护。
T02 MVC SSE 透传与取消，上游最终用量、未知用量对账、连接资源限额。
T03 Actuator/Micrometer/Prometheus/Grafana：活跃 SSE、拒绝、延迟/错误、Deployment 压力，Provider mock 压测。

## P9 紧急任务

T01 指定 Employee/Agent/Task 的审批状态与有限池。
T02 有效期、优先处理、可观测性及安全上限；并发与资金验证。

## P10 Governance

T01 安全数据分类与规则版本、管理员发布、审计表。
T02 入站 Prompt 出站前基础敏感数据检测/阻断及合规模型政策，元数据审计，不存原始敏感文本。
T03 政策版本切换/撤销、误报/受控例外与安全 E2E。

## P11 FinOps / RabbitMQ

T01 从已确认结算与成功 Grant 构建三口径日报/趋势，先 MySQL 聚合验证对账。
T02 账务事务 Outbox→RabbitMQ 发布确认→消费者 ACK/幂等 Inbox/重试/DLQ，断 Broker 仍正确记账、补发无重复。
T03 部门/Agent/Model/Deployment 成本报告、费用阈值告警、单位与币种区分、回放重建测试。

## P12 生产化/条件式拆分

T01 Docker/CI/回归、关键指标与告警、性能基线、JFR、SSE 高连接压测和多实例 MySQL 并发验收。
T02 比较双副本单体与候选 Gateway/Control/Quota/Governance/FinOps 的伸缩和故障隔离收益；有 ADR 才实施拆分。
T03 RPC/异常/凭证传播、数据归属/故障演练、面试/压测报告、技术债关闭或记录。

## 每包交付模板

变更文件清单、实现的业务主流程、接口/表/约束、测试命令与通过数量、风险与遗留问题、Git commit；不得回复“已经完成”但不给测试证据。总控负责 docs/ 的更新与任务重排。
