# P8～P12路由、LOW、紧急、FinOps与恢复测试

> 所有案例都是实施/验证规格，**没有代码运行证据，全部NOT RUN**。按首次出现的真实Phase执行；静态设计审查和真实数据库/网络测试不是同一种证据。

## 1. 本册适用范围

实现时必须对照[测试总策略](../09-test-plan.md)、[Phase任务](../06-module-design.md)，每个case记录Git SHA、命令、镜像版本、passed/failed/skipped、实际Provider出站计数及未知财务风险状态。

## 2. 业务验收场景组 1


复用旧 RTE/ALG/PRI/FAL/FIN/FNA/OPS/MSC/RPC/PROT/BUD 测试；以下为具体新增可量化业务断言，**不是重复实现五套测试框架**。集成需真 MySQL8.4、Redis Lua、Gateway/Core/FinOps独立JVM，P11用RabbitMQ持久队列，Fake ThirdParty与SelfHosted提供真实请求数、usage、断包与延迟证据。各阶段未启动前测试均 NOT RUN。

### P8：D25-RT01～RT12

| ID | 真实场景 | 验收判定 |
|---|---|---|
| D25-RT01 | Core V7→V8，两个同型号多部署 | 路由政策、观测/索引只迁移一次；既有资金/ACL/价表不变 |
| D25-RT02 | 两个不同 LogicalModel 但 HTTP 协议相同 | 不能跨型号进行“健康自动切换” |
| D25-RT03 | HEALTHY 但 EmployeeAgent/AgentModel 无权、P6策略拒绝 | Provider请求0，健康不能突破硬过滤 |
| D25-RT04 | 60秒20条真实故障率24%/55% | 分别 DEGRADED/UNHEALTHY；少于20样本不得靠单个百分比判窗口断路 |
| D25-RT05 | 连续3个归属部署真实连接/5xx故障 | 打开冷却，普通请求不再被推荐，30秒后真实单探路 |
| D25-RT06 | 429带受控Retry-After，用户错误400与客户端取消 | 429进 THROTTLED，客户错误/取消不记 Provider失败 |
| D25-RT07 | 证据超过120s、新部署无数据 | UNKNOWN且不伪填0毫秒延迟，仅管理员批准安全试运行 |
| D25-RT08 | 同健康/优先级两个 Deployment 不同活跃/等待 | weightedLoad排序在两个Gateway/共享Redis观察一致且真实许可不过额 |
| D25-RT09 | 同币种低风险价 vs USD/CNY 不可比价格 | 同币种可判价；跨币种不得按虚拟汇率低价自动选 |
| D25-RT10 | Admin并发发布相同 activeRevision；参数越界 | CAS一成功一409、旧路由版本不可覆盖，Core审计准确 |
| D25-RT11 | 原 pool lease取得后部署被禁或同型号不同 pool 可安全重选 | 旧lease归还、一个全局deadline、重选最多一次，未申请第二份收费许可 |
| D25-RT12 | permit已提交或上游发过网络字节后Provider5xx | 不跨部署重新发送、原财务风险保留及最小可追踪状态 |

### P9：D26-FAL01～FAL14

| ID | 场景 | 验收判定 |
|---|---|---|
| D26-FAL01 | V8→V9 配置公司池/员工周cap/本地quota | 五表迁移成功，旧TeamGrant/供应商预算保持 |
| D26-FAL02 | NORMAL不足、fallback_mode缺失或NONE | 安全402，不能静默换劣质模型 |
| D26-FAL03 | fallback_mode=ALLOW_APPROVED但无管理员可信fallback | 不换型号，Provider0；不能由body指定任意Provider |
| D26-FAL04 | fallback型号跨AgentModel无权/治理越界 | 403，不能借LOW获取更高数据出站权限 |
| D26-FAL05 | Agent10/Employee5/Grant5，低价风险30，公司补10 | Core同事务正常来源20、公司池10、员工周cap10，所有积分仅一份 |
| D26-FAL06 | 经降级后普通积分足付新价 | LOW队列但公司pool/员工cap已使用量=0 |
| D26-FAL07 | 公司fallbackPoints足够但USD NORMAL采购份额不足 | Provider0，两本账回滚，不可借EMERGENCY份额 |
| D26-FAL08 | 两Employee共享公司fallback池并发超限 | used+reserved<=limit，没有双重批准或透支 |
| D26-FAL09 | 付费LOW在真实排队前只读预评估不合规/不可负担，另有获准LOCAL_BASIC；以及付费LOW已入队后余额突变不足 | 前者直接只选LOCAL_BASIC占每员工周Token+GPU，无0 USD采购；后者不得再跨型号切本地重发，返回受控资金拒绝 |
| D26-FAL10 | 同Employee不同Agent切换请求本地周补贴 | 共用一个周桶，不重置额度；跨周仅原桶结算 |
| D26-FAL11 | 本地GPU pool实际已满、Redis丢共享许可 | 拒绝/有界WAITING，不能假装公司账单变0或用JVM Semaphore放行 |
| D26-FAL12 | NORMAL→LOW改型号又排队超时 | 共享原队列deadline，最多一次跨LogicalModel降级 |
| D26-FAL13 | 首次Permit已提交/Provider可能接收后再请求fallback | 不能触发任何第二Provider发送；保留最初资金风险 |
| D26-FAL14 | LOW已预留跨周/月、Grant到期/未知供应商usage | 公司cap/旧Grant/外币预算保持原账，可信settle之后再释放，Team发行不回冲 |

### P10：D27-EM01～EM16

| ID | 场景 | 验收判定 |
|---|---|---|
| D27-EM01 | V9→V10 紧急五表、活动槽 | V10表/FK/slot唯一约束生效，旧NORMAL/LOW未变 |
| D27-EM02 | 普通Employee替其他Employee申请或无Team权限 | 403，不能通过请求beneficiaryId绕对象权限 |
| D27-EM03 | 同申请人同Idempotency-Key重复申请 | request只一份，真实审计无重复成功 |
| D27-EM04 | 申请人同时有Admin角色试图自审 | 审批拒绝；服务svc_不代替另一审批人 |
| D27-EM05 | 另一ADMIN缩小Points/期限/允许Models并批准 | 只允许申请子集，产生单有效Authorization |
| D27-EM06 | 两Admin同时审批同Employee+Agent+Task两申请 | 只允许一条active slot，另一409，可证明SQL锁有效 |
| D27-EM07 | 旧授权过期且slot未清理，批准新授权 | 受锁复核旧时窗/slot，合法回收后一个新有效授权 |
| D27-EM08 | 未持真实授权的客户端伪造serviceClass=EMERGENCY | 403/400，不产生优先队列/资金 |
| D27-EM09 | 已批Task但审批人撤销且Gateway尚在WAITING | 新Permit拒绝，资金0/Provider0 |
| D27-EM10 | 审批获批但当月公司EmergencyPool不足 | 不执行，审批不保证资金可用 |
| D27-EM11 | 公司池足但Task cap不足或采购EMERGENCY份额不足 | 全部资金回滚/Provider0，不能借NORMAL采购 |
| D27-EM12 | 同Task两次并发紧急请求合计超cap | 最多额度内成功，used+reserved≤approvedLimit |
| D27-EM13 | SELF_HOSTED紧急推理且无第三方采购账 | 强制GPU许可+Task/公司池，不能虚构采购金额 |
| D27-EM14 | Redis三个档均持续WAITING，反复释放名额 | 4:2:1轮转可追踪，NORMAL/LOW不会永久饥饿，已运行SSE不被抢占 |
| D27-EM15 | Redis挂、两个Gateway抢同一pool真实lease | 新许可fail closed/无超发，旧token不能释放别人的 |
| D27-EM16 | 授权到期但旧Permit已出站、结算跨月且usage未知 | 原任务/紧急池/货币份额仍pending，无自动退款或重发 |

### P11：D28-FIN01～FIN16

| ID | 场景 | 验收判定 |
|---|---|---|
| D28-FIN01 | finops_db独立空库迁移V1，Core V4 Outbox已有事实 | FinOps7表迁移、各DB账号交叉SELECT拒绝，独立三JVM启动 |
| D28-FIN02 | Core结算一次usage写Ledger+Outbox | 同Core事务产生事件；FinOps未上线也不影响真实资金 |
| D28-FIN03 | Grant100发行，Employee30使用 | TEAM_GRANT_ISSUED100 与EMPLOYEE_USAGE30分开，不能相加伪采购 |
| D28-FIN04 | USD与CNY供应商结算、公司 NORMAL/EMERGENCY预算份额 | 不跨币种换算，也不把分区占用合计作第二份供应商费用 |
| D28-FIN05 | Outbox claim后Relay宕机重领 | 同eventId至多一条Inbox Fact，即使RabbitMQ重复送达 |
| D28-FIN06 | Broker confirm成功但发布进度丢失，或mandatory unroutable | 重发同eventId不多算，unroutable不可标PUBLISHED |
| D28-FIN07 | Consumer DB已COMMIT，尚未发RabbitMQ ACK即SIGKILL | 重投Inbox去重，Fact/聚合一次，manual ACK顺序正确 |
| D28-FIN08 | 不受支持Schema/永久坏事件及短暂DB失败 | DLQ/重试有界、错误与source可审计，不能silent drop |
| D28-FIN09 | 月末已派发，次月可信对账才完成/更正 | 原业务日期/调用Team快照修订，不统计到处理日 |
| D28-FIN10 | Core Outbox仍有缺事件但FinOps最近processedAt为现在 | readModelStatus UNKNOWN/DELAYED，不伪造FRESH或0实际费用 |
| D28-FIN11 | source-manifest真实完整且已覆盖Inbox/Fact，重建中又来迟到事件 | 无遗漏/重复，旧版可读且标REBUILDING，校验后切新版本 |
| D28-FIN12 | TeamManager查询另一Team 或通过total/分页绕过 | Core拒绝或FinOps SQL Scope先过滤，无法推断别Team费用 |
| D28-FIN13 | Employee调Team，Grant跨Team发放 | usage按历史 usageTeamSnapshot、Grant按issuerTeam；自查本人不丢旧数据 |
| D28-FIN14 | Employee只持自己mgt_访问采购/GPU公司费用 | 禁止，只有self POINTS/LocalToken，FinOps不接收原mgt_ |
| D28-FIN15 | 管理员A录入有证据真实月GPU费用自批，再管理员B批准 | A自批拒绝；B单本地finops事务Approved+Revision+Fact+Audit；Core资金不变 |
| D28-FIN16 | MQ/FinOps全停，Core待对账实际非零 | /pending-exposure仍显示Core真实数，summary 503或显式过期，不返回假0 |

### P12：D29-OPS01～OPS12

| ID | 场景 | 验收判定 |
|---|---|---|
| D29-OPS01 | 三服务不同可执行Jar、finops独立DB、两Gateway副本 | 网关无Core SQL、FinOps无Core SQL，真不同进程/端口 |
| D29-OPS02 | 金融Core MySQL断开，Redis/Provider健全 | 新收费Provider0、返回安全503，原pending不消失 |
| D29-OPS03 | Redis全停或重启ACTIVE空、SelfHosted仍有真实执行 | 不凭空放满GPU许可，新准入fail closed直到完成核查 |
| D29-OPS04 | RabbitMQ/FinOps宕机又恢复 | Core资金继续结算，Outbox最终可重放，报告延迟显式披露 |
| D29-OPS05 | Gateway SIGTERM/SIGKILL，存在WAITING和运行中SSE | 有界drain/取消，不能凭断连判Provider费用0或二次发 |
| D29-OPS06 | 管理员切ADMISSION_BLOCKED/RECOVERING/SERVING | 身份/RBAC/审计正确、对UNKNOWN账没有危险强制release接口 |
| D29-OPS07 | 旧备份早于一次真实收费Provider出站，尝试数据库恢复 | 必须阻止直接开放重复额度，产生事故/对账证据与人工恢复审批 |
| D29-OPS08 | 服务签名密钥/证书轮换与过期、Gateway外部伪造svc_ | 真TLS/Service认证边界正确，日志无secret/Prompt，过期拒绝 |
| D29-OPS09 | N/N+1 Gateway/Core/FinOps滚动运行与旧OutboxV1 | 兼容读写不改变冻结价/ACL，未识别事件须受控隔离 |
| D29-OPS10 | 固定Fake JSON100ms/1s、SSE10s、1vs2 Gateway | 同硬件/连接池/真实RPS/P50/P95/P99/TTFT/CPU/JFR/GC/Redis测量可重复，不能编造双倍收益 |
| D29-OPS11 | 慢SSE客户端压满有界连接与缓冲、Core热锁 | 拒新/背压且内存线程不无限增长、资金无透支/可审计 |
| D29-OPS12 | 全CI空库迁移、故障注入和安全指标审查 | Git SHA/镜像digest/命令/通过失败跳过数+DB断言可复现；未测生产HA/GPU要显式说明 |

**统计**：本节新增12+14+16+16+12=70个独立设计测试编号；与旧案例有覆盖关系，只实施唯一真实测试类并映射编号，避免“测试数膨胀但没有新验证价值”。实际全部 NOT RUN。仍须生成每个用例精确的 SQL+HTTP JSON 固件/时钟/故障脚本、Maven Testcontainers 兼容和CI证据，P0未冻结前不能冒充通过。



## 3. 业务验收场景组 2


| ID | 归属 | 输入/故障 | 自动化必须断言 |
|---|---|---|---|
| D50-01 | P4 | QuotaPolicy草稿发布、同作用域并发更新 | 同一作用域发布指针唯一，旧已存在周桶不追溯更改、重放旧revision409 |
| D50-02 | P4 | PUT USD月份预算金额JSON number/null、分区限额和超总额 | 非decimal8字符串400、超出总额409/400、没有部分写入 |
| D50-03 | P4 | 已有settled+reserved后下调公司预算 | 409且总月/分区账户实际DB不变 |
| D50-04 | P4 | GET FinanceOperation未知账及SELF_HOSTED | 未知费用null/PENDING，无供应商币种时currency=null而不是伪造USD零费 |
| D50-05 | P5 | Grant相同Idempotency-Key不同recipient/金额、并发Manager | 一笔发行且Receipt/Ledger/TeamPool原子，重放409、总额度不超 |
| D50-06 | P5 | 跨Team领取、Grant过期前已预留但完成后过期 | issuerTeam归属不变，历史元数据完整，余可新用为0、不退款 |
| D50-07 | P6 | 无Policy、未审批Draft、创建者自己review/publish | fail closed、403/409，任何Provider HTTP=0 |
| D50-08 | P6 | Config RuleV1未知/危险表达式、CLIENT降级dataClass、缓存旧revision | 拒绝非法配置/敏感内容；Core最终事务以MySQL activeRevision授权 |
| D50-09 | P7 | 非文本messages、超64条/64KiB、非法temperature/top_p、额外tools/providerUrl | 400，无财务预留/Provider发包 |
| D50-10 | P7 | preflight请求A→permit内容B、同Key JSON已完成重发 | 409，不增加另一个DispatchPermit，不新增上游发送 |
| D50-11 | P7 | 成功stream但缺可信usage、客户端中途断开 | 正确SSE错误/DONE语义；费用状态PENDING，不报告0 |
| D50-12 | P7 | 首次Preflight后轮换Core HMAC Key；旧Key版本仍受控可用 | V7记录keyId/schema=1，Permit用原Key通过，无Prompt落库 |
| D50-13 | P7 | 旧HMAC Key被紧急撤销/丢失、再请求Permit | fail closed，无新Reserve、Provider HTTP=0，不以新Key掩盖 |
| D50-14 | P7 | schemaVersion=99、null温度、原JSON字段序重排 | 非支持版本400；正常规范等价内容HMAC相同；非法null400 |
| D50-15 | P8 | 健康未知/异常TTFT/currency不同、发布旧expectedActiveRevision | 不拿0伪造TTFT，跨币种成本不比较，CAS并发只一成功 |
| D50-16 | P9 | schemaVersion1且fallback_mode=ALLOW_APPROVED、v2值改变 | v1拒绝LOW；v2字段被纳入HMAC，变更时409且不入账 |
| D50-17 | P9 | 付费LOW预评估可行，排队后预算耗尽 | 当前选中目标失败后禁止额外LOCAL_BASIC二次发送 |
| D50-18 | P9 | 本地补贴跨多个Agent/跨周、新旧可用Token | 共享员工周token限额，不制造虚假的供应商货币成本 |
| D50-19 | P10 | schemaVersion2带紧急Header、v3只带一个紧急Header | 拒绝；两Header和Core DB任务Ref/Authorization对应才可提EMERGENCY |
| D50-20 | P10 | 预检taskRef=A，permit改taskRef=B/approvedModel不同 | Core HMAC版本3比较拒绝且没有新采购/模型出站 |
| D50-21 | P10 | 两管理员互相并发审批同Employee/Agent/Task | 唯一ACTIVE slot，禁止自己审批，旧任务预留不无证据释放 |
| D50-22 | P11 | TEAM reader指定无归属GPU月费或跨Team统计 | 400 UNSUPPORTED_SCOPE_METRIC /403，无公司总费用外泄 |
| D50-23 | P11 | sourceManifest缺一Event、重复MQ、审核费用更正 | 未补齐UNKNOWN/DELAYED，补齐后build隔离不双算、不会假0 |
| D50-24 | P11 | FinOps down、Core真实pending-exposure仍正常 | Core独立200返回已知风险；分析503或UNKNOWN而非0 |
| D50-25 | P12 | Redis/MySQL失联后尝试recovery-review无证据 | fail-closed，不可由单Gateway内存状态重开全公司收费 |
| D50-26 | P12 | Gateway SIGKILL中断SSE/Provider不确定出站 | 继续保留财务未知风险，不向Provider自动第二次派发 |
| D50-27 | P0+各Phase | 同一Idempotency-Key跨v1/v2/v3及新Core旧Gateway混部 | 合法默认映射保留原Invocation且无二次派发；真实改变资金/模型/任务则409；Core向后兼容先于Gateway |
| D50-28 | P0 | 在V7 SQL规范中检查 canonical_schema_version、payload_hmac_key_id、CHECK和V3 ProviderCredential四列 | 静态存在只记DESIGN_REVIEWED；真Flyway/Testcontainers前绝不记PASS |

**证据/阶段标记**：D50-01～28均**NOT RUN**。D50-12/13是真Core Keyring+MySQL/多进程集成测试，不可用纯Java字符串拼接比较宣称真实安全通过。D50-19/20涉及本轮新增成对 TaskRef Header和SchemaV3，必须与P10 Gateway白名单、Core HMAC和V10快照同时验收。P6 RuleV1安全扫描实现与P7真实Provider计费上界仍有专门冻结阻塞；本矩阵说明该怎么验证而非声称已经证明。




## 4. 业务验收场景组 3


> **重要**：以下编号是交给本地AI在相应Phase实现的**测试契约**，不是实际测试结果。全部测试必须产生可复现GitSHA/容器版本/命令/环境/断言日志，财务/安全场景使用FakeProvider、FakeDNS和随机生成的测试Key；没有供应商批准/账单不可说“真实计费已通过”。

| Test ID | 对应Phase | 场景/注入 | 必须的DB/网络/安全断言 |
|---|---|---|---|
| D52-01 | P3 | AdapterCompatibility缺tokenizer/evidenceRef/capabilityRevision不匹配 | 创建DRAFT受控校验拒绝或不能激活；不可由Gateway任意YAML替代DB受信配置 |
| D52-02 | P7 | 输入含role模板开销/Emoji/Unicode，多种不可信“字符数=Token”算法 | 仅已验证模型Tokenizer完整请求可形成风险上界；缺证明422、HTTP Provider0 |
| D52-03 | P7 | max_tokens未强制、Provider有工具/推理额外计费或multiModal | 拒付费出站422 BILLING_BOUND_UNVERIFIABLE，不添加虚假零费 |
| D52-04 | P7 | 正常usage prompt=100、completion=25、cached=20 | cached计入prompt子集，不叠加，总token=125，冻结双价独立用BigDecimal |
| D52-05 | P7 | usage缺字段、负数、total不等prompt+completion、remoteModel身份不同 | UNKNOWN/PENDING，不SETTLED为0、不泄漏供应商完整Body |
| D52-06 | P7 | SSE前半完成但final usage帧丢失/晚到 | SSE结束文本和费用状态分离、usage=null、保留原资金风险 |
| D52-07 | P7 | Provider连接前明确零出站 vs 写入后无响应/504 | 仅前者PROVEN_NOT_SENT合法RELEASE；后者PENDING且Provider最多一发送 |
| D52-08 | P7 | 供应商真实外币/POINTS超上界 | 真实异常OVERRUN记录并冻结公司对应采购币种，不使员工桶突破CHECK |
| D52-09 | P7 | 原Report ACK丢失/重复不同digest/密钥轮换 | 同reportOperationId+digest只结算一次；不同digest409；绝不二次Provider |
| D53-01 | P7 | 两Gateway并发ENQUEUE、同Invocation重复、Pool maxWaiting=1 | 持久Invoice唯一、Redis一个ticket、队列不会超maxWaiting、不保存原Prompt |
| D53-02 | P7 | 并发PROMOTE N次超过maxActive/rpmLimit | 所有Lua原子、严格≤最大；过去60秒滚动RPMLimit内无多余授位 |
| D53-03 | P7 | 伪造owner/leaseToken/epoch，Core独立INSPECT | 不能得到合法Permit、DB FinanceReservation不变、FakeProviderHTTP=0 |
| D53-04 | P7 | Gateway PROMOTE ACK丢失、Gateway SIGKILL/内存Prompt丢失 | 不再生成第二发送租约/重播Prompt；异常UNKNOWN与财务风险保留 |
| D53-05 | P7 | Redis冷重启后RDB恢复旧ACTIVE/guard；runId改变 | 新Permit fail closed、直到Core审核升Pool admission_epoch；不能因为Redis新计数0超配GPU |
| D53-06 | P7 | Provider SSE客户端取消但本地GPU任务未确认停止 | physicalActive仍保守隔离，不能TTL回收产生超卖 |
| D53-07 | P7 | Redis中断后FinOps/Core result可写 | 旧有可信usage可安全结算；仅禁止新Admission，不丢账 |
| D53-08 | P9 | LOW入队无批准fallback与非LOW阶段恶意改serviceClass | 全部拒；NORMAL额度变化不能盲目生成第二LOW Provider发送 |
| D53-09 | P10 | 3队列持续饱和调度千次与临时某档空队列 | 授位机会长期近4:2:1、任何持续等待合法类有限7槽被选择、不抢占ACTIVE |
| D54-01 | P11 | 真Grant发行+独立员工使用、跨Team收Grant | 发行归issuerTeam一次，实际用量归usageTeam快照，不能把发行+消费总和叫公司总费用 |
| D54-02 | P11 | Outbox同事务提交后RabbitMQ停机 | Core真实钱仍正确且Outbox待发，恢复后原eventId到FinOps，不丢已确认事实 |
| D54-03 | P11 | Broker confirm成功Core更新Outbox失败、消息重复投递10次 | 同buildId Inbox仅一条，同Fact/aggregate单次金额，ACK在本地事务之后 |
| D54-04 | P11 | Publisher mandatory return/queue不存在/confirm NACK | PUBLISHED绝不能成功标记，retry隔离/告警且Core财务不回滚 |
| D54-05 | P11 | payloadDigest hex64与Inbox binary32不对应/非法enum/Unknown schema | 消费拒绝/隔离DLQ、不按0构造Fact，不暴露Provider密钥 |
| D54-06 | P11 | Provider成本USD与另笔EUR、POINTS与TOKENS混入 | 只能单位/币种分别聚合，没有隐式汇率或货币虚拟总和 |
| D54-07 | P11 | 更正事实负delta、重放旧revision、历史转Team、运营费用VOID | append-only新revision，原businessDate旧归属，buildId隔离不二次加总 |
| D54-08 | P11 | Core SourceManifest<=5000漏一个event，FinOps补拉同eventId | 先UNKNOWN/DELAYED，可信SourceEvent复核digest并投影后才可FRESH(asOf) |
| D54-09 | P11 | Manifest窗口超过5000/SourceEvent丢失/本地审批真实账单未到 | 不将不完整窗口标FRESH，也不能读0当真实完整成本 |
| D54-10 | P11 | Building重建期间持续新消息、两build同消息、切换瞬间失败 | Active继续可读、Building独立Inbox、只有完整验证才CAS切Build，旧build证据保留 |
| D54-11 | P11 | 非Admin尝试录GPU费用、本人approve、Fake租金无票据 | 403/409，FinOps不能生成真实OPERATING_COST事实，Core采购不变 |
| D55-01 | P12 | 首次Core V11 migrate与seed | 唯一singleton id1 ADMISSION_BLOCKED、Core V1～V10历史不丢、FinOpsV1独立 |
| D55-02 | P12 | 两Core+两Gateway，管理员发admission-block与正在提交Permit竞态 | MySQL singleton锁决定先后；BLOCK先提交新Permit全部拒，无未经授权Provider网络 |
| D55-03 | P12 | 同Actor+Idempotency-Key重复/不同Body，V11 AdminAudit UNIQUE约束 | 相同操作只变化一次epoch/version+一次审计；不同Body409，不回显运营HMAC |
| D55-04 | P12 | 恢复缺readinessEvidenceIds、过期假Probe、Redis runId不符 | 仍BLOCKED/RECOVERING，没有SERVING；Gateway旧缓存不放行 |
| D55-05 | P12 | 成功恢复后两Core/Redis epoch不一致，逐Pool真实可用 | Global与Pool两轴全部有效才可能Permit，恢复只一次提升global epoch |
| D55-06 | P12 | 从旧MySQL备份恢复但Provider曾真实出站/旧Outbox缺失 | 启动全局阻塞，未知费不清0，人工核对才能恢复 |
| D55-07 | P12 | FinOps/RabbitMQ down但Core资金/Outbox正常 | FinOps报告UNKNOWN/DELAYED，Core合法请求可继续、pending暴露不变 |
| D56-01 | P0静审/P7 | 同RuntimeKey/请求正文重新发、缺temperature与显式1.0、top_p同义 | 相同请求规范HMAC等价但不得第二Provider发送；错误body D33四字段、InvocationId只安全Header |
| D56-02 | P0静审/P9/P10 | 相同幂等键跨schemaV1/V2/V3，额外fallback/task header | 非授权版本拒绝升资金类，Core缓存已失效的KeyId failclosed，不重派发 |
| D56-03 | P0静审/各Phase | 七文档和迁移表归属检查（旧61、P12可选表、Gateway算HMAC等） | D56唯一口径胜出；历史草案明确不参与生成源码 |
| D56-04 | P0兼容PoC | MySQL8.4 Flyway Core V1～V11/FinOpsV1完整迁移；Java21+Boot4 Gateway MVC+MP+Redis/RabbitMQ依赖 | 真实PASS/FAIL/BLOCKED，不能凭Docs SQL文本或Git提交标PASS |

**案例编号D52-01～D56-04全部NOT RUN**。对应P7/P11/P12真实集成/性能证据必须在相应Phase出现后提交，而P0文档门禁要求测试规格、数据/错误/权限合同完整。用户明确要求**具体项目源码由本地AI负责**，当前总控只负责这些验收和技术决策，不代替执行AI生成测试类/SQL文件。

### 全阶段实际验证时必须记录的指标

真实测试报告按 `gitSha,phase,migrationVersion,jdk,boot,redis,mysql,mqImageDigest,caseId,command,passed,failed,skipped,providerHttpAttempts,reservedPoints,settledPoints,pendingCurrency,redisEpoch,coreAdmissionState,finopsAsOf` 留下可重现证据；未来Phase未出现的指标允许标N/A，绝不记0冒充它们已经被检查。首次正确状态均NOT_RUN，静态审查单独标DESIGN_REVIEWED。


### D56设计终审补充竞态测试（NOT RUN）

| Test ID | 归属 | 测试断言 |
|---|---|---|
| D56-05 | P7 | Redis Lua不能调用INFO获取runId；Gateway/Core各自从受信主连接读取真实runId并比对Guard；INFO和INSPECT之间强制failover返回UNCERTAIN、拒绝新Provider出站 |
| D56-06 | P12 | 两个Core Permit并发通过InnoDB `FOR SHARE`锁定读取同一个SERVING singleton不彼此串行；同时管理员BLOCK `FOR UPDATE`排他等待旧短事务 |
| D56-07 | P12 | BLOCK先提交则后续Permit拒绝；Permit已有共享锁先提交则其出站风险合法保留，BLOCK不能追溯删除已批准Intent；DB锁超时不能重复发Provider |

D56-05～07当前全部NOT RUN，必须有真实Redis INFO/主切换与MySQL锁实测证据。



## 完成条件

异常输入/越权/幂等/账务竞态/超时/重启不得仅用模拟UnitTest代替必要真实MySQL/Redis/MQ/两进程E2E。未实际运行前一律NOT_RUN；原草案描述中的阶段归属若与[Roadmap](../08-roadmap.md)有差异，以Roadmap和最新Database/API规范为准。
