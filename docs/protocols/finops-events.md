# Core Outbox、RabbitMQ、FinOps Inbox与Manifest

> 规范等级：P0 详细协议设计候选；按对应Phase首次实现并执行实际测试。本页不是运行日志。

## 1. 目标与适用阶段

五指标各自独立单位/币种；Core资金/Outbox同库、RabbitMQ至少一次、FinOps Inbox+Fact+Aggregate同库事务后ACK、构建期间每build独立consumerName。SourceManifest每窗口最多5000，漏事件通过安全source-events补拉；无法核验必须DELAYED/UNKNOWN而不是0。FinOps运营费用需真实证据、另一管理员审批、原币种记录。

## 2. 参与者与执行流程

具体参与者遵守[架构边界](../03-architecture.md)、[内部RPC](../api/internal-rpc.md)以及Core唯一Permit/事务约束，客户端不提供权威费用、治理结果或真实ProviderSecret。

## 3. 规范字段、状态与失败恢复合同
**Business Goal**：使用Core同库真实Ledger/Grant/ProviderUsage事实生成可审计的五指标FinOps报表，而不把异步统计当资金权威。P11才部署独立aigate-finops、RabbitMQ和finops_db；P4已经落库的历史Outbox按同一安全白名单正常补拉，不在P1～P10提前要求RabbitMQ启动。

### 3.1 事件发布者、路由、白名单与不变量

Core `outbox_event` 是已提交的唯一事实源，RabbitMQ只是至少一次传输，FinOps Inbox/Fact/Aggregate使用自己的单库事务去重。`eventId` UUID、`businessEventKey`（同一真实来源业务事件唯一）、`aggregateRevision>0`、`schemaVersion=1`、`payloadDigest=SHA256(canonicalPayloadJSON)`，与Core Outbox表中既有列逐一对应。Outbox `payload_digest`为**64位hex字符串**，FinOps `finops_processed_event.payload_digest`为**BINARY(32)**，消费者须严格校验长度/字符后把hex解码为32字节，不能拿hex UTF8的前32字节冒充。

专用RabbitMQ exchange `aigate.finops.events.v1` = direct + durable，主queue `aigate.finops.projector.v1` = durable、消息 persistent，按受控routingKey `finance.<eventType>` 显式bind；专用 retry/DLQ `aigate.finops.retry.v1` / `aigate.finops.dlq.v1` 仅用于异常隔离，不把DLQ积压当已确认财务0。仅Core svc权限发布、FinOps svc权限消费，Core/FinOps不是用户HTTP API对外Broker。开publisher mandatory、correlated confirms和returns；`basic.return`或confirm nacked或超时 → outbox标RETRY/待核查，不能假装已投递；ACK可能已送达但Core本地UPDATE失败 → 重发相同eventId，由Inbox去重。

**发布白名单**（明确P4 test-only event不进入真实财务队列）：

| EventType / 来源Phase | 唯一源/业务键 | 允许生成的指标 | 金额/归属不可混淆 |
|---|---|---|---|
| `TEAM_GRANT_ISSUED_V1` / P5 | `team_grant.id` +发行revision；业务键 `GRANT:<grantId>:ISSUED:<revision>` | TEAM_GRANT_ISSUED_POINTS | 发行团队 issuerTeamId、recipientEmployeeId、正向POINTS，一笔Grant发行只一次，不把后续消费再算发行 |
| `INVOCATION_USAGE_SETTLED_V1` / P7 | `finance_operation.id` +结算revision；`INV:<operationId>:POINTS:<revision>` | EMPLOYEE_USAGE_POINTS | 实际已确认总POINTS或独立delta，usageTeamSnapshotId是调用时Team，不按查询时转岗重算 |
| `PROVIDER_COST_ESTIMATE_SETTLED_V1` / P7 | 同operation +采购revision；`INV:<operationId>:PROVIDER:<revision>` | PROVIDER_API_COST_ESTIMATE | 真实THIRD_PARTY实际部署+币种+冻结采购价；不是发票，不为SELF_HOSTED制造0 USD事件 |
| `LOCAL_FALLBACK_TOKENS_SETTLED_V1` / P9 | 同operation +本地使用revision；`INV:<operationId>:LOCAL_TOKENS:<revision>` | LOCAL_FALLBACK_TOKENS_USED | 只统计经过证明的实际Token，signed BIGINT delta、单位TOKENS，不复制为个人POINTS |
| `FINANCIAL_FACT_CORRECTED_V1` / P7+ | `<originalBusinessKey>:CORRECTION:<revision>` 且sourceRevision严格递增 | 原事件所属同一个metricType | 只新增delta事实，不覆写过去事实；不得更正成不同币种/其它业务身份 |

SelfHosted运营成本 **不通过RabbitMQ假装有Core事件**。第五个指标 `SELF_HOSTED_OPERATING_COST` 仅源自FinOps本地 `self_hosted_operating_expense` 在另一管理员批准后写 `self_hosted_expense_revision` + 本地Fact/Aggregate；Void/更正为有符号delta，不DELETE已发布成本。`FINANCE_OPERATION_TEST_SETTLED`仅P4受限测试Profile使用，在真实P11 relay事件白名单外，错误发布必须隔离并告警。

### 3.2 Core SourceEventPayloadV1字段级Schema

所有MQ消息外包络只能包含 `{eventId,outboxId,businessEventKey,aggregateType,aggregateId,aggregateRevision,eventType,schemaVersion:1,companyScopeId,businessAt,createdAt,payloadDigest,payload}`，无额外安全身份字段。**Core**以固定键排序、Decimal字符串、日期UTC稳定序列化 `payload` 并生成digest，FinOps仅对同一CanonicalBytes核对，不信客户端传来的digest。没有Prompt/Response/Key/EndpointURI/全文账单/Cookie/员工真实姓名。

| payload必填字段 | 适用EventType | 精确约束 |
|---|---|---|
| `sourceBusinessId`,`sourceRevision`,`metricType` | 全部 | sourceBusinessId=grantId或FinanceOperationId安全字符串，revision为正整数；metricType取五个固定枚举 |
| `businessDate` | 全部 | Core在发生事务时按已冻结公司IANA ZoneId得出的`yyyy-MM-dd`，更正使用原业务日；不以MQ送达日计 |
| `employeeId`,`usageTeamSnapshotId`,`issuerTeamId`,`recipientEmployeeId` | 按指标归属 | 用量(employeeId和usageTeam)必需、grant发行issuerTeam和recipient必需；不适用显式null，FinOps不得推断Team |
| `agentId`,`logicalModelId`,`deploymentId`,`providerId`,`resourcePoolId` | 原事实真实有值的维度 | 授权已冻结维度、没有真实值的项目为null，禁止按最新配置回填过往Model/Team |
| `serviceClass`,`fundingMode` | Invocation真实用量/费用 | NORMAL/LOW/EMERGENCY/LOCAL_FALLBACK等受控枚举与已保存历史快照一致 |
| `unit`,`currency`,`pointsAmount`,`currencyAmount`,`tokenCount` | 按metricType互斥 | POINTS → pointsAmount decimal6非null、其它金额/token null；CURRENCY →currency ISO4217和currencyAmount decimal8；TOKENS →tokenCount signedLong，其余null |
| `pointsPriceVersionId`,`purchasePriceVersionId` | 实际Invoice/员工费用相关 | 只传Core真正绑定的已发布版本；不允许用当前价补历史，SOURCE事件只能来源真settlement |
| `isCorrection`,`supersedesEventId`,`correctionReasonCode` | 正常事件false+两null；更正true+源eventId和受控reason | 更正必须有实际授权/审计凭证，允许负数delta；不是对原记录 UPDATE |

**唯一JSON null规则**：上表不适用字段必须显式null；任何缺少规范必要字段、未知enum、精度不合、负的首次发行、日期不符、eventId/digest不符都拒绝并送隔离DLQ，FinOps不得「尽力解析并假装0」。`FINANCIAL_FACT_CORRECTED`包含真实目标`metricType`，其它金额类别不可跨币种或跨维度更正；需要更改归属则以受控冲正原事实+经审批的新增相应正确事实两笔留下完整链路。一个Event只生成一个Metric Fact；一个Invocation有Points和Provider成本时是两个**各自唯一**的Outbox Event，绝不把它们混合成一个“总支出”。

### 3.3 源事件的生成/投递/消费状态机

- Core在确认Grant发行/真实用量结算/合法更正的**同一MySQL事务**写账户、Ledger、Outbox；待对账PENDING只在Core运营风险接口显示，不能创造`INVOCATION_USAGE_SETTLED`的已确认0事件。Outbox Relay按 `PENDING|RETRY` + nextAttemptAt选择短事务claim、`SELECT FOR UPDATE SKIP LOCKED`、唯一ownerToken/leaseUntil防并发双claim；commit后才执行Broker publish，不在账务DB事务等待Broker。
- Relay只有同时获得 **ack-confirm且无mandatory return** 才可以CAS ownerToken把同一Outbox改PUBLISHED；Broker confirm到达但状态CAS丢失则仍可能重新投递，使用完全相同eventId、payloadDigest和businessEventKey。异常有限退避→RETRY；不可解析/未知版本→QUARANTINED或DLQ并报警，历史保留，绝不静默删除。选定retry参数为1s、5s、30s、120s、600s，超过5次转隔离并以人工修复后原eventId安全重投；这些是可测试运行默认非生产SLA。
- FinOps收到消息先验证trusted routing/版本/digest，再将**Inbox Insert + Fact Insert + Aggregate UPSERT**在一笔finops_db事务提交，随后手动ACK；重复 `(consumer_name,eventId)` 相同digest ACK且不得二次加总，不同digest视数据篡改隔离。消费DB提交后Broker ACK丢失则照常ACK重复事件，不回滚已经确认的事实。
- `consumer_name` 使用 `aigate.finops.projector.build.<buildId>` 明确区分当前active和building投影，因此V1 `finops_processed_event`表**不用额外加buildId列**；对应`finops_fact.build_id` 和`finops_daily_aggregate.build_id`必须一致。双版本重建期间同一源Event必须分别投影到两个buildId且各自Inbox去重。每个 `finops_fact`记录`sourceBusinessId/sourceRevision/metricType`唯一，不能用重复MQ提高funding总额。
- 对已存在事实的真正纠错：append-only新的 `sourceRevision`与有符号 delta Fact；同一业务日/原Team旧快照回写新的Aggregate delta，不UPDATE原Fact，不把员工POINTS/货币/Token混加。事实与读模型可延迟，但绝不向Core回写账户/预算。数据库权限双向隔离。

### 3.4 SourceManifestV1 和缺事件恢复的可执行合同

沿D41，Core受信 `GET /internal/v1/finops/source-manifest?fromOutboxId&toOutboxId&maxItems` 最多5000，在InnoDB一致性快照中返回**本时间快照可见的所有已提交Outbox事件** `eventId,outboxId,payloadDigest,eventType,schemaVersion`及 `snapshotAsOf,rangeStart,rangeEnd,count,manifestDigest,hasQuarantined`；禁止依赖自增ID连续/最后一条Outbox published就断言中间没缺。若窗口实际条数>5000、源账本主库无法提供一致快照、包含不可审计异常或合法更正尚未投影，不能返回"complete=true"；回409 MANIFEST_SNAPSHOT_TOO_LARGE或标UNKNOWN。

FinOps对active/building每一个 `eventId+payloadDigest` 验证Inbox与已产生Fact（或经审批的仅内部测试无Fact类型），缺项后仅用受信 `GET /internal/v1/finops/source-events/{eventId}` 取**同一已提交Outbox安全Payload**，与manifest digest字节核对，再使用和MQ正常消费**同一套幂等事务**补拉。API不能让FinOps传任意SQL或拉未批准的服务机密。补拉后必须重新比对源区间，无法补齐 → `DELAYED/UNKNOWN`，绝不FRESH。

`FRESH(asOf)`只能代表具体 `snapshotAsOf` 之前已核验源集合的完整读模型；Outbox有新提交更正晚于asOf、Broker lag、DLQ、SourceManifest过大或FinOps运营费用本地修订未核对，状态必须保守。对Core与FinOps两个MySQL分属独立读事务，输出 `coreFactsAsOf`和`localExpenseAsOf` 两个时间，不声称跨库同一瞬间原子。Core `/pending-exposure`仍可在FinOps停机时独立查询真实风险。

### 3.5 真正可切换的 Read Model Build

`finops_read_model_state`已有 `active_build_id,building_build_id,lag_status` 单例行。开始rebuild时锁该单例行；新buildId从active/building/现有Inbox和Fact/Aggregate全部build历史的已见最大版本+1分配，禁止覆盖仍有历史行的旧build；创建building只改指针/状态。Building从Core真实安全事件历史清单+源补拉构建，旧Active继续服务已有已验证asOf。实时新事件至少投影到Active+Building各自Inbox；building若没通过直到切换时刻的manifest核验、存在pending/DLQ/来源不一致，保持REBUILDING或UNKNOWN，不切指针。切换在一个finops_db短事务中 CAS `active_build_id←verifiedBuildingId,building_build_id←null` 且保留旧build供回滚核对；其后旧数据按受控保留期清理，**不能删除任何尚需财务审计的真实来源事实**。

### 3.6 运营费用是真实本地财务事实

P11 V1固定允许的费用类别：`GPU_RENT`、`DEPRECIATION`、`ELECTRICITY`、`HOSTING`、`OPERATIONS`；受信来源 `CLOUD_BILL`、`RENTAL_CONTRACT`、`ACCOUNTING_ADJUSTMENT`，按真实公司会计凭证Ref+SHA256二进制digest验证完整单自然月/币种/具体资源池/金额。Core当前GLOBAL PLATFORM_ADMIN先验身份/审计，FinOps再审双人分离（createdBy≠approverId）、自身source unique/idempotency/revision；审批时在本地同库同时写Expense/Revision/Fact/Aggregate/Audit。草稿不能算费用；Void追加负delta更正但保留原票据。**没有真实Cloud账单/租赁合同的Fake GPU环境只可产生测试数据，不能发布正式运营支出**；FinOps读模型中LOCAL_EXPENSE与CORE_EVENT采用不同`source_kind`，没有跨库全局事务可假装来源自动同步。

**仍需真实验证**：MySQL 8.4一致性快照和窗口>5000、RabbitMQ confirm/returns、重复消息Inbox与双build、源补拉和历史负delta、更正跨月、权限scope SQL过滤、运营成本两人审批。未运行不能标PASS。




## 4. 验收标准与不可变条件
- 资金不负、未知不报0；所有关键真实I/O失联都不能授权第二次Provider发送。
- API字段与迁移真实列以[API设计](../06-api-design.md)、[DB目录](../05-database-design.md)为准；有冲突须提交总控修正。
- 相应Phase真实负例/故障测试详见[测试策略](../09-test-plan.md)；当前NOT RUN。
