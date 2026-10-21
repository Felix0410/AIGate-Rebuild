# 08 · Module Design

## 单体目录结构（目标，不是现存文件）

src/main/java/com/felix/aigate/
- bootstrap/ : Spring Boot 启动、全局配置和环境探针
- common/ : 错误码、时钟、金额/积分精度、审计元数据、统一 API 响应
- identity/ : Team/Employee/Agent、Key、角色与权限
- catalog/ : Provider、LogicalModel、Deployment、PriceVersion
- gateway/ : runtime Controller、Admission、Router、ProviderAdapter、SSE
- quota/ : Bucket、Grant、FundingPolicy、Reservation、Settlement、Ledger、Reconciliation
- governance/ : Policy、RuleMatcher、PolicyPublisher、DecisionAudit
- analytics/ : FinOps projection、DailyReport、ThresholdAlert、EventConsumer
- integration/ : 外部 Provider HTTP、RabbitMQ Publisher、Redis Lua 脚本
- observability/ : 指标、trace、告警挂钩

每个业务模块内部按 controller / dto / application-service / domain / repository 分层。只允许通过其公开 Facade 调用其他模块；**禁止跨模块直接 Mapper 改账本/安全规则表**。

## 依赖与执行流程

Gateway 接收 Agent+Employee 验证过的 SecurityPrincipal → identity.validatePermission → governance.checkBeforeDispatch → quota.reserve(invocationId,model,upperRisk) → gateway.routeAndCallProvider → quota.settleConfirmedUsage 或 markPendingReconciliation → event outbox。

Quota 的事务不跨 Provider I/O。跨模块只传不可变 requestId、employeeId、agentId、policyVersion、priceVersion 等业务上下文。不得把外部传来的 employeeId 当认证 principal。

FinOps 不能同步阻塞模型响应；只处理已结算事件和已成功 Grant 发放事件。消费事件发生异常时只影响衍生报表，不能回滚真实账本；安全拒绝和额度拒绝必须在 Provider 请求之前发生。

## Service / Repository 边界

Controller 仅做参数绑定、SecurityPrincipal 获取、响应转换。Application Service 组织事务和用例；Domain Service 执行费用/审批/调度规则；Mapper/Repository 只持久化对应领域的表。事务入口应放在拥有状态变更的 application service，避免 self-invocation 导致事务失效。

资金变更禁用“直接 set balance”，必须以业务命令（grant, reserve, settle, release, reconcile）和唯一业务键实现，伴随权威流水。时间计算统一注入 Clock 与配置的 company ZoneId，周一 00:00 不依赖服务器本地时区。

## Provider 适配

首版 OpenAiCompatibleAdapter 支持非流式和 SSE。通过 allowlisted HTTPS base URL 和受控路径构造请求，不接受任意 URL 请求转发；Provider Key 解密只在出站 HTTP 时短暂使用；禁止写日志。非 OpenAI 协议后续新增 Adapter，不修改 Quota 核心。

## 本地 AI 交付约束

每个工作包只编辑指定模块、迁移脚本、测试和需要的 pom/Compose。新增中间件/包依赖必须先有 docs/04 与 ADR 授权。本地 AI 不维护 docs 和 Phase 编号。发现方案不完整，汇报具体阻塞而非悄悄设计替代技术。
