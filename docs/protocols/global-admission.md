# P12全局准入、ReadinessProbe与灾难恢复

> 规范等级：P0 详细协议设计候选；按对应Phase首次实现并执行实际测试。本页不是运行日志。

## 1. 目标与适用阶段

P12 Core V11新增一张单例状态表，初始ADMISSION_BLOCKED；正常Permit持FOR SHARE共享锁，运营BLOCK持FOR UPDATE排他锁。P12 readiness-probes由Core真实受信检查后记录admin_audit_event/HMAC证据，30秒内才可review；旧Core、Redis Epoch不一致、未知风险未隔离不得恢复SERVING。此全局Epoch与P7 perPool Epoch分别验证。

## 2. 参与者与执行流程

具体参与者遵守[架构边界](../03-architecture.md)、[内部RPC](../api/internal-rpc.md)以及Core唯一Permit/事务约束，客户端不提供权威费用、治理结果或真实ProviderSecret。

## 3. 字段、状态、失败与恢复详细合同


**业务问题**：P7容量Pool基于Redis Guard/Provider状态防止重复派发，但P12已经有多个Gateway和Core进程、三服务以及真实备份恢复。如果“停止新收费调用”只写某一个JVM变量/某个Redis键，网络分区、Redis重启和Gateway滚动升级会产生节点间不同的准入决定；而直接在已有资金预算表里塞运营开关会混淆公司财务额度与系统健康。原D29提过`runtime_admission_control`可选表；本次明确选择**Core MySQL单例持久权威**，所以它不再是随演练决定的空白。

### 62.1 方案、归属与SQL字段级合同

P12 新增 **Core V11__runtime_admission_control.sql**（命名指Core自身增量版本，P11 FinOps独立finops_db V1）；增加且仅增加一张表`runtime_admission_control`，Core数据库数量从54变成**55**，FinOps仍7，总设计**62张表**，不提前修改已经设计好的 Core V1～V10或FinOpsV1。这里给实施型数据库合同，不是本人替本地AI编写迁移文件。

| Column | MySQL8.4类型/Null约束 | 唯一/业务不变量 |
|---|---|---|
| id | TINYINT UNSIGNED NOT NULL PK | 唯一单例1；CHECK id=1；V11种子初始化一行 |
| state | VARCHAR(32) NOT NULL | CHECK仅`ADMISSION_BLOCKED`,`RECOVERING`,`SERVING`；`DRAINING`是Gateway实例状态，不是Core全局决策 |
| epoch | BIGINT UNSIGNED NOT NULL | 从0递增；每次经批准恢复入场都增加，禁止复用旧运营代次 |
| version | BIGINT UNSIGNED NOT NULL | CAS版本，初始0；每次合法迁移version+1 |
| changed_at | DATETIME(6) NOT NULL | Core数据库UTC时间，不能用Gateway客户端时间 |
| changed_by_employee_id | BIGINT UNSIGNED NULL FK→employee.id | 首次部署系统阻止状态可空；后续运营变更必须有效管理Actor |
| reason_code | VARCHAR(64) NOT NULL | 受控原因枚举，不允许原Prompt、Provider密钥或含PII自由文本 |
| recovery_reference_id | VARCHAR(96) NULL | 记录受控应急工单/处置引用；首次BLOCKED可null |
| last_readiness_digest | BINARY(32) NULL | 最近一次受控恢复检查证据HMAC/SHA-256摘要，仅摘要及引用，不存密钥/原内容 |
| created_at,updated_at | DATETIME(6) NOT NULL | DDL初次种子UTC_TIMESTAMP(6)；状态变更同事务审计 |

种子必须为`(id=1,state=ADMISSION_BLOCKED,epoch=0,version=0,reason_code=INITIAL_P12_MIGRATION)`。**禁止**因为部署完新表就立即SERVING；必须通过有证据的显式 `recovery-review` 才放开。应用数据库账号仅Core持读写，Gateway不能写，FinOps数据库不能引用/修改该表；Schema migrator用户和Core App用户分离。生产已有 Core V1～V10账本和资金历史，不得在迁移中DROP/CREATE旧表。

### 62.2 全局线性化、权限与对外端点

`GET /api/v1/operations/runtime-status`：仅GLOBAL PLATFORM_ADMIN，Core读取当前单例`state,epoch,version,changedAt,reasonCode`及聚合依赖健康（MySQL、Redis、GatewayPool、必要Core RPC、Provider安全配置、RabbitMQ/FinOps只影响报表，不必阻止既有Core独立收款）并返回`{controlState,epoch,version,observedAt,dependencies:[{name,status,observedAt}],readiness: "READY"|"NOT_READY"|"UNKNOWN"}`。依赖未知不伪装HEALTHY。

`POST /api/v1/operations/admission-block`：GLOBAL PLATFORM_ADMIN+mgt_、Header Idempotency-Key、body `{reasonCode,expectedVersion}`，事务锁 singleton、校验旧version/currentRole、转ADMISSION_BLOCKED、version+1、同事务管理审计；首次200返回`{state:"ADMISSION_BLOCKED",epoch,version,changedAt}`，相同operationId重放不得重复产生操作或提升epoch。阻止**新Core PREQUEUE和新Permit**，不能删除已发供应商可能收费的财务Reservation。MySQL已失联时所有新Permit和Gateway入口本地自动fail closed；不为报告缺少阻塞审计而放行。

`POST /api/v1/operations/recovery-review`：GLOBAL PLATFORM_ADMIN+mgt_+Idempotency-Key、body `{referenceId,readinessEvidenceIds:positiveLong[],decision:"APPROVE"|"REJECT",reasonCode,expectedVersion}`；必须包含真实DB/Redis/租约runId一致性、未知Provider账务风险盘点、安全策略/Key/价版本、相关Gateway版本门禁和最小负向Probe证据。审查不通过或拒绝，继续ADMISSION_BLOCKED并更新审计；证据收集中可以进入RECOVERING但它也拒绝新入场。**只有一次当前权限、当前证据充分的APPROVE事务**才能把RECOVERING/ADMISSION_BLOCKED→SERVING，epoch+1、version+1、同时记录证据Digest/Ref并审计；不能把过期readinessEvidenceIds重复提交作为新的成功证明。恢复期间另有FinOps lag可显示UNKNOWN，但不把报表滞后当成预算主库不可用；MySQL/Redis lease核心不可靠则绝不可SERVING。

`GET /api/v1/operations/reconciliation-cases`：严格Admin作用域读取Core真正未确认Provider账务风险，不提供将PENDING一键归零的写API；历史风险案件与是否允许**新的**安全业务调用是不同维度。若有系统级无法判定Provider出站的广泛恢复风险，则必须继续BLOCKED并进行人工审计，而不能因为一个个案件尚未解决就默认全部新Permit永远无法运行（由安全恢复政策及风险隔离证据判定）。

**本地幂等物理修订**：P12运营写操作不能只相信Redis短TTL，也不能仅靠单例的`expectedVersion`区分「相同业务请求重放」与「不同请求版本冲突」。因此Core V11除创建单例表，还必须对**已存在的V1 `admin_audit_event`做兼容ALTER**：新增 nullable `operation_idempotency_key VARCHAR(128) COLLATE utf8mb4_bin`、nullable `operation_request_hmac BINARY(32)`、nullable `operation_hmac_key_id VARCHAR(32)`；新增`UNIQUE KEY uk_admin_operation_receipt(actor_type,actor_id,action,operation_idempotency_key)`，三个新字段的CHECK为「同时全部NULL或同时全部NOT NULL」。旧审计行保持NULL，不影响历史；P12 `admission-block`和`recovery-review`首次执行时三列**全部非NULL**，由Core独立运维请求HMAC密钥基于canonical(action,body,actorId)形成摘要，KeyID供轮换追验，绝不存敏感原文。Core首先验证同Actor+action+operationKey收据：相同body重放统一409 `IDEMPOTENCY_REPLAY`并只带安全当前状态，不再次改变epoch/version；不同body重放409 `IDEMPOTENCY_CONFLICT`。不存在收据时锁运营单例、CAS expectedVersion、在**同一core_db事务**插入带幂等信息的admin_audit_event；唯一键和提交事实保证并发只成功一次。运营写入是单表状态变更+同库审计，**不新增另一张通用收据表**。真实审计保留期须长于可能操作重放窗口，不因状态恢复便删历史。

### 62.3 Gateway/Core/Redis 多实例状态判断

**Core Prequeue**和**Core最终Permit的同一MySQL资金事务**均读取全局state；Permit首先用MySQL8.4/InnoDB的`SELECT ... FOR SHARE`**共享锁定读**读取 `runtime_admission_control(id=1)`，必须state=SERVING且epoch为当前已批准代次才进行身份/价/资金锁序；`admission-block`用`SELECT ... FOR UPDATE`**独占锁**更新此单例。合法Permit之间共享锁可以并发，不因一个单例把所有Provider请求无意义串行化；停止操作必须等所有已取得共享锁的Permit事务提交/回滚，二者顺序由InnoDB锁等待与提交决定。谁先获得相容锁并提交决定新Permit能否合法发生：BLOCK先提交则Permit拒绝；Permit已先提交的网络请求仍可能出站，不允许管理界面宣称BLOCK追溯取消已有订单。两种操作锁序都必须以singleton开始（之后需要锁其它表时按D33账户锁序），并且**绝不在持此锁时等待Redis/HTTP或全模型文本扫描**。

Gateway可将Core的`ADMISSION_BLOCKED`缓存为**更严格拒绝**，但不能把之前`SERVING`缓存当成成功许可。任何Gateway本地状态未知→拒绝新业务；FinOps读模型延迟不改变Core准入状态。P7 perPool Guard的`admissionEpoch`管Redis容量生命周期，与P12 global `epoch`是**两个不同的版本轴**，不要求数值相等：Core要同时验证当前 global SERVING与真实perPool OPEN/runId/capacityLease。Gateway实例SIGTERM的DRAINING只阻止本实例新请求并有界等待在途/保留未知账风险，不能代替Core全局开关。

### 62.4 发布/恢复Runbook合同

- **P12引入V11是影响全局 admission 的兼容升级**：发布前先用受控入口暂停新调用并排查在途，升级Core以识别V11门禁再运行迁移，随后全量Gateway滚动至需要Core最新Permit的版本。种子BLOCKED；只有真实两服务路由/SQL/RedisGuard负向E2E后运营审核开SERVING。不允许旧Core实例仍能绕过V11读取许可。
- **Redis cold-restart**：PerPool Guard runId/epoch失效，Core即使global SERVING也拒新Permit；操作员核验旧SELF_HOSTED物理任务/未知Provider风险后升pool epoch并开放guard，若风险影响全局则admission-block。
- **MySQL真实备份恢复**：恢复的单例历史SERVING不足以证明新环境安全，部署启动应以运营配置强制阻断新入场，验证账本在备份后是否有真实外部账单/Outbox丢失，人工重建新global epoch并审计；无真实证据不能仅因为连接成功就自称SERVING。
- **RabbitMQ/FinOps故障**：Core真实账本/Outbox正常时已有服务继续，但FinOps显示DELAYED/UNKNOWN，不把报表宕机当可以删除Core reservation；若已知源存储不可保留Outbox则安全阻断相应写入资金操作。
- **量化验收**：至少Gateway×2、Core×2、Redis池状态、真实MySQL V11、FakeProvider/SSE。两个Admin同时STOP/恢复、恢复不充分、旧Core实例、Redis重启/主从切换、MySQL与Provider网络故障、Gateway SIGKILL，所有测试必须证明无新增未授权ProviderHTTP、资金不负、许可不重复、状态单例版本有序、异常输出正确并且故障指标可审计。

**重要变化**：D29以前将P12单例表描述为“可选/按演练决定”，在本 D55 明确**必须**。Core V11一表是业务实际需要的安全全局栅栏，不是为了丰富技术栈；P12验收结束后如果未来真实业务希望彻底不建该表，必须重新论证替代的跨Core强权威与ADR、而不是让执行AI改成JVM变量。最新全量规范SQL**设计表数62**，V11的细节由此字段合同与对应迁移测试约束，实际Flyway SQL仍由本地AI根据冻结设计编写。

**D52与D55物理闭合勘误**：D52的AdapterCompatibility现在Core V3 `model_deployment.adapter_compatibility_json/adapter_evidence_ref`持久化；D55的P12运维幂等在Core V11对V1 `admin_audit_event`补三个可空操作收据字段+唯一约束。Core55表、FinOps7表合计62张；新增是字段/约束，非表。所有设计仍待真实迁移验证。




## 4. 验收与不变量

- 资金不负、未知不报0；所有关键真实I/O失联都不能授权第二次Provider发送。
- API字段与迁移真实列以[API设计](../06-api-design.md)、[DB目录](../05-database-design.md)为准；有冲突须提交总控修正。
- 相应Phase真实负例/故障测试详见[测试策略](../09-test-plan.md)；当前NOT RUN。
