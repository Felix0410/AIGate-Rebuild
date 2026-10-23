# 运行时请求规范化、HMAC密钥轮换及版本化

> 规范等级：P0 详细协议设计候选；按对应Phase首次实现并执行实际测试。本页不是运行日志。

## 1. 目标与适用阶段

P7 v1只含model/stream/messages/max_tokens/temperature/top_p；P9 v2增加fallback_mode；P10 v3增加emergencyAuthorizationId+taskRef。所有签名/核对由Core完成，Gateway不得计算、存储RequestDigestKey或利用JWT/Redis假定授权。V7 Invocation保存canonical_schema_version/payload_hmac_key_id。缺temperature与显式1.0、top_p缺省与显式1.0等价，null/未知字段400。

## 2. 参与者与执行流程

具体参与者遵守[架构边界](../03-architecture.md)、[内部RPC](../api/internal-rpc.md)以及Core唯一Permit/事务约束，客户端不提供权威费用、治理结果或真实ProviderSecret。

## 3. 字段、状态、失败与恢复详细合同


**发现的具体缺口**：D42只保存 `invocation.payload_hmac_digest`，未记计算此摘要所用的 Core HMAC KeyId。如果Preflight后运维轮换密钥，Permit时按新Key复算即使messages完全不变也会误判409；若尝试绕过比较，则允许请求被静默替换。P9又增加 `fallback_mode`、P10增加审批ID/TaskRef，如果HMAC只覆盖D42原六字段，则新关键授权语义不被「同一请求」保护。**修正已直接应用到上文规范 Core V7 CREATE TABLE invocation**：新增 `canonical_schema_version SMALLINT UNSIGNED NOT NULL DEFAULT 1`、`payload_hmac_key_id VARCHAR(32) NOT NULL`，CHECK schemaVersion 1～3；表数量不变。并非已运行的Flyway变更；若真实V7已经发布，则只能走后续ALTER新迁移。本Rebuild新仓库尚无真实V7执行记录。

### 57.1 不兼容语义必须提高 RPC Schema Version

| Phase | Gateway→Core preflight/dispatch-permits 的schemaVersion | Canonical字段（按序、明确纳入HMAC） | 能否接受 |
|---|---:|---|---|
| P7～P8 | 1 | `model,stream,messages,max_tokens,temperature,top_p` | P7仅v1；拒绝 `fallback_mode` 与紧急相关Header |
| P9 | 2 | v1六字段 + `fallback_mode`（NONE/ALLOW_APPROVED，缺省NONE） | P9后允许v1（视为fallback NONE）或v2；P9 LOW必须v2 |
| P10～P12 | 3 | v2七字段 + `emergencyAuthorizationId`（null或有效ID）+ `taskRef`（null或受控ASCII1～96） | P10后允许v1/v2/v3；只有v3可以授权EMERGENCY，v1/v2永远不能激活紧急 |

- Gateway 只在相应Phase放开外部 `fallback_mode`、`X-AIGate-Emergency-Authorization`、`X-AIGate-Task-Ref`。v3的两个紧急字段必须同时为非null、且与真实批准授权/Task模型、当前agk_+empk_与DB身份一致，否则403；非紧急v3两字段同时null。不能单方提交任务Header让Core将旧请求偷换成紧急。
- `stream_options.include_usage` 只是请求客户端与Gateway执行适配标志，不得被忽略而改变Provider可靠收费语义；外部 `ChatRequest` 的所有受支持参数映射到明确 CanonicalDTO：`stream_options` 有效模式必须事先归一化到可执行供应商请求并与业务摘要策略一致；**D42六字段的canonical若忽略 include_usage 参数，必须固定Gateway始终向支持者请求最终usage，客户端不能借更改该字段影响出站计费分支**。未来出现对模型行为有影响的新字段，必须提高schemaVersion并纳入签名。
- canonical不是随意序列化原JSON：对象成员排序按上述合同，UTF-8保留原Prompt字符（不trim、不做Unicode文本归一化），数值按声明类型和默认精度归一化（省略temperature=默认与显式给同值等价；null无效）、不包含临时 X-Request-Id/HTTP Header顺序或客户端声称的员工身份；同schemaVersion应形成唯一规范字节序列。不同schemaVersion的HMAC输入必须有独立域分隔：`UTF8("AIGATE:INVOCATION:V"+schemaVersion+"\\n") || canonicalBytes`。使用HMAC-SHA256，不是普通SHA或拼接明文密钥。
- Preflight在 Core 通过合法 schemaVersion/身份后，以当前激活Core RequestDigestKey算摘要和对应KeyId，一次性写入Invocation `payload_hmac_digest`、`payload_hmac_key_id`、`canonical_schema_version`。这些值不返回给Gateway、Redis或客户端。Permit阶段从同一真实Invocation锁定读取并根据**原KeyId及原schemaVersion**重新计算相同版本的规范请求摘要，以常量时间比较；版本/KeyId缺失或旧Key已不可用则 fail closed、不得预留资金或发送Provider。绝不自动切换到当前新Key再当成相同请求。
- Key rotation：新请求使用新KeyId，旧KeyId必须保留至少所有**尚在合法队列窗口/未终结的原Invocation**完整校验需要的时间；若密钥被安全事件紧急撤销，受影响原Invocation停止新Permit并以安全原因失败，不自动用新Key替代。完成出站后的结果报告不重算Prompt HMAC，基于AttemptToken/报告幂等收据和真实Usage证据处理；不为财务结算长期保存旧HMAC secret。数据库KeyId本身不泄露密钥。
- 重复Idempotency-Key请求：必须用**首次存的canonicalSchemaVersion**进行可比的请求语义比较。一个相同业务V1请求在P9升级至V2但不要求LOW时，Core应可通过受控版本兼容映射与首次Key的字段默认值比较；如任何可能影响实际可用模型/资金/执行许可的字段差异，409 IDEMPOTENCY_CONFLICT且不派发。禁止直接取当前schemaVersion重新生成另一个操作使同Key重复执行。
- D42既有六RPC地址不变；P9/P10只扩展内部强类型DTO和协商v2/v3，响应 `schemaVersion` 必须回显本次支持的版本，不把v1 extra字段默默丢弃。Core与Gateway混部升级时先发布可验证的Core向后兼容版本，再放开Gateway新字段；不支持的版本400 UNSUPPORTED_SCHEMA_VERSION，不能退回宽松解析。

### 57.2 对应P9/P10真实持久状态

Core Invocation P9 V9仍按D43加 `actual_model_id`、`fallback_route_policy_id`、`fallback_policy_revision`、`funding_mode`；P10 V10仍加 `emergency_authorization_id`、`emergency_task_ref`。这些不是替代本次HMAC 2列，而是持久化实际资金/授权历史快照；**Core的Permit与账务预留同事务**核验相同schemaVersion/TaskRef/AuthorizationID与真实当前ACL/Policy/Pool/Provider采购预算。历史 NORMAL v1 可正常读取；P9必须v2授权LOW，P10必须v3授权EMERGENCY。FinOps事件只报告允许的任务关联安全元数据，不能包含RequestDigestKey、Task详情Prompt或机密内容。

**测试固定场景**：同一个合法v1请求先Preflight再轮换Key，旧Key仍受控可用时Permit通过；旧Key立即撤销时Permit拒绝且Provider0；修改任一消息、max_tokens、temperature、fallback_mode、taskRef、emergencyAuthorizationId时409且无新资金；P9 v1不能LOW、P10 v2不能EMERGENCY；schemaVersion=99和两个不全的紧急Header必须安全拒绝；同Idempotency-Key跨v1→v2进行合法默认兼容时不得重新派发；真实对照 Core请求保留Prompt仅在短时TLS受信内存，不入DB/Redis/log。所有测试当前 NOT RUN。




## 4. 验收与不变量

- 资金不负、未知不报0；所有关键真实I/O失联都不能授权第二次Provider发送。
- API字段与迁移真实列以[API设计](../06-api-design.md)、[DB目录](../05-database-design.md)为准；有冲突须提交总控修正。
- 相应Phase真实负例/故障测试详见[测试策略](../09-test-plan.md)；当前NOT RUN。
