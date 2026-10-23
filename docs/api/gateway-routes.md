# Gateway · 精确方法与路径白名单

> 文档性质：**唯一实施合同候选**。所有字段、API、状态、错误、鉴权由Core授权校验，严格遵循[API总约定](../06-api-design.md)。真实HTTP/集成测试NOT RUN。

## 1. 外部路由注册规则


**目标**：各阶段API必须以唯一公开Gateway入口注册明确的Method+Path；历史草稿不能取代现行[管理API分册](./p01-p03.md)、[额度治理分册](./p04-p06.md)、[运行管理分册](./p07-p12.md)。此处固定 Gateway 的**分阶段路径注册纪律**，防止本地AI为了省事使用 /api/v1/** 或 /** 裸通配而把未实现的P11财务报表、P7运行API、Core私有/internal过早暴露。这里是**边界清单，不取代各Phase逐接口DTO契约**。

## 按 Phase 扩容的唯一公开入口

| 自何Phase开放 | Gateway真正可路由到的URL资源集合 | 真实处理应用与权限 |
|---|---|---|
| P1 | /api/v1/bootstrap/initialize、/api/v1/bootstrap/recover-management-key；/api/v1/teams、/api/v1/teams/{teamId}；/api/v1/employees、/api/v1/employees/{employeeId}、/role-assignments/{roleCode}/{scopeType}/{scopeId}、/management-credentials 及 /{credentialId}；/api/v1/me/profile；/api/v1/admin/audit-events | 全部 Gateway→Core；bst_只在两个短时运维入口，其他只有mgt_并由Core当前RBAC+对象范围检查 |
| P2 | /api/v1/agents、/{agentId}、/{agentId}/keys、/{agentId}/keys/{keyId}、/{agentId}/keys/{keyId}/rotate、/{agentId}/employees、/{agentId}/employees/{employeeId}；/api/v1/employees/{employeeId}/runtime-credentials、/{credentialId}、/{credentialId}/rotate | Gateway→Core；仅PLATFORM_ADMIN可改变Agent、两类运行Key或EmployeeAgent ACL；不开放运行时验证到公网 |
| P3 | /api/v1/providers、/{providerId}；/api/v1/models、/{modelId}、/{modelId}/deployments、/{modelId}/points-prices、/{modelId}/points-prices/{revision}/publish；/api/v1/deployments/{deploymentId}、/{deploymentId}/purchase-prices、/{deploymentId}/purchase-prices/{revision}/publish、/{deploymentId}/credentials、/{deploymentId}/credentials/{credentialId}；/api/v1/agents/{agentId}/models、/{modelId} | Gateway→Core，均需管理身份/审核，真实Provider Secret永不回读 |
| P4 | /api/v1/quotas/policies、/{policyId}/publish；/api/v1/employees/{employeeId}/quotas；/api/v1/provider-budgets、/{currency}/{month}；/api/v1/finance-operations/{operationKey} | Gateway→Core；公司预算和收入不是自由查询/修改员工资金的公网服务 |
| P5 | /api/v1/teams/{teamId}/pool、/{teamId}/pool/{periodStart}、/{teamId}/grants、/{teamId}/grants/{grantId}/revoke；/api/v1/employees/me/grants | Gateway→Core；TeamManager只能管理自己的发行Team，Employee只能看本人 |
| P6 | /api/v1/governance/policies/drafts、/policies/active、/policies/{revisionId}/submit、/review、/publish；/agent-egress-approvals、/deployment-boundaries/{deploymentId}、/exceptions、/exceptions/{id}/review、/exceptions/{id}/revoke、/decisions | Gateway→Core；编辑与审批人不能相同，敏感命中摘要不返回原文 |
| P7 | **POST /v1/chat/completions**；**GET /v1/invocations/{publicInvocationId}**；/api/v1/capacity-pools（GET/POST）、/capacity-pools/{poolId}（GET/PATCH）、/capacity-pools/{poolId}/activate（POST）、/capacity-pools/{poolId}/block（POST）、/api/v1/deployments/{deploymentId}/capacity-pool/{poolId}（PUT） | Runtime Gateway处理HTTP/SSE，但所有身份/ACL/安全/资金授权请求Core内部RPC；不得把整个/v1/**路由到Core或Provider |
| P8 | /api/v1/models/{modelId}/routing-policies、/routing-policies/{revision}/publish；/api/v1/deployments/{deploymentId}/health | Gateway→Core，管理员可写策略，安全低敏观测可查 |
| P9 | /api/v1/agents/{agentId}/fallback-policies/{requestedModelId}；/api/v1/corporate-fallback-pools/{month}；/api/v1/employees/{employeeId}/fallback-weekly-caps/{weekStart}、/local-fallback-quotas/{weekStart} | Gateway→Core，配置管理；P7原POST Chat仅增加受控fallback_mode、绝不允许客户端选SupplierKey |
| P10 | /api/v1/emergencies（GET/POST）、/{requestId}/review；/api/v1/emergency-authorizations/{authorizationId}/revoke、/{authorizationId}/usage；/api/v1/corporate-emergency-pools/{month} | Gateway→Core；单独审批/申请范围，**不注册旧 /emergencies/{id}/approve** |
| P11 | /api/v1/finops/summary、/trends、/facts、/me/usage-summary、/pending-exposure、/outbox-status、/operating-expenses、/operating-expenses/{id}/approve、/operating-expenses/{id}/void、/rebuilds | **全部先Gateway→Core**，Core鉴mgt_和Team Scope。pending/outbox由Core读core_db，其他Core用专用svc_调用FinOps的受控私有API。FinOps不暴露公网端口 |
| P12 | GET /api/v1/operations/runtime-status、POST /admission-block、POST /readiness-probes、POST /recovery-review、GET /reconciliation-cases | Gateway→Core、管理员审计；不会提供强制清掉未知供应商费用的接口 |

**实现规则**：
- 本表使用了前缀继承省略写法（例如 P2 第一个资源为 /api/v1/agents，后面的 /{agentId}/keys 在此前缀下），落地为 Gateway RouteDefinition 时必须**展开为明确合法路径+HTTP method**，而不是拿本表的文字直接作为PathPredicate或把所有子路径 /** 放行。执行AI只能实现当前Phase已经存在的端点；新增一个API要同步 Gateway routes、Core Controller、授权测试和本清单。
- 除网关自己的受限 Actuator readiness/liveness 外，任何 /internal/**、URL编码变体、路径规范化后包含 traversal、/actuator/env 等都不对客户端开放。Gateway卸除伪造 X-AIGate-Service-* / X-Internal-* / X-Employee-Id / Forwarded 等，内部svc_只由受信HttpClient为自身RPC添加；运行时不能用mgt_取代agk_/empk_。
- P1就进行一次真正的网络双应用转发，其余Phase增加显式配置；Core内部 /internal/v1/ping（P1）、/runtime-identities/verify（P2）、/catalog/models/{modelCode}/eligibility（P3）、六类Invocation RPC（P7，见[内部RPC](./internal-rpc.md)）、FinOps私有source-manifest/source-event（P11）**不属于Gateway公开路由**，即使它们真实运行并返回200，公开请求也应404/403。
- Router不能无条件转发客户端 Authorization mgt_ 给FinOps；P11进入FinOps的是Core验证后的业务主体/TypedScope，原 mgt_仍只在 Gateway→Core上被验证。
- Gateway管理POST/PATCH/PUT/DELETE绝不自动业务重试。外部504可能意味着Core已COMMIT，只有同业务幂等ID的安全查询或有明确重复收据的无敏感重放才可继续；特别是首次管理Key不重新显示。
- 公开错误JSON按[API总则](../06-api-design.md)使用 {error:{code,message,requestId,retryable}}；内部RPC按[Canonical协议](../protocols/runtime-canonical.md)的P7 v1/P9 v2/P10 v3协商版本，不可永远锁定在v1。**P1两个Bootstrap端点使用bst_，不是管理mgt_的替代角色**。



## 2. 额外应放行的合法资源（仍按Phase）

- P6包含GET /api/v1/governance/policies/active和POST /api/v1/governance/exceptions/{requestId}/revoke。
- P7包含GET/POST /api/v1/capacity-pools、GET/PATCH /api/v1/capacity-pools/{id}、POST activate/block、PUT Deployment容量池绑定；绝不使用全路径裸通配。
- P10 GET/POST /api/v1/emergencies；P12 POST /api/v1/operations/readiness-probes。
- P11管理FinOps必须Gateway→Core→FinOps typedScope，不能Gateway直连FinOps。
- 未激活Phase方法、/internal/**、路径编码变体和伪造service header必须拒绝。
