# AIGate-Rebuild 完整开发设计说明书

> 文档版本：1.0（2026-10-23） · 状态：P0 DESIGN_REVIEWED / NOT_FROZEN  
> 用途：交付给项目负责人、Code Reviewer 和本地执行 AI 的**统一阅读入口**。本说明书将完整业务闭环、应用边界、接口/数据权威、阶段依赖与验收纪律串成一个可以从头阅读的开发合同。详细字段、DDL、请求响应和失败矩阵以所链接的专题规范为唯一权威，不在本页重复维护第二套字段。
>
> 重要：设计检查不等于运行通过。Java、Spring Boot、MySQL、Redis、RabbitMQ、Provider 的真实兼容或故障测试尚未执行。P0 未获用户最终冻结批准，P1～P12 均未启动编码。

## 1. 建设背景、问题及目标

企业同时使用第三方 AI API 和自部署推理。若员工各自配置供应商密钥，就无法统一回答：是谁发起请求、Agent 是否获得授权、数据能否发往指定地区、共享 GPU 是否有容量、积分和采购预算由谁承担、失败或丢失 Usage 后是否仍会收费。

AIGate 提供统一 Chat JSON/SSE 入口，把 Employee 与 Agent 双身份、LogicalModel 与实际 Deployment、数据治理、公司采购成本、员工积分、租约、真实出站和事后核对串成一条有持久证据的链路。

**交付目标**：端到端可运行；失败时拒绝未授权调用而不重复付费；Core 保留真实资金/许可事实；审查者能通过请求、数据库记录及测试重放解释每笔调用。产品不实现 Agent 推理循环、RAG、多模态编排、财务支付、任意脚本执行或自主训练调度。完整功能与非功能编号见[需求规格](./02-requirements.md)。

## 2. 角色、信任边界与操作权限

管理者使用 mgt_ 管理凭证，经 Gateway 精确 Method/Path 路由进入 Core，Core 重新查询当前身份、角色、团队及资源范围。PLATFORM_ADMIN 管理组织及平台配置；TEAM_MANAGER 仅管理受权 Team；POLICY_EDITOR 提交治理草稿；SECURITY_APPROVER 独立审核；FINOPS_READER 按 GLOBAL/TEAM 授权读取报告。Bootstrap bst_ 只供受控初始化/恢复，不是普通管理令牌。

真正运行时必须同时具有 agk_（Agent）和 empk_（Employee），且 Employee→Agent 与 Agent→LogicalModel 两类权限均有效。Gateway 不拥有最终授权权威；svc_ 内部身份只能访问限定内部 RPC，不得冒用用户身份。密钥只显示首次签发明文，数据库持久化安全摘要或加密密文，日志及审计不含 Prompt、完整 Key 或 ProviderSecret。

规则原文和检查顺序见[需求](./02-requirements.md)、[API总则](./06-api-design.md)、[内部RPC](./api/internal-rpc.md)与[Gateway白名单](./api/gateway-routes.md)。

## 3. 物理架构与技术选择

| 初次上线阶段 | 进程/存储 | 主写责任 | 不得承担 |
|---|---|---|---|
| P1 | aigate-gateway | 公开入口过滤、转发，P7 起长连接与一次 Provider 出站 | 不查写 Core MySQL、不持有资金权威 |
| P1 | aigate-core + core_db | Team、身份、权限、目录、治理、双账户、账本、唯一 Permit、Outbox | 不在数据库资金事务中做 Provider HTTP/SSE |
| P7 | Redis | 短期 Pool 排队、租约、RPM 及 runId/epoch Guard | 不证明物理 GPU 已停止、不代替账本 |
| P11 | RabbitMQ | 至少一次异步传递 Core 已提交的事实 | 不提供跨数据库事务 |
| P11 | aigate-finops + finops_db | Inbox、事实读模型、聚合、源 Manifest、受控运营成本 | 不修改 Core 额度和真实财务操作 |

目前选择 Java21、Spring Boot4.1.1、Spring Cloud Gateway Server WebMVC、Spring MVC/Core、MyBatis-Plus、MySQL8.4、Flyway、Redis8.4 与 RabbitMQ。**这些是待兼容 PoC 核验的设计基线，不是已经运行通过的版本组合。** 早期就拆 Gateway/Core，是基于公开网络长连接与内部权限/资金信任边界，而不是按 CRUD 域拆出很多微服务；P11 再因只读分析隔离而增加 FinOps。选择原因、替代方案和代价见[架构说明](./03-architecture.md)与[ADR](./adr/README.md)。

## 4. 领域对象及持久化划分

业务关系可以沿以下方向理解：

1. **组织与身份**：Team、Employee、RoleAssignment、ManagementCredential；Agent、AgentApiKey、EmployeeCredential 和两类 ACL。
2. **模型目录**：Provider → Model/LogicalModel → Deployment；发布不可变 POINTS 单价和实际采购币种价格；ProviderSecret 由 Core 安全保存。
3. **资金和容量**：员工/Agent 周积分、公司月采购预算、TeamPool/Grant、QuotaReservation、不可变 Ledger；CapacityPool 对应一组真实共享容量而非任意虚构容量。
4. **治理和出站**：四级数据分级、当前发布 Policy、独立审核、Runtime Invocation、FinanceOperation、唯一 DispatchPermit、ResultReceipt 和 ReconciliationCase。
5. **分析**：Core 同事务 Outbox → RabbitMQ → FinOps Inbox/Fact/Aggregate/Manifest；事实可以延迟，但缺失不能假定为 0。

Core V1～V11 合计 **55 张表**；P11 独立 FinOps V1 **7 张表**；全设计合计 **62 张表**。它们是完整规划，而非 P1 的初始化脚本。P1 只建 V1 的 6 张表，之后按 Phase 顺序迁移。所有表、索引、唯一约束、外键、CHECK、ALTER 和 SQL 版本入口见[数据库总规范](./05-database-design.md)。数据库版本文件是设计 SQL，真正 Flyway 迁移及实际 MySQL 检查由对应 Phase 交付。领域所有权及不可变条件见[领域模型](./04-domain-model.md)。

## 5. 单次 Chat 调用的端到端流程

| 步骤 | 责任方 | 正常路径产物 | 失败纪律 |
|---|---|---|---|
| 1. 接收 | Gateway | 严格 JSON、双 Key、Idempotency-Key、请求 ID | 未知字段、不支持参数直接拒绝；绝不转发内部 URL |
| 2. Preflight | Core | 验证身份/两 ACL/治理条件，写受信 CanonicalDTO 版本与 HMAC 元信息 | 不能使用客户端自报分级、价格或许可证 |
| 3. 排队与准入 | Gateway + Redis，Core 复核 | 与真实 Pool/runId/epoch 绑定的 ACTIVE Lease | Redis TTL 到期不证明上游任务停止 |
| 4. 资金和许可事务 | Core MySQL | Invocation、双账户 Reservation、FinanceOperation、**唯一 Permit** 一次提交 | 凭当前授权、价格版本、容量 Guard 校验；资金不足/UNKNOWN 上界拒绝 |
| 5. 实际外发 | Gateway | 按 Permit 使用短时 ProviderSecret 发出至多一次 HTTP，代理 JSON/SSE | ACK丢失、504或已可能发包后不得透明二次发送 |
| 6. 结果和结算 | Core | 可信 Usage → SETTLED 和 Ledger；不确定 → PENDING/核对风险 | 不可信 Usage 不当作0；不能丢失未证明费用 |
| 7. 下游分析 | P11 起 Core → FinOps | Outbox、Inbox、Fact/ReadModel 与 Manifest | 重复事件幂等，缺事件标 DELAYED/UNKNOWN |

首 Token 前即使有可重选路径，也必须符合唯一 Permit/不会二次收费的严格条件；首 Token 后不进行透明跨模型重放。消费者断开要尝试取消上游，但物理执行和外部账费仍可能延续。P8 才增加同型号健康路由；P9 只有经同意/已批准才允许 LOW；P10 才有两个不同角色审核的紧急授权。这些新增能力不绕过安全、预算或一次出站边界。具体请求版本、SSE 帧、Redis 原子协议、可信 Usage、恢复与 MQ Schema 见[运行协议索引](./07-runtime-protocols.md)。

## 6. 接口合同及错误纪律

公开接口只由 Gateway 精确 method+path allowlist 进入；管理接口均回 Core 重新鉴权。P7 的唯一聊天入口为 POST /v1/chat/completions，调用查询为 GET /v1/invocations/{id}；内部 /internal/** 只能经受信服务网络使用。API DTO 以 camelCase 表示、分页 page 从 1 起且 size 不超过 100，POINTS/采购金额使用精度固定的十进制**字符串**，时间为 UTC ISO 8601；禁止把管理实体、DB 实体或原始 ProviderSecret 直接序列化给客户端。

修改资源若以 expectedVersion CAS 保证竞争顺序，旧版本重放为 409；若承诺相同 Idempotency-Key 重现安全业务响应，就必须拥有真实唯一操作收据，不能把 CAS 假装成强幂等。错误使用统一 error.code/message/requestId/retryable 结构。按阶段的字段/权限/状态/请求响应见[API总规范](./06-api-design.md)以及 P1～P3、P4～P6、P7～P12 分册；服务 RPC 单独见[内部RPC](./api/internal-rpc.md)。

## 7. 关键不可越过的不变量

| 分类 | 强制业务结论 |
|---|---|
| 数据治理 | PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED 不允许客户端降低真实分级；无合法发布的 Policy 不能当作放行 |
| 密钥安全 | agk_/empk_/mgt_/svc_ 的角色、用途和网络边界不得互换；ProviderSecret 不可回显 |
| 定价与财务 | POINTS 与原币种采购分账；不同币种不自动求和；资金扣减、Permit、Ledger、Outbox 必须符合 Core 同库事务语义 |
| 唯一外发 | 一个 Invocation 只能有一个持久许可；网络超时不能以不确定状态重试导致重复付费 |
| 容量 | Redis 短期 lease/runId/epoch 与物理 GPU 状态不同；冷恢复先隔离未知任务 |
| 异步事实 | RabbitMQ 至少一次，FinOps 依靠 Inbox、Manifest 和读模型构建去重核漏 |
| 全局恢复 | P12 Core V11 单例持久 BLOCK/RECOVERING/SERVING；Block 提交后不产生新 Permit，但不伪造旧请求成本消失 |

发现冲突时暂停受影响的实施子任务，先由总控修正字段权威、API 和必要的 ADR，不能由本地执行 AI 自行设计。

## 8. 开发顺序与实施包

| Phase | 要打通的业务闭环 | 本阶段数据库/主要依赖 | 详细合同 |
|---|---|---|---|
| P0 | 全量设计审查、版本约束及兼容 PoC | 规范完成、PoC 必须真实运行 | [P0](./phases/p00.md) |
| P1 | Bootstrap → Team/Employee/RBAC/管理身份 | Core V1：6表；Gateway/Core 两进程 | [P1](./phases/p01.md) |
| P2 | Agent/Employee 双 Key 与员工使用授权 | Core V2：4表 | [P2](./phases/p02.md) |
| P3 | 可用的模型目录、双价、受保护 Secret | Core V3：7表 | [P3](./phases/p03.md) |
| P4 | 普通周积分 + 企业采购预算与账本 | Core V4：10表 | [P4](./phases/p04.md) |
| P5 | TeamPool/Grant 的真实发行消耗 | Core V5：3表 | [P5](./phases/p05.md) |
| P6 | 数据等级、出站审批与策略发布 | Core V6：7表 | [P6](./phases/p06.md) |
| P7 | 真正 Provider JSON/SSE + Redis Permit 结算 | Core V7：5表；Redis、FakeProvider | [P7](./phases/p07.md) |
| P8 | 同逻辑模型的健康选路 | Core V8：2表 | [P8](./phases/p08.md) |
| P9 | 用户同意和受限 LOW 保底 | Core V9：5表 | [P9](./phases/p09.md) |
| P10 | 两角色紧急审批与 4:2:1 调度 | Core V10：5表 | [P10](./phases/p10.md) |
| P11 | FinOps 五指标及可信异步读模型 | FinOps V1：7表；RabbitMQ | [P11](./phases/p11.md) |
| P12 | 多实例全局阻断与可审计恢复 | Core V11：1表及既有表 ALTER | [P12](./phases/p12.md) |

各阶段新增的高级机制仅因当期业务需要出现，不能在 P1 把 P12 的组件全部堆上去。每份 Phase 文档含 Business Goal、Scope、Main Flow、Architecture、Tasks、Acceptance Criteria、Knowledge Points，链接当期 SQL/API/协议/测试。具体先后关系见[Roadmap](./08-roadmap.md)。

## 9. 开发、测试、评审与冻结流程

本地 AI 的职责是**执行确定好的当前 Phase 合同**：交付 Java、Mapper、Flyway、容器配置、Lua/MQ/Provider Adapter（如果当期需要）、测试及命令/日志，而不是再设计业务字段或替总控维护正式文档。

每个 Phase 从最短可运行 Happy Path 打通本次业务；同一 Phase 再补齐已经承诺的校验、授权、并发、幂等、安全失败和真实测试，不让跨阶段核心安全规则永久拖欠。每轮结束依次完成：功能检查、Code Review、真实测试、架构检查、技术债登记、Progress 更新、Architecture 更新、必要 ADR、知识总结与面试表达；**用户确认后才进入下一阶段**。

测试证据必须带 Git SHA、Java/镜像版本、复现命令、测试编号、PASS/FAIL/SKIP、Provider HTTP 实际发送次数与关键资金状态。未运行标 NOT_RUN，缺环境标 BLOCKED；不能用静态推导冒充 MySQL/Redis/Provider 的真实通过。详见[测试计划](./09-test-plan.md)、[协作规范](./13-collaboration.md)、[Progress](./10-progress.md)。

## 10. 文档权威与变更原则

- 业务范围与角色：Requirements；总体信任边界：Architecture；实体关系和不变量：Domain Model。
- 表/外键/约束/迁移：对应 Database Vxx；公开 DTO、方法和错误：对应 API 分册；Redis/Provider/MQ/HMAC/恢复：对应 Protocol 分册。
- 执行范围与阶段验收：对应 Phase；验证案例：Tests；重大取舍：ADR；真实进度：Progress。
- 规范冲突时**不按后追加章节“最后写的为准”**，先发起设计勘误，更新唯一权威位置并检查关联文档。
- 不删除尚未迁移的规则；已被现行文档覆盖的历史 Dxx/Rxx 草稿只从 Git 历史追溯，不再作为实现输入。当前文档可合并同类章节，但不得丢弃唯一的失败边界或测试条件。
- P0兼容基线的官方版本支持证据与实际PoC验收由[技术兼容性检查](./tests/p00-technology-compatibility.md)追踪；其静态SUPPORTED不等于Maven/容器/真实HTTP PASS。

首次理解项目按本说明书从第1节顺读。实际开发只需要当前 Phase 文档及其关联 SQL/API/协议/测试规范，不需要靠聊天记录拼接指令。
