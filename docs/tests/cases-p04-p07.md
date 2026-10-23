# P4～P7资金、Grant、治理及首次真实调用测试

> 所有案例都是实施/验证规格，**没有代码运行证据，全部NOT RUN**。按首次出现的真实Phase执行；静态设计审查和真实数据库/网络测试不是同一种证据。

## 1. 本册适用范围

实现时必须对照[测试总策略](../09-test-plan.md)、[Phase任务](../phases/README.md)，每个case记录Git SHA、命令、镜像版本、passed/failed/skipped、实际Provider出站计数及未知财务风险状态。

## 2. 业务验收场景组 1


这些用例要求**真实 MySQL8.4 Testcontainers、Core DB 事务/行锁**；P7 额外需要两个真实可停机 Gateway/Core 进程、Redis Lua、可设置应答/ACK 丢失及统计 Provider 实际 HTTP 次数的 Fake Provider。不得用 Mockito 把分布式超时、数据库 FK/锁、Redis 原子脚本冒充已验证。复用 BUD/GRT/GOV/PROT/RPC/MSC/SYS 原有矩阵，新编号只是针对 P4～P7 新详细数据合同的定量补充。

### P4：BIZP4-01～12（真实资金，不允许公开模拟任意扣费 API）

| ID | 初始事实 / 故障 | 必须断言 |
|---|---|---|
| BIZP4-01 | P1～V3 升 V4、重新 migrate | P4 十表及约束只迁一次；P1～P3 数据/Key/ACL 保留；Gateway 无 DB 权限 |
| BIZP4-02 | 员工 A AgentX10、Employee周20、USD总0.10/NORMAL0.08，可信风险25Points/0.03USD | 同一事务 Agent10+Employee15 reserve、Provider总+NORMAL各0.03，真实采购风险只计一笔 |
| BIZP4-03 | 可信 usage 实际12Points/0.015USD | 使用 Agent10+Employee2、释放Employee13；Provider总/份额仅 settle0.015、release0.015；Ledger+Outbox 关联同 operation |
| BIZP4-04 | 两并发 operation 各消耗剩余可用 > 账户值 | 至多一个成功，任意提交状态 used+reserved<=limit；没有超支 Provider 许可 |
| BIZP4-05 | POINTS够而采购NORMAL不足，或采购够而积分不足 | 两账及 Ledger/Outbox/Allocation 全部无部分成功 |
| BIZP4-06 | 相同 operationKey 并发/响应丢失再提交不同 request digest | 不重复 reservation/settle；冲突409，不能把其他 Owner 账务详情泄漏 |
| BIZP4-07 | SELF_HOSTED 常规需10Points、不按 Token 采购 | 只写 Points/Allocation，无 ProviderSpendReservation、无“0 USD账单” |
| BIZP4-08 | 周日23:59准入，周一09:00结算/释放 | 原周桶发生 settle/release，新周可用不凭空增加；公司按出站月的原币种预算归属 |
| BIZP4-09 | 不配置该 Agent 周政策/货币预算、非法币种/负限额/科学计数超精度 | 400/422/409可控拒绝，不假设无限预算、无出站 |
| BIZP4-10 | 有缓存Token不能证明输入费用上界，Provider支持额外未计价类别 | 422 BILLING_BOUND_UNVERIFIABLE，全部风险0、Provider次数0 |
| BIZP4-11 | 可能已向 Provider 收费却无 usage、或客户断连 | 状态 PENDING_RECONCILIATION，原周/月双预留保留，不能 RELEASE |
| BIZP4-12 | 可信 usage 大于冻结上界、公司修改月限额到小于已占用 | 风险异常 OVERRUN/RISK_FROZEN 可核对且拒绝降低预算；不静默负余额/隐藏采购费用 |

### P5：BIZP5-01～12（发行与消费双事实）

| ID | 初始事实 / 故障 | 必须断言 |
|---|---|---|
| BIZP5-01 | V4→V5 迁移 | 三新表，原 finance_operation/ledger/账户完整可迁移；GRANT Allocation可用 |
| BIZP5-02 | Team5 Pool1000，Manager5给 Team6 EmployeeB 发100 | Team5 issued=100、available=900；Grant=100；TEAM_GRANT_ISSUED仅一条，issuerTeam5 |
| BIZP5-03 | Team5 Manager试图发 Team6 Pool，或普通员工给自己发行 |403，Pool/Grant/Audit无成功更改 |
| BIZP5-04 | 同 Idempotency-Key 重复请求，第一次响应丢失 | 只有一条 Grant/receipt/Ledger/Outbox，重复409不重复扣 Pool |
| BIZP5-05 | 两 Manager 同时发行700+600，Pool1000 | 不得成功1300；总 issued<=1000，事务竞争可复现 |
| BIZP5-06 | Agent周10+个人周5+Grant100，请求最高40 | Allocation Agent10/Employee5/Grant25，源总和40 |
| BIZP5-07 | 存在两 Grant expiresAt不同/相同 | FEFO：最早失效优先，时间相同 id最小 |
| BIZP5-08 | 已 Reserve 的 Grant 结算前到期 | 仍按原 Grant 正确 settle；release余额不可供新调用也不返 issuer Team |
| BIZP5-09 | Grant 到期后开始新的 Reserve | 不使用此 Grant，即使物理 balance 数字还有额度 |
| BIZP5-10 | Grant 发100，员工只实际消耗30，剩70到期 | TeamPool issued依旧100；FinOps 发行100 vs 员工使用30为两种口径 |
| BIZP5-11 | revoke 已存在 Grant 时有 UNKNOWN 账单 pending | 新 Reserve 禁止，既有 Reservation/原发行账不被删除/退款 |
| BIZP5-12 | Team Manager/Employee 分页查看发行与领取信息 | issuer/recipient 不因员工转Team改写；分页 total 无别Team明细 |

### P6：BIZP6-01～12（出站安全）

| ID | 场景 | 必须断言 |
|---|---|---|
| BIZP6-01 | V5→V6 与治理角色迁移 | policy七类表/RoleAssignment 受限 role_code 可用，默认无发布策略 fail closed |
| BIZP6-02 | POLICY_EDITOR 提交草稿后同人用 SECURITY_APPROVER 权限自审 | 拒绝自审，状态仍未获批准；审计仅记录安全事实 |
| BIZP6-03 | 不同审批人审核/发布，两个 Admin 并发以同 activeRevision CAS 发布 | 最多一个 active 指针成功、旧版不可变，另一409 |
| BIZP6-04 | 所有消息第1条公开，最后一条包含 RESTRICTED 或真实密钥模式 | 扫完整 messages，hard deny，无 Redis/Provider 进入 |
| BIZP6-05 | 客户端自称 PUBLIC，服务端业务标签 CONFIDENTIAL | 不能降级分类，禁止 ThirdParty |
| BIZP6-06 | INTERNAL 发第三方，Agent 没 Provider/region 审批 | 403，不因 AgentModel GRANTED 变成出站许可 |
| BIZP6-07 | CONFIDENTIAL 发未批准 SelfHosted 或 stream=true | 403/422，无外部发送；批准 SelfHosted buffered JSON 才允许候选 |
| BIZP6-08 | RESTRICTED/硬凭证带 EMERGENCY/LOW/管理角色 | 同样禁止，模型调用次数0 |
| BIZP6-09 | 治理例外只针对 exemptible 的误报，审核人与申请人不同 | 允许限定范围/期限的例外，不允许豁免硬机密或可信等级 |
| BIZP6-10 | PREQUEUE旧版允许，WAITING中发布更严格版本 | PREDISPATCH重新扫描并拒绝，资金/Permit/Provider均0 |
| BIZP6-11 | 终态 Permit 事务和政策发布锁竞争 | 数据库锁顺序决定先后；新政策已提交后启动许可不使用旧版权限 |
| BIZP6-12 | Scanner timeout、规则损坏、Redis/服务端日志收集、机密输出 Review拒绝 | fail closed/安全审计不含 Prompt/密钥；若已生成输出，真实usage仍结算或待对账 |

### P7：BIZP7-01～16（真 HTTP/SSE 与异常）

| ID | 场景 | 必须断言 |
|---|---|---|
| BIZP7-01 | V6→V7，启动 Gateway+Core+MySQL+Redis、Fake Provider | Invocation/Permit/Receipt/Case/Pool表存在，真实 HTTP 管理/模型分别归正确服务 |
| BIZP7-02 | 两 Key+双 ACL+策略+积分/采购预算充足，stream=false | Fake Provider真实接收1次，JSON响应model为LogicalModel，可信usage按冻结双价表 settle |
| BIZP7-03 | stream=true，FakeProvider分段多次flush+最后usage和DONE | 客户端确实渐进收到数据，正确UTF8/SSE帧，财务状态不以DONE冒充SETTLED |
| BIZP7-04 | 任一 agk_/empk_ 缺失，EmployeeAgent或AgentModel撤销 | 401/403、Provider0；不使用 Gateway 自报员工ID |
| BIZP7-05 | RESTRICTED/CONFIDENTIAL stream、未批区域、Provider Secret缺失 | 403/422/503，Provider0、积分/采购不占用 |
| BIZP7-06 | 公司 USD NORMAL 不足，即使 Employee积分充足 | 资金事务整体回滚，Provider0 |
| BIZP7-07 | Redis WAITING 超时/满、ACTIVE lease失效、两个网关同时争名额 | 不突破真实共享容量，WAITING不占资金，租约不属于请求则 Core拒绝Permit |
| BIZP7-08 | Preflight Core已提交 Invocation但响应丢失 | 相同幂等键找回原安全ID或409；无第二 Invocation，Provider0 |
| BIZP7-09 | Core Permit已提交双账和唯一Intent但回复Gateway丢失 | 只有一份 Permit/Reservation，Gateway 不通过state查询重播上游，FakeProvider0 |
| BIZP7-10 | 首次permit成功，Provider已收到一次后网络超时/客户端重连 | 不会第二次向原或其他 Deployment发送；双账 pending直到可靠对账 |
| BIZP7-11 | Provider成功usage已到Gateway，Core result ACK丢失 | 相同 reportOperationId+证据重报，Ledger/Outbox/结算一次，上游仍只调用1次 |
| BIZP7-12 | 同 reportOperationId 改usage/价格或报告负数 |409/受信证据失败，Core不得根据 Gateway 自报任意金额改总预算 |
| BIZP7-13 | Provider流式中断、usage尾帧丢失、错误200 body、上游结算失败 | execution/billing两轴正确分离，无可信usage仍 PENDING，不当作0 |
| BIZP7-14 | 下游SSE慢消费者/取消/全程超时 | 有界线程/缓冲、尝试取消/关闭连接，资金仍取真实证据/UNKNOWN保留，不双发送 |
| BIZP7-15 | Gateway在Provider可能发送后SIGKILL，Core/Redis分别重启 | Core恢复原财务/唯一Permit，Redis租约恢复不代表GPU已停止，人工对账可查；不自动重发 |
| BIZP7-16 | 相同LogicalModel两Deployment（一个ThirdParty一个SelfHosted），出站前禁用一条 | 真正被允许的最终部署及自己的币种/容量策略；用户POINTS价一致，不跨逻辑型号偷偷切换 |

**固件矩阵**：公司时区周日→周一、月底→次月；EmployeeA/B、AgentX/Y、ManagerTeam5、两Grant不同到期；ThirdParty Fake 收费 USD 和 SelfHosted Fake，明确不同 Provider remoteModelName；一个Fake的include_usage尾帧可取消、一个可模拟费用未知；Core/Gateway网络注入“已提交但ACK丢失”与受控 SIGKILL。每个测试不仅验 HTTP，还要查询真实 Account/Allocation/Permit/Outbox/审计与 Provider真实接收计数。旧编号 BUD/GRT/GOV/PROT/RPC/SYS 仍要回归；任何一个方案不能以“新增用例通过”替代前序更完整的验收。

**全部 NOT RUN**：存在的是验收设计，不是测试类、报告、真实迁移或已经跑通的业务。P0 冻结前还需要每个用例的 SQL/JSON 固定输入、迁移检查、请求与响应断言、可复现故障注入脚本以及 Maven Testcontainers 集成结果。只有经过真实 Review/测试门禁、用户逐 Phase 确认才能推进。



## 3. 业务验收场景组 2


| ID | 真实执行步骤 | 必须断言 |
|---|---|---|
| FZAPI-04 | P11 FinOps Inbox故意缺一条Core Manifest列出的事件，按 eventId 使用受限内部SourceEvent接口补取；与此同时RabbitMQ重投 | Core仅svc_finops_core可读经白名单净化的事件负载；Digest与 manifest一致，FinOps按buildId写Inbox/Fact/Aggregate只一次；缺失/不一致时状态UNKNOWN/DELAYED而不是成功0 |
| FZAPI-05 | P7 Preflight的messages内容/temperature为A，dispatch-permits改成B，Gateway根本不知道Core HMAC secret | Core独立重新计算HMAC并常量时间比较，409 REQUEST_PAYLOAD_CHANGED，不能成功Reserve或创建Permit，Provider接收次数0 |
| FZAPI-06 | P7相同真实DTO消息但更换JSON对象字段序，省略/显式给相同默认temperature；null温度/额外未知字段 | canonical DTO应固定Digest而非原JSON字节排序；非法null/未知字段400、正常重放不创建新Invocation |
| FZAPI-07 | Gateway调用Core permit得到网络504，但Core真DB已提交Permit；随后查询dispatch-state | 状态仅可查询，不返回attemptToken，不允许凭Committed状态重新发Provider或二次选Provider；原风险pending或待结算 |
| FZSQL-32 | Core真实MySQL按V8→V9迁移，P7既有普通Invocation，再做LOW模型保底 | V9 ALTER实际成功，旧Invoke无损读取；新LOW保存realActualModelId/fallbackPolicyId/revision/fundingMode且归属有效；P9两型号间不自动重复派发 |
| FZSQL-33 | Core真实MySQL按V9→V10迁移，旧LOW/NORMAL仍存在，新Emergency申请批准并真实派发 | V10 ALTER成功、旧行兼容；新Invoker快照授权ID+TaskRef与当前Employee/Agent/Model/窗口匹配；过期/越权不产生新Permit |
| FZRPC-01 | D16旧apiVersion/maxTokens与D42 schemaVersion/model/max_tokens混用 | 未知别名严格400 INVALID_INTERNAL_REQUEST，不默默转换为另一种计费规则 |
| FZRPC-02 | Provider result ACK丢失后重报相同reportOperationId/evidenceDigest但没有再发任何Provider HTTP；改证据重报 | 旧报告幂等只一次Ledger/Outbox、修改后的报告409 REPORT_EVIDENCE_CONFLICT；运行时execution与billing状态轴保持独立 |

**检查进度**：上述仅为文档里的测试定义，没有Java Test类、MySQL执行、Docker/RabbitMQ运行证据。执行AI不能用Mockito重复Mock两边协议来证明内网权限、MySQL原子性及网络ACK丢失，必须跑真实两服务/三服务与FakeProvider断包场景。



## 4. 业务验收场景组 3


| ID | Phase | 输入/故障 | 必须断言 |
|---|---|---|---|
| D51-GOV01 | P6 | Policy缺可信下界规则、缺硬密钥规则、缺Egress8格任一 | 400 INVALID_GOVERNANCE_POLICY，不可发布 |
| D51-GOV02 | P6 | 上传未知ruleCode、任意regex、非法参数、超64条 | 400；无任意代码执行、无越过安全默认 |
| D51-GOV03 | P6/P7 | 无可信标签、客户端写PUBLIC、真实Core标签CONFIDENTIAL | 默认至少INTERNAL；真实CONFIDENTIAL不可发第三方，stream只能拒绝 |
| D51-GOV04 | P6/P7 | 全messages的第一条/最后一条/assistant中植入高置信mgt_等凭证 | hardDeny，FakeProvider计数0，日志/审计无原文 |
| D51-GOV05 | P6 | 经审批marker误报例外，同时有RESTRICTED来源标签或另一硬密钥命中 | 仍DENY；例外不能降低服务端可信分级 |
| D51-GOV06 | P6/P7 | Scanner超预算、异常、UTF8错误、总Body超64KiB | fail closed；未储存截断后原文，资金预留0 |
| D51-GOV07 | P6/P7 | CONFIDENTIAL经批准SELF_HOSTED、stream=false、输出匹配凭证或超过256KiB | 不向客户端返回输出，但保持真实usage或PENDING账务，不假免费 |
| D51-GOV08 | P6/P7 | Policy刚发布/撤销与Permit并发 | MySQL activeRevision最终锁定复核，旧本地缓存不能覆盖新安全指针 |
| D51-GOV09 | P0/P6 | Marker敏感词库真实生产配置未知、管理员未批准 | 仍仅设计/示例数据；不能自称达到企业完整DLP合规 |

所有D51-GOV01～09测试**NOT RUN**。确定性规则存在漏报；测试通过也不构成「识别所有企业秘密」的安全声明。




## 完成条件

异常输入/越权/幂等/账务竞态/超时/重启不得仅用模拟UnitTest代替必要真实MySQL/Redis/MQ/两进程E2E。未实际运行前一律NOT_RUN；原草案描述中的阶段归属若与[Roadmap](../08-roadmap.md)有差异，以Roadmap和最新Database/API规范为准。
