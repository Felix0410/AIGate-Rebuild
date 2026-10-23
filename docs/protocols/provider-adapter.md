# Provider Adapter、两种价格与可信Usage

> 规范等级：P0 详细协议设计候选；按对应Phase首次实现并执行实际测试。本页不是运行日志。

## 1. 目标与适用阶段

P7首版只接受受控纯文本Chat Completions的真实两类input/output价，AdapterCompatibility持久化在V3 model_deployment。未经证实的Tokenizer/Provider强制max_tokens/额外计费类别一律422 BILLING_BOUND_UNVERIFIABLE，出站次数0。cached_tokens属于prompt_tokens子集不加总；已可能发送但用量未知保留PENDING，不把HTTP200当零成本。

## 2. 参与者与执行流程

具体参与者遵守[架构边界](../03-architecture.md)、[内部RPC](../api/internal-rpc.md)以及Core唯一Permit/事务约束，客户端不提供权威费用、治理结果或真实ProviderSecret。

## 3. 规范字段、状态与失败恢复合同
**Business Goal**：调用一个获准的 Chat Completions 文本模型，并在外部供应商可能真实收费的前提下保证：没有足额风险上界就绝不派发、只有可信证据才结算、未知用量不按零消费处理。依赖 P2双身份、P3双价格、P4/P5资金、P6治理、P7 Permit；本设计**不增加技术组件/服务/表**。

### 3.1 受支持协议与真实 Deployment 门禁

P7 首版只实现 `OPENAI_CHAT_TEXT_V1` 适配类型，支持 `POST /v1/chat/completions` 的纯文本 `system/user/assistant` 消息、`n=1`、固定受控 `max_tokens/temperature/top_p/stream`。只接受模型服务端通过安全审批的 Endpoint、remote_model_name 和鉴权形式（BEARER/API_KEY_HEADER/NONE限已批准SELF_HOSTED）。**不是**兼容任意声称OpenAI兼容的收费Provider：每个Deployment的协议、tokenizer、计费类型、usage质量、输入输出硬上界，都需以真实非收费/可控测试固定为 `AdapterCompatibilityV1`，该对象是受信运维配置和审批证据，不从客户端HTTP传入。

`AdapterCompatibilityV1` 的固定字段/取值：`{deploymentId,adapterKind:"OPENAI_CHAT_TEXT_V1",remoteModelIdentity,tokenizerId,tokenizerRevision,inputBoundMethod:"VERIFIED_TOKENIZER"|"PROVIDER_ENFORCED_BOUND",maxInputTokens,maxOutputTokens,maxTotalTokens,supportsJson,supportsStreaming,supportsFinalStreamUsage,usageEvidenceMode:"VERIFIED_UPSTREAM_USAGE"|"VERIFIED_SELF_HOSTED_USAGE",billingCategories:["INPUT_TOKENS","OUTPUT_TOKENS"],cachedInputMode:"SAME_INPUT_RATE"|"NO_CACHE",extraChargesPossible:false,providerSupportsIdempotency:false,compatibilityRevision,verifiedAt,evidenceRef}`。所有字段必须明确、不能从未知/缺失推断安全；如果Adapter不能证明usage关系或费用类别，上线 `Deployment ACTIVE` 不构成可计费许可。Provider没有真实服务端去重时`providerSupportsIdempotency=false`，AIGate不得用它承诺外部Exactly Once。

**配置唯一持久化**：P3 Core V3 `model_deployment` 规范DDL现新增 `adapter_compatibility_json JSON NOT NULL` 和 `adapter_evidence_ref VARCHAR(256) NOT NULL` 两列（已在上方V3 CREATE TABLE修改，不增表）；P3 `POST /api/v1/models/{modelId}/deployments` 必须提交封闭且已审查的 `adapterCompatibility` 与 `adapterEvidenceRef`，创建DRAFT后Core保留配置全文和审计ref，Gateway只能通过Core受信Eligibility/Permit/Adapter配置响应获取**对应不可更改的Approved Revision**，不能将Gateway本地YAML或任意客户端Header当事实。Compatibility当前版本与部署 `capability_revision` 严格一致；需要改变计费类别/上限/Token模板/Protocol/真实Endpoint时新建Deployment和审批，不能UPDATE旧ACTIVE行。P3创建DRAFT时无Provider实体凭证可以先存在，但若缺真实兼容证据永不可ACTIVE/收费。配置可引用非秘密证据，不存Provider Secret/Prompt。P3 的 LogicalModel/Deployment/Provider身份版本与上述配置必须一致。对于多个不同端点的同逻辑模型，公开调用只显示原 `modelCode`，P7 ActualDeployment在 Permit 冻结；RemoteModel对应身份、能力、地域/SSRF审批不得由Provider HTTP 302自行更换。禁用自动重定向、客户端自指定 baseUrl、HTTP proxy环境变量绕过；每次连接解析并检查所有IPv4/IPv6最终目的地址落入批准范围。HTTP状态不等于 usage证据。

### 3.2 输入上界、输出上界及预算冻结

1. Preflight解析**原始**messages、严格只接受文本、1～64条、64KiB以内；Core和Gateway使用一致SchemaVersion/CanonicalDTO。输入Token上界 \(T_{in,max}\) 必须由和remoteModel/模板版本匹配且已实际验证的tokenizer，对本次**完整被Provider实际计费的请求表示**计算（包含role/模板/协议额外token）并保守取上界；或者由可核查的上游协议明确强制输入计费上界。不能用字符数、UTF8字节数/4或客户端token数猜测。
2. 输出上界 \(T_{out,max}\) 取客户端明确 `max_tokens`，并由Gateway/Adapter确认真实Provider会强制执行这个字段；不得仅因本地断开SSE就假定上游已停止计费。如果提供商允许不受控后处理、隐式推理Token、多次tool calls或其它额外收费，首版不支持付费路径并以422 `BILLING_BOUND_UNVERIFIABLE`拒绝。
3. 当前 V3 两列 `point_per_1k_input/output`、`amount_per_1k_input/output` 只允许 INPUT/OUTPUT 两类计价：对可收费第三方 \(P_{reserve}=\operatorname{ceil}_{6}((T_{in,max} p_{in}+T_{out,max} p_{out})/1000)\)，\(C_{reserve}=\operatorname{ceil}_{8}((T_{in,max} c_{in}+T_{out,max} c_{out})/1000)\)。模型自身 POINTS价格和真实Deployment外币采购价分别冻结；不做汇率换算。SELF_HOSTED只有POINTS/本地授权Token，绝不凭空增加货币ProviderSpend。这里的ceil上界预留与实际usage的HALF_UP入账是两种操作。
4. `cached_tokens` 如存在只作为 `prompt_tokens` 的**子集**，不再次加总。只有已经被证明按与普通input相同单价计费的部署可用 `cachedInputMode=SAME_INPUT_RATE`；不支持有折扣/另收费缓存类别的精确结算，不允许假定折扣为零或按错价入真实财务。其它tool、reasoning、image/audio、minimum-charge、request-fee、batch差异及Provider收费货币变动一律先不启用该Deployment（不是在P7偷偷增加V3价格列）。
5. 最终Core在相同MySQL资金事务冻结 `logicalModelId,actualDeploymentId,pointsPriceVersionId,purchasePriceVersionId,currency,maxInputTokens,maxOutputTokens,maxPoints,maxCurrencyAmount,adapterCompatibilityRevision` 的业务语义。当前V7没有独立compatibility_revision列：首版用 `model_deployment.capability_revision` 作为**已审核适配证据版本标识**（同时指向受信配置登记/审计），不能在原ACTIVE部署上无版本改变其真实计费方式；不向执行AI授权新增表。没有证据或预算时Provider发送次数必须为0。

### 3.3 JSON和SSE字段的可信来源

**非流式JSON可接受 usage**：仅来自本次已通过TLS/endpoint/model身份验证的合法Provider响应，必须包含整数 `prompt_tokens>=0,completion_tokens>=0,total_tokens=prompt+completion`；可选cached_tokens满足 \(0\le cached\le prompt\)。受控Provider requestId/response model与冻结部署兼容、usage证据与本次唯一attempt匹配；多choices、非法整数、缺字段、计费类别未知、不同真实RemoteModel或额外未批准billing字段，都不视为可信 usage。若有额外计费可能但未在V3价列覆盖，一律不能宣称准确采购成本。

**SSE**：支持的上游由Adapter请求 `stream_options.include_usage=true`，解析最后 `choices=[]` 的受信usage帧和 `data: [DONE]`；允许文本完成但usage尾帧丢失，向客户端可正常结束已有可见文本，**Core记PENDING_RECONCILIATION**。对于明确不支持尾usage但有经过签约验证的独立上游结算证据接口，首版P7**不自动认其为可信**，必须为后续Provider-specific显式扩协议并追加ADR/测试。SSE数据帧增量UTF8/有限单帧大小，不把未知Provider字段转发到客户端；实现同D08规定的timeout、背压、取消、输出审查。

**可信本地usage**：SELF_HOSTED仅当推理服务真实返回与本次任务ID对应、可审计的token count及已批准tokenizer/协议证据时才作为 `TRUSTED_LOCAL_USAGE`；单纯Gateway计数/Prompt字符猜测不算。GPU run完成证据与usage证据是两种不同证据；网络断连不证明任务已物理停止。

### 3.4 证据与错误状态唯一矩阵

| 最终观测 | Core结果报告 evidenceType | 执行与账务 | 资源与后续动作 |
|---|---|---|---|
| Provider被证明**完全未发送**（连接前失败且I/O明确0出站） | PROVEN_NOT_SENT | FAILED / RELEASED | 释放实际预留，Permit标不可再次使用；Provider发送计数0 |
| 已实际出站、可信完整usage并通过价格/上界校验 | TRUSTED_PROVIDER_USAGE或TRUSTED_LOCAL_USAGE | OUTPUT_COMPLETED或可解释的部分生成 / SETTLED | 一次性结算冻结双账，写Ledger/Outbox；释放差额 |
| HTTP 200文本完成但usage丢失/不可信 | UNKNOWN | OUTPUT_COMPLETED / PENDING_RECONCILIATION | 保留真实Points/外币风险、立对账案，输出usage=null |
| timeout/客户端断开/Provider 5xx，可能已发送 | UNKNOWN | FAILED/CANCELLED/UPSTREAM_UNKNOWN / PENDING_RECONCILIATION | 尽力终止并监控，不能因HTTP错误直接释放资金 |
| 有可信供应商账单，但费用超过原上界 | TRUSTED_PROVIDER_USAGE | OVERRUN（仍保留真实执行结果） | 记真实额外采购事实和异常，冻结该采购预算币种后续收费，不能把MySQL正常账户扣成负数 |
| 使用报告ACK丢失 | 原 reportOperationId + 原 evidence digest | 不变，按Core持久收据核对 | 允许重复**报告账务**，不重复发送Provider |

`result_report_receipt` 的证据摘要对 `schemaVersion,invocationId,attemptTokenDigest,providerRequestId,usage原整数,adapterCompatibilityRevision,priceSnapshotIds,evidenceType` 形成规范化摘要；报告操作键 `reportOperationId` 一次确认后相同键不同摘要409 `RESULT_REPORT_CONFLICT`。`UNKNOWN`不是便于放行的成功证据；只有可信账单或证明未收费的独立证据能将其财务状态推进。临时缺失的上游Provider requestId若Provider不提供可为null，但若因此无法将可信usage唯一关联本次attempt，应保留PENDING。

### 3.5 运营批准与版本变更边界

生产启用第三方部署前必须明确：真实provider baseURL/地区、真实协议/计费货币、公开且有效的价格版本、合法机密数据边界、可信tokenizer/capabilityRevision、实际输出上限与usage证据测试、Provider限额/采购预算、密钥KMS或受保护注入方式。**这些是部署审批输入，不由执行AI凭文档示例凭空设值**。若业务尚无真实供应商，允许用FakeProvider固定测试各状态，但只可宣称Fake联调。正式Adapter若需新能力（如Cache独立价格、reasoningToken、tools、provider billing reconciliation API），总控先修改价格/状态/表及ADR，再编码；不让执行AI自己给新增计费字段默认0。




## 4. 验收标准与不可变条件
- 资金不负、未知不报0；所有关键真实I/O失联都不能授权第二次Provider发送。
- API字段与迁移真实列以[API设计](../06-api-design.md)、[DB目录](../05-database-design.md)为准；有冲突须提交总控修正。
- 相应Phase真实负例/故障测试详见[测试策略](../09-test-plan.md)；当前NOT RUN。
