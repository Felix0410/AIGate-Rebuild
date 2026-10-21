# 05 · Domain Model

## 领域聚合

Identity-Control：Team、Employee（主 Team）、ManagedAgent/Application（已有 Agent 1:1）、AgentApiKey、EmployeeCredential、EmployeeAgentPermission、AgentModelAllowlist、Provider、LogicalModel、ModelDeployment、两个不同的价格版本与 RoleAssignment。

Quota-Billing：WeeklyAgentFreeBucket（Employee×Agent×Week）、WeeklyEmployeeBaseBucket、TeamQuotaPool、TeamExtraGrant、GrantApproval、CorporateFallbackBudget、EmergencyAuthorization、Reservation、ReservationAllocation、Settlement、LedgerEntry、ReconciliationCase。

Gateway：Invocation、RoutingDecision、AdmissionDecision、UpstreamAttempt、SSESession（瞬态）。

Governance：SafetyPolicy、PolicyVersion、SafetyRule、ExceptionApproval、SafetyDecisionAudit（元数据）。

Analytics：SettledUsageEvent、TeamGrantExpenseEvent、DailyCostProjection、AnomalyAlert、InboxReceipt、OutboxEvent。

## 关键关系

Team 1:N Employee；Team 1:N Agent；Agent 1:N ApiKey；Employee 1:N EmployeeCredential；Employee N:M Agent（个人授权）；Agent N:M LogicalModel（白名单）；LogicalModel 1:N Deployment，Deployment N:1 Provider。

Employee+Agent+Week 唯一免费额度桶；Employee+Week 唯一基础额度桶。Team → TeamPool → Grant → Recipient Employee；同一 Employee 可以持有多个不同来源、不同有效期的 Grant。

Invocation 按 Agent、Employee、请求幂等键唯一，有 Reservation、多笔 Allocation、至多一次最终结算。额度锁定和释放需要独立短事务。

## 必须由数据库保护的不变量

1. 可用积分不能负数；Team 授额和 Team 池扣减必须同事务，grantId 唯一。
2. 周额度初始化幂等，不得因任务重跑重复赠送额度。
3. 多个资金来源的一个请求原子预留；中间失败不能只扣走部分余额。
4. 已经分配/使用的资金保留来源流水与生效价格版本，不随后续改价改变历史。
5. 同一幂等 Key 与相同 payload 不能二次发送收费 Provider 请求；Key 相同但 payload 不同返回冲突。
6. 真实模型使用归 Employee，跨 Team 补充额度的支出归 issuing Team；供应商成本以实际 Token 单独估算。
7. 不可确认的供应商用量转待核对；不得默认为 0 或销毁资金风险记录。

## 状态机

AgentKey：ACTIVE → REVOKED/EXPIRED。
GrantApproval：PENDING → APPROVED / REJECTED / CANCELLED；批准只可履行一次。
Invocation：CREATED → RESERVED → DISPATCHED → SETTLED / PENDING_RECONCILIATION；可证明未出站则 CANCELLED。
Governance Policy：DRAFT → PUBLISHED → RETIRED（历史不可覆盖）。
Outbox：PENDING → PUBLISHED；失败可重试。
FinOps Inbox：eventId 唯一，重复事件不重复聚合。

## 领域事件

GrantIssued（发放团队、员工、积分和时间）；InvocationSettled（Employee、Agent、Model、Deployment、Token 分类、价格版本、已结算积分与外部估算金额）；SecurityDenied（最小安全元数据）；ThresholdAlert（对象、阈值与时间）。消息不得带原始 Prompt、完整输出或明文凭证。

实体名称为**目标设计**，非已存在的物理表。表约束见 06。
