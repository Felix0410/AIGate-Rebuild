# P0静态审查与跨阶段回归案例

> 所有案例都是实施/验证规格，**没有代码运行证据，全部NOT RUN**。按首次出现的真实Phase执行；静态设计审查和真实数据库/网络测试不是同一种证据。

## 1. 本册适用范围

实现时必须对照[测试总策略](../09-test-plan.md)、[Phase任务](../06-module-design.md)，每个case记录Git SHA、命令、镜像版本、passed/failed/skipped、实际Provider出站计数及未知财务风险状态。

## 2. 业务验收场景组 1


> 不增加纯重复的 CRUD case，以下各项是在正式编码前要证实“设计即能运行”的**Schema/Contract证明任务**，不得把已在GitHub写出的SQL文本视作Flyway通过。本次开发容器没有 Docker/MySQL/Maven工具；官方坐标存在只算文档核实。

| ID | 确切测试步骤 | 必须有的证据 |
|---|---|---|
| FZSQL-01 | MySQL8.4空 core_db 执行 V1__core_identity.sql 一次、重复 Flyway启动 | 6表、bootstrap id=1、FK/三管理角色CHECK、唯一索引、schema_history成功 |
| FZSQL-02 | 用两不同Team名并发调用Bootstrap首次创建 | 仅1次201/另1次409，Admin/Key/审计和singleton完全一致，SQL失败全部回滚 |
| FZSQL-03 | V1→D19 V2__runtime_identity.sql，再加入真实Agent/Employee两Key与ACL | 4张V2新增表、历史P1管理凭证/员工不变，禁用和重复Key约束验证 |
| FZSQL-04 | V2→D31 V3__catalog.sql 7表/3个父current指针 FK | MySQL真正允许循环外键迁移顺序，校验真实父外键、唯一ModelCode/DeploymentCode/PriceRevision |
| FZSQL-05 | 两管理员并发发布同ModelPrice和新revision、与旧Invocation冻结价竞争 | 只有一current价发布成功；已发布旧价金额/时间不被改，旧Invocation价ID不变 |
| FZSQL-06 | 人为让Model A的activePriceVersionId指向Model B的真实price | Core发布业务强校验拒绝；SQL FK虽能找到priceId但不会被错误当可执行 Model A价 |
| FZSQL-07 | V3→D32 V4__normal_quota_billing.sql 10表与所有十进制CHECK、FK | 真实MySQL迁移一次成功、V1～3数据不受损、账务按原Price FK |
| FZSQL-08 | 并发两operationKey同时耗尽一周Points/同币种NORMAL采购额 | 所有used+reserved满足正常限额，两个金额必须整笔预留或整笔回滚，不双占一笔货币 |
| FZSQL-09 | 第三方可信实际收费超过预留maxCurrencyAmount、普通POINTS上界不足 | 账务识别OVERRUN并冻结新采购、保存超额真凭证；不让DB CHECK拒绝真实费用、不伪造合法普通quota负值 |
| FZSQL-10 | FinanceOperation先存在但V7 Invocation尚不存在时运行P4 | P4可从V4独立启动/单测，无缺失表FK、无假的Invocation或收费Provider |
| FZSQL-11 | P6添加POLICY_EDITOR/SECURITY_APPROVER后以旧P1员工和旧角色真实DDL升级 | V6显式替换role_assignment角色CHECK/Scope；旧role可查且新角色受限，不允许Java扩枚举但SQL拒绝 |
| FZSQL-12 | V7创建Invocation指向finance_operation_id UNIQUE 后模拟同任务竞争 | 一真实 operation至多关联一个Invocation、只一Permit，ACK丢失不能凭dispatch-state重复调用 |
| FZSQL-13 | 真HTTP应用Core授权撤销、Policy指针更新、预算调整同时争用派发 | 按一致锁序/版本证明已撤销后新Permit拒绝；记录死锁、等待超时、事务回滚和无Provider出站 |
| FZSQL-14 | MySQL多事务在不同提交顺序插入Outbox自增ID，FinOps从高水位重建 | 证明仅ID不是commit watermark，sourceManifest/Inbox无缺口，无法证明则UNKNOWN |
| FZAPI-01 | POST 管理 Key同actor+key重复、不同body；PUT ACL重复；Client假X-Internal身份 | Key明文只回首次，409后无密钥；ACL真幂等；Gateway不得信Client身份Header |
| FZAPI-02 | Gateway→Core Permit 事务已提交但ACK丢失、result报告ACK丢失 | 前者Provider0且原双风险pending，后者只重报相同结果一次settle、FakeProvider只接收1次 |
| FZAPI-03 | Core FinOps TEAM scope TypedQuery，跨Team过滤/分页 total/过时角色吊销 | Core新请求拒绝，FinOps SQL先scope后COUNT，pending-exposure仍Core直接查 |
| FZOPS-01 | Maven有效POM、Bootstrap/GatewayMVC/MP/MySQL/Redis/FlywayCLI/Testcontainers2依赖与服务真启动 | 真实Java21/Boot4.1.1/Cloud2025.1.2依赖树、镜像digest、CI命令+GitSHA+通过失败跳过数；旧Boot3模块缺席 |
| FZOPS-02 | 两Gateway +Redis Lua/共享Pool、FinOps RabbitMQ+Inbox 宕机重放 | 不超容量、不越资金、Gateway不因ACK重发、报告异步lag可见、资金独立 |

**门禁解释**：文档SQL是“准备验证的基准”，不是可直接认定通过的SQL。主环境无Docker/MySQL/Maven，FZSQL等均 NOT RUN；跑失败须由总控维护并改文档，再生成对应migration测试。架构Review必须能说清楚一个MySQL事务为什么不能原子包含Redis/Provider出站和FinOps。



## 3. 业务验收场景组 2


| ID | 新实际测试输入 | 精确期待结果 |
|---|---|---|
| FZSQL-15 | Core V4→V5 Grant三表，两个并发TeamManager发行不同Grant合计超过Pool | 唯一receipt/Pool行锁只批准足额发行；实际issued一次、Grant消费不再扣Pool、过期不退 |
| FZSQL-16 | V5→V6治理七表与Role CHECK变更 | 旧ADMIN/MANAGER/FINOPS Reader可查询；新增GLOBAL EDITOR/APPROVER受约束；DDL真实FK与CHECK通过 |
| FZSQL-17 | POLICY_EDITOR自审、审批人不同，两个publisher同时切Policy pointer | 自审拒绝，指针只能指同Policy已批准版本，PREQUEUE/PREDISPATCH最新发布不被旧缓存覆盖 |
| FZSQL-18 | 同traceId同phase同policy两次真实复评；同一次报告重试 | 不因UNIQUE缺ordinal误阻正常复评；相同ordinal/相同digest可安全幂等处理，真实Rule审计不含原Prompt |
| FZSQL-19 | V6→V7，Core V7五表及V6治理审计、P3 Deployment capacityPool FK迁移 | 五表/FK真实成功；Invocation与financeOperation UNIQUE、Permit同Invocation唯一、ResultReceipt同ReportId唯一 |
| FZSQL-20 | V7→V8两表，V3 Model原先空 routing pointer，新有效rev CAS | 新routingRevision表可作为Model FK目标、历史价表/授权不变；陈旧健康不变成HEALTHY |
| FZSQL-21 | V8→V9五表，联合原积分/CompanyPool/EmployeeCap正常及异常结算 | 同事务资金不透支；LOCAL_BASIC不虚构零USD Provider采购账；旧Grant不重扣 |
| FZSQL-22 | V9→V10五表，两个申请争同Employee+Agent+TaskRef的活动Slot | 同时至多一activeSlot；已到期清理受锁且历史Approval保留、无任务资金在审核时预扣 |
| FZSQL-23 | finops_db独立从空库V1七表，Core与FinOps各独立DB账号 | 交叉SELECT拒绝；FinOps真实UUID inbox事件可建立；Core没有finops_db写权限 |
| FZSQL-24 | 同Core事件在Build1和Building2重建，旧Build1仍有读请求 | 两投影build独立、Inbox按consumer/build去重、旧报表不为0、验证合格后指针原子切换 |
| FZSQL-25 | FinOps对LOCAL_FALLBACK_TOKENS_USED作真实负向更正 | Fact有符号token delta、Aggregate不会溢出/被误当无符号；新revision不可改变原Core账 |
| FZSQL-26 | Core事务分配低Outbox ID却迟提交，高ID先提交；FinOps增量重建 | ID高水位不足以证明无缺口，真实一致manifest核对才FRESH，否则UNKNOWN |

**D40附加门禁**：finops_daily_aggregate完整复合唯一索引必须在真正 MySQL8.4 测到长度/列数没有超过InnoDB限制；finops_fact 单位 CURRENCY/POINTS/TOKENS CHECK在真实事务下生效；SelfHosted完整月日期与双人审批由Core授权代理/FinOps本地事务双重检查。以上测试均未编写/执行。禁止把“设计61表”“预计通过”换算为 BUILD SUCCESS 或凭空增加测试运行数量。



## 4. 业务验收场景组 3


| ID | 真正待执行的验收 | 判定 |
|---|---|---|
| FZSQL-27 | 在真实 MySQL InnoDB REPEATABLE READ单事务中并发两个 Outbox 事务（低ID迟提交，高ID先提交），发source-manifest V1覆盖相关业务时间范围 | 在同一快照可见的**全体已提交**相关 eventId+digest 都被一次列举，不能只按MAX(id)推断完整；服务限额/超时要有明确错误 |
| FZSQL-28 | 一个窗口有5001条可见相关 Outbox，调用maxEvents=5000 | 返回409 MANIFEST_SNAPSHOT_TOO_LARGE，FinOps维持UNKNOWN/DELAYED，不返回被截短的“FRESH manifest” |
| FZSQL-29 | 同一核心历史业务事件在快照以后迟到更正，另有FinOps本地月成本审批同时提交 | 只对各自真实asOf做完整性声明；新revision按旧业务日/完整月份修订；两个DB不同快照不虚构一个跨库原子asOf |
| FZSQL-30 | FinOps重建build2失败、旧build1仍在服务读，同时MQ传新增事件 | 旧报表仍限定build1安全可读，build2重建不覆盖旧数；无完整catch-up不允许切activeBuild指针 |
| FZSQL-31 | 在真实MySQL 8.4分别运行 Core V1～V10 和 FinOps V1 全部Flyway迁移，核对61张设计表及约束 | Core54表+FinOps7表、外键先后/新增角色CHECK/事件事实build隔离/无跨DB FK均通过实际SQL；只有执行完成才标PASS |

**静态文本审阅结果（不等于以上测试运行）**：总控从《03》D30、D31、D32、D34、D35、D36、D37、D38、D39、D40十个规范段落提取 CREATE TABLE 名称，分别为6+7+10+3+7+5+2+5+5+7=57，发现57个不同名字、未见重复；D19原V2另有4表→目标共61表。程序结构性提取没有检测出明显尚不存在的跨阶段FK目标，但**尚未经过真正MySQL Parser/DDL执行**，不能称“DDL一定可运行”。规划的唯一数量为Core54+FinOps7=61；测试执行前不把静态表名计数当数据库迁移验收。



## 5. 业务验收场景组 4


| ID | 启动场景和HTTP | 真实验收 |
|---|---|---|
| FZROUTE-01 | P1真实Gateway/Core，只注册D44 P1管理和Bootstrap路径，请求P7 /v1/chat/completions与P11 /api/v1/finops/summary | P1管理路径真实走Core网络并独立鉴mgt_；后续路径404/不发送Core私有任意调用 |
| FZROUTE-02 | 用户显式URL编码/双斜杠/点段拼入/internal/v1、/actuator/env、X-AIGate-Service-Authorization伪造 | Gateway规范化路径后fail-closed，客户无法用svc_冒充内部身份，也不能探测Core私有方法 |
| FZROUTE-03 | P2真实Gateway新增/agents及/employee runtime credentials路径但不开放/public runtime验证 | Agent/员工Key CRUD走Core受权，/internal/v1/runtime-identities/verify从公网不可路由 |
| FZROUTE-04 | P3-P10逐Phase开启D44相应新增管理资源，刻意请求尚未实现的后续接口 | 只能当前Phase批准的精确路径+method，未知method/path 404/405；不使用Gateway裸/api/v1/**兜底 |
| FZROUTE-05 | P11从Gateway请求/finops/summary并伪造另TeamId或FINOPS_READER | 真正处理路径是Gateway→Core当前mgt_授权→FinOps scoped RPC；不存在Gateway→FinOps直接代理与跨Team total泄漏 |
| FZROUTE-06 | P10请求旧/emergencies/{id}/approve；D16旧字段apiVersion/modelCode/maxTokens；D33公开错误Shape | 旧审批端点404、非法RPC schema400、所有公开失败为 {error:{code,message,requestId,retryable}}，不暴露内部status/Secret |
| FZROUTE-07 | P10资金类型EMERGENCY_POINTS，先迁移V9再V10并对旧LOW历史查询 | V10真实DROP/ADD CHECK通过，旧funding_mode合法，紧急保留EMERGENCY_POINTS和已批准AuthorizationID/Task快照 |

**全部 NOT RUN**：Gateway WebMVC Filter/PathPattern匹配、真实HTTP Core代理/FinOps Scope、MySQL V10 CHECK ALTER必须用真Java+MySQL+多服务验证；不能将文档路径清单当作安全回归通过。




## 6. 业务验收场景组 5


本节是审查补充的可执行测试标准，不是项目源码或测试已经执行的证明。实现AI不得凭“总控设计审过”把任何测试标PASS。

| Case | 所属Phase | 输入/边界 | 真实验收必须观察的结果 |
|---|---|---|---|
| R-AUD-01 | P6 | 两管理员并发review同governance_policy_revision、旧expectedVersion重放 | version字段按真实MySQL CAS只有一个状态修改；审核与提交者不同、审计一次 |
| R-AUD-02 | P6 | Exception提交、批准、撤销并发及旧expectedVersion | governance_exception_request.version真实变更；响应每次回version；不得越过hardDeny |
| R-AUD-03 | P10 | 紧急申请allowedModelIds多项/申请快照被篡改/审核增加未申请模型 | requested_model_ids_json不可变，授权子集实际写emergency_authorization_model FK，不能多授 |
| R-AUD-04 | P6/P10/P12 | Gateway尝试额外路径/错误Method：policy active、exception revoke、emergency list、readiness probe | 只开放阶段内具体方法，旧/internal和未知路径全部拒绝，权限在Core重复核验 |
| R-AUD-05 | P12 | V11空库+已有V1～V10升级、旧admin_audit_event行存在 | 生成唯一BLOCKED单例行、V11操作收据ALTER兼容NULL历史，重复Flyway不改旧校验和 |
| R-AUD-06 | P7 | 容量Pool创建未提供受控resourceIdentityCode/审批证据、同实际身份重复建池 | 创建合法Pool默认为DISABLED，UNIQUE(scopeKind,resourceIdentityCode)生效；未激活Provider发送0 |
| R-AUD-07 | P7 | Deployment未绑定Pool、跨PROVIDER/SELF_HOSTED错误绑定、重复绑定旧version | 只有受控PUT绑定且ModelDeployment父version CAS成功，旧字段不能由P3请求提前填入 |
| R-AUD-08 | P7 | Pool status ACTIVE提交和Redis Guard OPEN之间SIGKILL/Redis切换；有未知GPU任务 | fail-closed，不能因DB ACTIVE或TTL掉线就给第二个GPU许可；Block后新Permit0 |
| R-AUD-09 | P6/P8 | Policy发布与其他版本修改竞态；P8发布与模型价格更新竞态 | 双CAS(version+activeRevisionId)唯一发布；GET带当前父version及生效指针，初次无策略只返回NOT_CONFIGURED，Runtime不得放行 |
| R-AUD-10 | P11 | 运营费用草稿初始revision1、第一次approve expectedRevision1 | FinOps数据库/HTTP版本一致，审批变成revision2且仅一个事实；旧version409 |
| R-AUD-11 | P11 | 同Actor相同Idempotency-Key新草稿不同源/不同金额及重建完成后同key重发 | V1持久唯一索引/HMAC收据防跨Owner和跨Build重做，旧buildId可查询，不重复聚合 |
| R-AUD-12 | P3 | Provider Secret UTF8最长4096字节 + AES-GCM tag16，合法密文/非法tag/超长 | VARBINARY(8192)容纳合法加密物理结果，密钥从不回显；非法tag不能恢复明文 |
| R-AUD-13 | P5 | 发行Grant附reasonCode、撤销附不同reasonCode、重复撤销 | issued_reason_code及revoked_by/at/reasonCode在V5表持久；重复204不改审计和资金 |
| R-AUD-14 | P7 | 两不同PoolCode指向同resourceIdentityCode、两个Deployment共享同池 | UNIQUE资源身份阻止重复身份注册，同一真正物理额度不会按Deployment重复计数；伪名要有人工批准证据 |
| R-AUD-15 | P7 | 初次池激活不传readinessEvidenceIds、Redis INFO切换 | P7由Core执行真实安全检查再用CAS+Guard开放；绝不依赖P12才实现的Probe ID |
| R-AUD-16 | P12 | POST readiness-probes真实PASS/FAIL、伪造ID/不同工单、过期超过30秒、已换Redis运行代次 | admin_audit_event产生受控probeId和不可伪造HMAC；旧/伪证据不能使Core单例变SERVING |
| R-AUD-17 | P12 | BLOCK与Permit短事务竞态、Gateway旧代码仍在路由 | MySQL FOR SHARE/UPDATE可解释线性化，旧Core不支持V11时拒恢复而非绕过 |
| R-AUD-18 | P0设计静态 / 对应Phase正式DB | 汇总62张不同CREATE设计表、所有内联FK、ALTER外键类型、重复CONSTRAINT和索引名 | 静态记录确认62不同表（Core55+FinOps7），全部外键物理目标存在且列类型一致；**只有真实MySQL8.4/Flyway顺序验证才可记PASS** |

**本轮静态证据**：审查脚本根据《03》设计SQL，找到63处CREATE TABLE片段（含D18历史bootstrap_control与D30规范版本重复），61张旧基准+R03新增runtime_admission_control组成**62个不同表名**；已有物理DDL+ALTER涉及的外键目标名、引用列名和同类列SQL类型静态检查未发现不匹配，主表中的索引/约束名未发现重复。文档级静态核对不具备MySQL Parser/DDL执行/隔离级别或真实SSE/Provider断网的证明力。

**归属**：R-AUD-01～18全部NOT RUN。审查修订的数据库列写在各自阶段规范V3/V5/V6/V7/V10/FinOpsV1/CoreV11，不允许执行AI把所有ALTER移到P1或自行另建第63张表。P12 Readiness Probe 使用已有admin_audit_event安全收据，不引入新服务/表。


## 完成条件

异常输入/越权/幂等/账务竞态/超时/重启不得仅用模拟UnitTest代替必要真实MySQL/Redis/MQ/两进程E2E。未实际运行前一律NOT_RUN；原草案描述中的阶段归属若与[Roadmap](../08-roadmap.md)有差异，以Roadmap和最新Database/API规范为准。
