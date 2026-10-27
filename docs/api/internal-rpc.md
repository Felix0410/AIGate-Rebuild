# 内部受信RPC · Gateway↔Core↔FinOps

> 文档性质：**唯一实施合同候选**。所有字段、API、状态、错误、鉴权由Core授权校验，严格遵循[API总约定](../06-api-design.md)。P3目录检查与P7以后RPC分别按所属阶段验证，实际结果见[Progress](../10-progress.md)。

## P3 目录就绪检查

`GET /internal/v1/catalog/models/{modelCode}/eligibility`，禁止正文和任何 query。必须唯一携带 `X-AIGate-Service-Authorization: Bearer svc_...`、`Authorization: Bearer agk_...`、`X-Employee-Key: empk_...`；由 Core 重新验证 P2 当前双身份、员工/Agent/双方团队状态及 EmployeeAgent ACL，再检查当前 GRANTED AgentModel ACL。未认证401、缺任一ACL403、不存在Model404、Model停用409 INVALID_STATE、数据库/DNS依赖故障503；任何客户端自报身份或审批字段400，不认可 mgt_ 代替运行 Key。

200 精确 DTO：`{modelId,modelCode,agentId,employeeId,pointsPriceVersionId,eligible,deployments,checkedAt}`。deployments 每项仅 `{deploymentId,hostingMode,costModel,capabilityRevision,purchasePriceVersionId,currency}`，按 deploymentId ASC；checkedAt 为UTC微秒时间，缺当前 POINTS价时 pointsPriceVersionId=null。候选要求 Model/Provider/Deployment ACTIVE、有效当前凭证、有效匹配批准、当前端点解析安全、同父PUBLISHED价格；第三方还需当前采购币种价，SELF_HOSTED 的采购版本和currency为null。缺价格/凭证/批准或无合格部署时200 eligible=false、deployments=[]。绝不返回 endpoint、ProviderSecret、原审批文件、Adapter全文或管理角色。

eligible=true 只说明 P3 当前目录条件，不证明 P6 治理、P7资金/容量/Permit已满足。审批过期、Owner/ACL撤销、Provider停用、凭证吊销和DNS结果改变均影响下一次检查；P7的资金事务和真实连接前仍须复查。P3不访问未来表，不打开Provider连接。外部经Gateway访问该路径必须404。

## 1. P7六类RPC和一次性许可


这部分补齐 D16/D33 里只给方法名而未给**明确 JSON 的六个内网合同**。所有时间 UTC Instant ISO8601、DB ID为正整数、金额十进制字符串、NULL只表示未知或不适用；客户端 Header 中的 mgt_、TeamId、Role、ActorId 不得伪造传入 RPC 作为最终身份。生产 Gateway→Core 必须 HTTPS+受信服务身份 svc_gateway_core，真实员工/Agent双运行 Key 必须由 Core 独立认证；内部请求是面向服务的协议，不直接暴露给公网。

## POST /internal/v1/invocations/preflight

只做身份/两ACL/初步治理和业务请求登记；**没有财务预留/Provider HTTP**。Header：
- X-AIGate-Service-Authorization: Bearer svc_gateway_core
- Authorization: Bearer agk_xxx
- X-Employee-Key: empk_xxx
- Idempotency-Key: P7 限16～128 ASCII安全字符
- X-Request-Id: 链路诊断ID；不作为金融唯一键

Request V1（完整类型与不允许字段）：
~~~json
{
  "schemaVersion": 1,
  "model": "TEXT-Q4-STABLE",
  "stream": false,
  "messages": [
    {"role": "system", "content": "Keep the reply short."},
    {"role": "user", "content": "Hello"}
  ],
  "max_tokens": 256,
  "temperature": 0.5,
  "top_p": 1.0
}
~~~

Core 自己按P7纯文本/最多64条/64KiB/n=1/不支持tools的规则重新校验Body，按固定字段顺序、字符串内容不自动 trim、JSON数值归一化得到 canonicalRequestBytes，再使用 **Core 独占 HMAC Key**计算 HMAC-SHA256 保存在 invocation.payload_hmac_digest。**Gateway 不提交 HMAC RequestDigest，也不能获得 Core 的 HMAC Key**；原消息仅通过TLS受信RPC传给Core且不得写日志。P9起额外可选 fallback_mode=NONE|ALLOW_APPROVED；P10起才认可 X-AIGate-Emergency-Authorization Header（与真实Key和Task绑定），P7不得因为传入额外 serviceClass 就准许LOW/EMERGENCY。

Response 201（Core新登记Invocation）：
~~~json
{
  "schemaVersion": 1,
  "invocationId": "inv_8801",
  "executionState": "RECEIVED",
  "billingState": "NONE",
  "requestedModel": "TEXT-Q4-STABLE",
  "preflightPolicyRevisionId": 31,
  "queueDeadlineAt": "2026-10-22T09:03:00Z"
}
~~~
相同员工/Agent/幂等键+相同正文已存在时只提供原安全Invocation状态，**不新建出站机会**；同键不同digest→409。Gateway随后才去Redis创建WAITING票，失败时可安全取消无出站Invocation/留下可审状态。

## POST /internal/v1/invocations/{id}/dispatch-permits

Gateway 先获得真正Redis pool ACTIVE，并提供 lease owner/token/具体最终候选Deployment和原messages；Core不能用Redis ticket取代安全/资金决策。Request：
~~~json
{
  "schemaVersion": 1,
  "requestId": "r-881",
  "candidateDeploymentId": 17,
  "capacityPoolId": 8,
  "leaseOwnerId": "gateway-instance-2",
  "leaseToken": "opaque-redis-owner-token",
  "leaseExpiresAt": "2026-10-22T09:02:30Z",
  "routingRevision": 0,
  "messages": [
    {"role": "system", "content": "Keep the reply short."},
    {"role": "user", "content": "Hello"}
  ],
  "max_tokens": 256,
  "stream": false,
  "temperature": 0.5,
  "top_p": 1.0
}
~~~

Core 从数据库读取已登记requestedModel（请求方不传第二个随意model），并将该请求的stream、max_tokens、temperature、top_p、messages等固定 DTO 字段重新 canonicalize 并用Core独占HMAC Key计算摘要，与Preflight保存的 invocation.payload_hmac_digest 常量时间比较，**不同立即409 REQUEST_PAYLOAD_CHANGED**。Core 从 P7 自己的受限 Redis Client / Lua只读验证真实 lease 仍在对应Pool、owner、capacity与deadline（**不能只验证Body中的leaseExpiresAt签名或相信Gateway自报**）；重新检查最终Employee/Agent双Key与两ACL、当前Governance/可信数据等级、ProviderHost/Deployment、PriceVersion/current pointer、可信input+output最坏计价类别/安全上界，**在同一Core短MySQL事务**创建 P4 FinanceOperation/两预算Reservation/唯一Invocation绑定+DispatchPermit，并持久化policy/price/currency/真实团队快照。任何数据版本改变/扣费余额不足→全回滚，Provider0。

Response **201 只能首次有效签发且首次明确收到的Gateway使用**：
~~~json
{
  "schemaVersion": 1,
  "permitId": 9001,
  "attemptToken": "opaque-once-only-secret",
  "financeOperationId": 781,
  "invocationId": "inv_8801",
  "finalDeploymentId": 17,
  "capacityPoolId": 8,
  "pointsPriceVersionId": 31,
  "purchasePriceVersionId": 44,
  "supplierCurrency": "USD",
  "maxReservedPoints": "25.000000",
  "maxReservedSupplierAmount": "0.03000000",
  "permitState": "COMMITTED",
  "authorizedAt": "2026-10-22T09:02:01Z"
}
~~~
SELF_HOSTED 的 purchasePriceVersionId/supplierCurrency/maxReservedSupplierAmount均为 null，**不是虚构的0美元采购**。响应强制 Cache-Control:no-store；Core存 attemptToken的 HMAC摘要而不是完整明文。请求重试如果 Core已提交（包括ACK丢失）必须返回409 PERMIT_ALREADY_COMMITTED_NO_REISSUE、**绝不再次签发原或新attemptToken**。如果MySQL未能证明明确未提交，Gateway也不得猜测可以再次签发后重发。

## GET /internal/v1/invocations/{id}/dispatch-state

Header服务凭证、受信Invocation关联；Response200：
~~~json
{
  "schemaVersion": 1,
  "invocationId": "inv_8801",
  "executionState": "ACTIVE",
  "billingState": "RESERVED",
  "permitState": "COMMITTED",
  "permitId": 9001,
  "attemptConsumed": null,
  "observedAt": "2026-10-22T09:02:04Z"
}
~~~
attemptConsumed 为 true/false/null 三态，null表示Core无法证明网络实际发送情况；state接口**永不包含 attemptToken 或 ProviderKey**，不能作为重新派发权。客户端/恢复任务只能保守等待可靠事实或人工对账。

## GET /internal/v1/invocations/{id}/provider-credential

只允许已明确取得首次Permit的对应 Gateway，在安全链路额外带 X-AIGate-Attempt-Token: opaque-once-only-secret，Core验证其HMAC匹配真实唯一Permit并校验finalDeploymentId；未签 Permit、凭证撤销、未知Deployment或超时后均拒绝，不能让调用者指定另一Provider URL。

Response200（仅受信TLS服务器内存；绝不落 Redis/日志/公开DTO）：
~~~json
{
  "schemaVersion": 1,
  "deploymentId": 17,
  "credentialRevision": 3,
  "credentialKind": "BEARER",
  "headerName": "Authorization",
  "headerValue": "Bearer test-fake-provider-credential",
  "issuedForPermitId": 9001
}
~~~
有些 Provider 使用 API_KEY_HEADER，此时对应受批准的headerName；NONE 时 headerName/headerValue=null。不能把P3的ProviderCredential原始明文永久存入 Gateway 配置。**此 RPC 的安全重试只允许补取未执行发送所需的凭证，不等于重新运行Provider请求**；如果Sender发送状态不确定，禁止因Credential二次获取而再次发上游。

## POST /internal/v1/invocations/{id}/result

可信尝试/Provider证据/确定幂等收据；最初结果报告可经受信服务身份提交，不需要外部用户新的网络连接保持。Request（正常可信usage示例）：
~~~json
{
  "schemaVersion": 1,
  "reportOperationId": "result-8801-v1",
  "attemptToken": "opaque-once-only-secret",
  "evidenceType": "TRUSTED_PROVIDER_USAGE",
  "providerRequestId": "fake-provider-8801",
  "evidenceDigest": "hex-hmac-of-trusted-evidence",
  "executionState": "OUTPUT_COMPLETED",
  "usage": {"input_tokens": 300, "output_tokens": 120},
  "providerOutcome": "COMPLETED"
}
~~~
只有真实 Adapter 通过Provider允许的完整usage合同能把这种 evidence 标为 TRUSTED_PROVIDER_USAGE；不接受客户端自行上传usage或任意supplierCost；Core按Permit中冻结价格自己计算 POINTS与原币种结算。无usage/是否已上游接收不确定→ evidenceType UNKNOWN，usage=null，Core保持账务PENDING。PROVEN_NOT_SENT 必须有可信发送前证据（Gateway SIGKILL/HTTP timeout不能自动证明），才允许资金RELEASE。

Response 200：
~~~json
{
  "schemaVersion": 1,
  "invocationId": "inv_8801",
  "billingState": "SETTLED",
  "settlementRevision": 1,
  "pointsSettled": "12.000000",
  "supplierCurrency": "USD",
  "supplierAmountSettled": "0.01500000",
  "reportOperationId": "result-8801-v1"
}
~~~
ACK丢失时**只用同一个reportOperationId/attemptToken/evidenceDigest安全重报结果**，Core receipt UNIQUE使Ledger/Outbox/Account只结一次；同ID不同内容→409 REPORT_EVIDENCE_CONFLICT，绝不重复调用Provider。PENDING响应的 amounts可为null（未知非零）；OVERRUN必须显式状态和原币种风险，不能被报成合法结算。

## GET /internal/v1/invocations/{id}/view

Core只允许正确agk_+empk_关联原Invocation，响应公开所需最低元数据，不包含Secret、真实Provider URL、原Prompt或员工采购预算原始详情。Response200：
~~~json
{
  "schemaVersion": 1,
  "invocationId": "inv_8801",
  "requestedModel": "TEXT-Q4-STABLE",
  "actualModel": "TEXT-Q4-STABLE",
  "executionState": "OUTPUT_COMPLETED",
  "billingState": "PENDING_RECONCILIATION",
  "serviceClass": "NORMAL",
  "usedPoints": null,
  "supplierCost": null,
  "queuedAt": "2026-10-22T09:01:59Z",
  "dispatchedAt": "2026-10-22T09:02:01Z",
  "observedAt": "2026-10-22T09:02:11Z"
}
~~~
**业务事实**：executionState=OUTPUT_COMPLETED与billingState=PENDING_RECONCILIATION能同时成立；Core自身pending-exposure仍占用原积分/采购风险直到可信核查。响应给客户端的供应商费用如涉及公司采购敏感信息应只向管理端返回，普通调用者的实际GET /v1/invocations/{id} **默认省略 supplierCost 字段**；上述内部DTO字段不是让Gateway直接原封不动对外展示。

## 三次响应丢失不可互相混淆

| RPC故障位置 | Core可能已持久化什么 | Gateway唯一允许的后续操作 | 明确禁止 |
|---|---|---|---|
| Preflight ACK丢失 | Invocation RECEIVED/WAITING；财务NONE | 查询原Invocation安全状态、对相同请求做受控幂等查询 | 新Key再造同一业务 |
| Permit ACK丢失 | 可能已有 FinanceOperation+Reservation+唯一Permit | 查只读dispatch-state、标UNKNOWN并对账，**不重发Prompt** | 取回token、再派发一次Provider、切换另一Deployment |
| Result ACK丢失 | 可能已经SETTLED、Ledger+Outbox提交 | **重报相同结果收据**或只读查询 | 重试 Provider HTTP 或更换usage值 |

**实现验证门禁（设计已确定，但工程未测）**：Core持有HMAC-SHA256摘要为DB BINARY(32)，不需/不允许Gateway传所谓HEX HMAC、max消息体64KiB、上下文Token上界不是 input_tokens的任意客户端估算、Provider 支持的stream usage类别/证书轮换与 timeout；实际Spring Web MVC/RestClient 不能自动把201明确成功与RPC 504模糊处理混为一谈。P0待真实集成验证，六类接口目前是可执行的V1设计合同但尚未有Java类或真HTTP测试。


## 服务端摘要计算与协议版本优先级

- **废弃的 clientRequestDigest/payloadDigest 由Gateway计算的HMAC字段作废**。Preflight和dispatch-permits均不提交该字段；Core独占应用HMAC Key，对严格解析后的受控DTO deterministic canonicalization；保存BINARY(32)摘要。Dispatch再次对完整业务字段独立计算校验；Gateway无需、也不得持有Core HMAC密钥。
- schemaVersion=1：本节采用JSON字段 model、max_tokens、temperature、top_p；废弃的 apiVersion、modelCode、maxTokens、topP **不是并行兼容别名**。P9/P10合法新增fallback或Emergency字段时先发布schemaVersion升级和兼容测试，不能在旧Invocation上换规范化规则重算。
- 规范化只固定对象字段顺序、必填和缺省值、数值的明确十进制表示，**不对messages.content做trim、lowercase、Unicode变换**。允许的默认temperature=1.0、top_p=1.0在计算摘要前由DTO填充；可选数字显式null一律400，缺失才填默认；不接受额外未知字段、工具或混入的收费选项。
- Core存的摘要用独立服务器密钥的HMAC-SHA256，防止泄漏摘要后对短Prompt做离线字典猜测；不把HMAC/Provider Key/原Prompt写入日志、Redis、Outbox或报表。
- 管理Gateway代理P1 connectTimeout=2s/responseTimeout=10s；P7六RPC建议connectTimeout=2s/responseTimeout=5s（真实运行门禁需验证）。Permit五秒无201确定结果不是未提交证明：Gateway不得通过GET重新取attemptToken或新签上游许可。Core持金融MySQL锁时不调用Redis或Provider HTTP。
- 同一个result报告操作号的重试，要求完全同一Provider Evidence Digest/Body；只允许受信Gateway Provider Adapter提交费用证据，客户端不能指定货币结算金额。
- Gateway将内部安全错误映射到[API总则](../06-api-design.md)外部统一结构 {error:{code,message,requestId,retryable}}。HTTP200已提交的SSE错误不可能再改HTTP状态，只能发安全错误事件/断开和提供状态查询。



## 2. 版本与安全覆盖

- P7 v1 RPC为基础合同；P9增加SchemaV2/fallback_mode，P10增加SchemaV3/emergencyAuthorizationId/taskRef，新字段须与Core HMAC一起比对。
- Gateway不计算、更不持有Core请求HMAC Key；旧Key轮换后Core按Invocation存储KeyId+SchemaVersion重验。
- 提交Permit后即使RPC ACK丢失，Gateway也仅查dispatch-state，不重发Provider或重新签发AttemptToken。
- FinOps P11的source-manifest、source-events和typedScope都是内网受信调用，FinOps不接受原mgt_。
