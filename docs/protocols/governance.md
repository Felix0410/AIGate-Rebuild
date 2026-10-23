# 数据分级、内置RuleRegistry与安全失败

> 规范等级：P0 详细协议设计候选；按对应Phase首次实现并执行实际测试。本页不是运行日志。

## 1. 目标与适用阶段

首版仅封闭builtin规则，可信下界缺省INTERNAL。RESTRICTED/高置信Credential命中禁止全部Provider出站；CONFIDENTIAL仅经批准的SELF_HOSTED非流式缓冲输出审核。审批不允许自审，例外只对特定误报信号、员工/Agent/Model有限生效，不可突破真实来源标签。

## 2. 参与者与执行流程

具体参与者遵守[架构边界](../03-architecture.md)、[内部RPC](../api/internal-rpc.md)以及Core唯一Permit/事务约束，客户端不提供权威费用、治理结果或真实ProviderSecret。

## 3. 字段、状态、失败与恢复详细合同


**业务问题**：D48仅给出 RuleV1[] 但没有定义可提交的 `ruleCode/safeParameters`。如果执行 AI 允许任意正则、脚本或把客户端声明PUBLIC当受信数据，则敏感信息可能未经批准出站；若什么都禁止也无法完成真实内部Agent调用。因此首版采取**小型确定性内置检测器 + 服务端可信分级下界 + 管理方固定出站矩阵**，不新增DLP微服务/外部审核API，不声称能识别所有隐私与Prompt Injection。代价是保守拒绝和可管理的误报。

### 58.1 输入与可信分级

在 Gateway解析受控纯文本messages（P7 V1最多64条、请求≤64KiB）并通过受信RPC发送至Core；Core根据**已发布P6 Policy**计算可信下界。未给出受信数据源标签时，**默认 minimumClass=INTERNAL**（不是PUBLIC）；若Agent有被Core审批的专用公开数据源配置，才允许使用PUBLIC最低级；可信来源明确标记CONFIDENTIAL或RESTRICTED优先级更高。客户端body里的 dataClass、system prompt里要求“忽略分类”以及伪造X-AIGate-Class Header都不是降低等级的依据。P6 Rule的扫描结果只能提高effectiveClass或产生hardDeny，不得降低可信下界。

安全主线固定：扫描**全部**消息（含system/assistant）；每条UTF-8解码、长度/总字节有界，不截断后放行；执行预算溢出、无法解析、Detector异常一律 `GOVERNANCE_SCANNER_UNAVAILABLE` fail-closed且Provider请求0，审计安全code；不在Redis/DB/log/trace中存文本或命中片段。初版扫描用Java有界字符串查找/经过测试的固定预编译模式、不会接受上传脚本/动态运行时正则。扫描不是在持有MySQL预算行锁期间做，而是在Prequeue、Permit之前分别完成，并以Core锁定activeRevision再判定最终出站。

### 58.2 封闭 RuleV1 DTO 与允许的4类检测器

`RuleV1={ruleCode:string,ruleKind:"CLASSIFY"|"SECRET_HARD_DENY"|"EGRESS_DENY"|"OUTPUT_REVIEW",severity:"INFO"|"WARN"|"DENY",detectedClass:"PUBLIC"|"INTERNAL"|"CONFIDENTIAL"|"RESTRICTED",exemptible:boolean,enabled:boolean,ruleOrder:nonnegativeInt,safeParameters:object}`。每个policyRevision最多64条规则、ruleCode不能重复、ruleOrder唯一且非负。只支持下列服务端编译器登记的规则，不支持用户自造的任意ruleCode。

| ruleCode | ruleKind / 参数 schema | 行为 / 是否允许例外 |
|---|---|---|
| `TRUSTED_CLASSIFICATION_FLOOR_V1` | CLASSIFY；`{defaultClass:"INTERNAL",honorVerifiedSourceTags:true}` | 从Core可信数据源/已审核Agent默认标签取最大等级；参数固定，来源RESTRICTED不可豁免；exemptible=false |
| `CREDENTIAL_PREFIX_HARD_DENY_V1` | SECRET_HARD_DENY；`{detectorSet:"AIGATE_AND_PROVIDER_CREDENTIAL_V1"}` | 对agk_/empk_/mgt_/bst_/svc_及已维护的真实供应商凭证标识/高置信模式检测，命中则hardDeny；须避免仅短词碰撞，detectedClass=RESTRICTED、severity=DENY、exemptible=false |
| `APPROVED_SENSITIVE_MARKERS_V1` | CLASSIFY；`{markers:[{code,matchText,classification}],ignoreCase:boolean}`；每rule最多64 marker、每个matchText 4～128字符，内部只存管理员批准的分类关键字/标记，**不能包含真实凭证或整段私密Prompt** | 命中时将有效等级提升到marker.classification（INTERNAL/CONFIDENTIAL/RESTRICTED）；只有经审批的**误报分类信号**能申请范围限定例外，exemptible=true，不可覆盖可信来源标签 |
| `CONFIDENTIAL_OUTPUT_SECRET_REVIEW_V1` | OUTPUT_REVIEW；`{detectorSet:"AIGATE_AND_PROVIDER_CREDENTIAL_V1",maxBufferedBytes:positiveInt}`，maxBufferedBytes≤262144 | 仅用于CONFIDENTIAL自部署 stream=false的缓冲文本审查；命中或超过上限拒绝向客户端输出，但上游Usage正常结算/UNKNOWN保留；exemptible=false |

**不可配置/永远执行的安全不变量**（不依赖是否有人漏配Rule）：有效等级RESTRICTED或高置信凭证hardDeny→全部模型拒绝；CONFIDENTIAL→仅受信SELF_HOSTED且stream=false+输出审核；INTERNAL第三方须 Agent/Provider/region/Deployment批准；Policy不存在、未发布、信任边界/出站权限/Scanner异常→拒绝出站。EGRESS_DENY在V6 SQL允许作为`rule_kind`，但首版**不允许管理员上传任意EGRESS_DENY RuleCode**；出站矩阵通过下面封闭EgressV1声明，经Core硬安全不变量裁剪，不能通过disable某条rule解除。

### 58.3 EgressV1 和 OutputRuleV1 两个非自由对象

`EgressV1={dataClass,hostingMode,decision:"ALLOW_IF_APPROVED"|"DENY",requiresAgentApproval:boolean,requiresDeploymentBoundary:boolean}`。Policy最多8条、每个(dataClass,hostingMode)唯一；对应公开4级×2类型的8格，要求提交时**全部覆盖，不允许遗漏=ALLOW**。PUBLIC/INTERNAL的ALLOW仍需正常两ACL和Policy/Catalog/部署有效；第三方 INTERNAL 必须 requiresAgentApproval=true、requiresDeploymentBoundary=true；第三方CONFIDENTIAL/RESTRICTED和SelfHosted RESTRICTED必须DENY；SELF_HOSTED CONFIDENTIAL仅ALLOW_IF_APPROVED且强制buffered review。PUBLIC第三方还需真实Provider/region审批（不能只声明PUBLIC就可任意出站）。

`OutputRuleV1={dataClass:"CONFIDENTIAL",requiredMode:"BUFFERED_JSON",ruleCode:"CONFIDENTIAL_OUTPUT_SECRET_REVIEW_V1",maxBufferedBytes:positiveInt}`。首版最多一个、必须存在且仅控制CONFIDENTIAL自部署非流式输出；大小上限不超过262144、缓冲超限/扫描失败即不回送响应，**但费用照实结算**。其他数据等级不假称有全量实时SSE输出审核。

`POST /api/v1/governance/policies/drafts` 的 `classificationRules` 必须至少包括并启用不可豁免的TRUSTED/FLOOR与CREDENTIAL/HARD_DENY，敏感Marker可以没有；`egressMatrix`必须完整8格；`outputReviewRules`必须含CONFIDENTIAL BUFFERED规则。Core在提交/发布时双重校验 config_json 与独立 governance_rule 行一一对应、configDigest一致；公开管理GET仅返回批准的ruleCode与编译状态，不回敏感Marker的原matchText（可由受控管理员的独立配置编辑会话输入，不在普通列表中明文展示）。

### 58.4 受限例外与可信审计

- 例外只允许 `APPROVED_SENSITIVE_MARKERS_V1` 对具体 Employee+Agent+LogicalModel+ruleCode 的已被证明误报信号生效，且该例外不能降低任何可信标签/其它命中/原出站矩阵；安全审核者不得是申请者。一个原文命中里同时有高置信凭证，例外不产生ALLOW。例外过期/撤销后 Permit最终复核必拒绝。
- 在数据库保存 `ruleCode,decisionPhase,decisionOrdinal,scannerVersion,effectiveClass,policyRevision,reasonCode,inputHmacDigest,traceId` 及主体安全ID；其中inputHmacDigest只由Core独占的专用键产生，与D50 Runtime RequestDigestKey**不同用途**，不回客户端。绝不保存命中matchText、用户正文、完整模型回复、供应商密钥或高基数Prompt。
- 执行预算/大小作为经核验的可调整服务器配置（初始设计上下界：scanner总耗时最多200ms；超过时间无ALLOW；总输入≤64KiB；maxBufferedBytes≤256KiB），上线真实性能要有测试、不能把200ms当实际SLO测量。规则语义变更提升scannerVersion与新PolicyRevision，不修改已发布config；配置失效不允许 Gateway 用last-known-good绕过Core当前指针。

### 58.5 非成功路径与最小Fixture

测试：`classificationRules`缺 `CREDENTIAL_PREFIX_HARD_DENY_V1`→400 INVALID_GOVERNANCE_POLICY；`egressMatrix`漏第三方CONFIDENTIAL→400；用户声明 `{"dataClass":"PUBLIC"}` 但Core可信来源CONFIDENTIAL→只能已批准本地非流式，否则403/422；输入64KiB边界/扫描预算耗尽→安全拒绝且FakeProvider计数0；命中 `mgt_`真实形状的高置信凭证→hardDeny，任意例外无效；输出扫描命中后不送全文但保留已发生的消耗/PENDING。

**状态与剩余条件**：D51为具体可实施规则候选，但公司真实敏感标记词库、地域/Provider处理协议与规则误报接受标准必须由企业管理员在部署时审批并填入，不能由开发AI编造；确定性扫描不提供保证发现所有秘密的承诺。P6 Rule Registry实现/极端输入测试及真实Policy发布未发生，全部NOT RUN。




## 4. 验收与不变量

- 资金不负、未知不报0；所有关键真实I/O失联都不能授权第二次Provider发送。
- API字段与迁移真实列以[API设计](../06-api-design.md)、[DB目录](../05-database-design.md)为准；有冲突须提交总控修正。
- 相应Phase真实负例/故障测试详见[测试策略](../09-test-plan.md)；当前NOT RUN。
