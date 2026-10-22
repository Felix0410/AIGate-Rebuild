# AIGate · 项目设计总览

> **当前阶段：P0 设计与审查；新项目尚未开始编码。**
>
> 新仓库：[Felix0410/AIGate-Rebuild](https://github.com/Felix0410/AIGate-Rebuild)。旧 AIGate 仓库只作历史备份，不再是新项目的事实来源。

## 项目是做什么的？

AIGate 是**企业内部已有 Agent 的 AI 模型接入与治理平台**：统一员工和 Agent 双身份认证、模型权限、Token/积分额度、Team 发放、限流与企业保底、SSE 模型路由、安全合规和 FinOps 成本分析。

项目不开发 Agent 工作流或模型训练。用户重点学习实际业务、Java 后端技术和架构设计；代码由本地 AI 根据我事先确定的详细任务快速实施。

## 七份核心文档

| 阅读顺序 | 文档 | 主要内容 |
|---|---|---|
| 1 | [业务与规则](01-业务与规则.md) | 产品定位、角色、完整需求、周积分、Team 发放、保底、SSE、治理、FinOps |
| 2 | [架构与技术](02-架构与技术.md) | 从 P1 直接采用 Gateway+Core 微服务，P11 增加 FinOps；五领域边界、ADR、Redis/RabbitMQ/SSE |
| 3 | [数据与接口](03-数据与接口.md) | 领域对象、数据库字段/索引、账务事务、身份安全、HTTP/SSE API |
| 4 | [阶段与任务](04-阶段与任务.md) | 从零重做的 P0～P12、具体任务、逐阶段验收和工作包交付规则 |
| 5 | [测试与验收](05-测试与验收.md) | 测试矩阵、并发/幂等、CI、SSE/MQ 故障、压测与观测 |
| 6 | [进度与协作](06-进度与协作.md) | 当前进度、已定/未定决策、ADR 摘要、本地 AI 规范、学习与面试路线 |

加上本索引文件，**整个 docs 仅保留 7 份中文 Markdown**。不再单独维护十几份需求、架构、ADR、Progress 和面试笔记。

## 文档如何使用

产品规则与需求以《01-业务与规则》为准；实现边界以《02-架构与技术》和《03-数据与接口》为准；阶段任务以《04-阶段与任务》为准；质量门禁与进度以《05-测试与验收》《06-进度与协作》为准。

**新开发规则（2026-10-21 已确认）**：编码前必须完成全部业务与技术设计、完整数据/API/异常/安全合同、全部业务块及其测试用例，并完成跨文档一致性审核与用户批准。编码以**完整业务块**为交付单位：该块承诺的功能、参数校验、权限、异常、幂等、事务/并发、测试和运行验证同时交付；不再采用“先做 Happy Path、以后补该块必要功能”的两轮开发模式。但仍逐块提交和验收，不能一次把全平台交给本地 AI。本地 AI 不维护 docs，也不得临时自行做业务/技术选择。业务块所依赖的前置模块须按依赖关系先完成，基础资金/认证/安全条件不完整时禁止真实付费出站。重大新增技术仍需业务理由，不为技术栈而过度设计。每个业务块/Phase 完成后 Review、测试、架构/技术债复盘，经用户确认才进入下一块。

## 当前进度

P0 文档已有总体框架，**尚未达到“全量设计冻结”的要求**：需补齐逐业务块的请求/响应 DTO、数据库表约束、资金和故障状态、逐块测试用例、全部任务合同、失败恢复和依赖关系等。新的业务块排序见《04-阶段与任务》，属于待讨论的重排提案。P1～P12 **均未启动**，没有新 Java 代码、数据库迁移或 CI 可作为已完成证据。所有公司具体预算、模型价格、真实供应商密钥均不编造。

## 本地准备

```bash
git clone https://github.com/Felix0410/AIGate-Rebuild.git
cd AIGate-Rebuild
```

在 P1 开始前只有 docs/ 是正常的。开始编码后，我负责分解任务与技术教学，本地 AI 负责代码、测试、迁移和运行配置。

## 设计冻结门禁

本仓库全部 7 份文档在首行写出版本/批准状态。只有满足**完整业务需求、可执行数据与接口合同、全量业务块工作包、全量验收矩阵、核心选型/失败行为确定、文档交叉审核无阻塞项**，并得到用户明确批准，才可将 P0 标为 FROZEN、开始本地 AI 的首次编码。冻结后若发现设计漏洞，总控负责变更文档、标注影响和复验，绝不把决策转交本地 AI。

### 最近一次设计增量：P0-D12（2026-10-21）

开始按完整业务块落地可执行合同：P1 组织/Team/Employee/管理角色、受控 Bootstrap 与独立管理 Key、审计，**D12 初版设计**了五表逐列结构、15 个管理 API（已由 D18 补成六表和2个受控 Bootstrap 运维 API）、事务/错误约束与 P1-01～P1-24 验收矩阵。技术权衡在《02-架构与技术》ADR-006，详细合同在《03-数据与接口》D12、任务/测试在《04-阶段与任务》《05-测试与验收》、记录在《06-进度与协作》。这些均为 PROPOSED，不代表 P0 冻结，也未授权启动 P1 编码。

### P0-D13 总体架构交叉审查（2026-10-21）

重新核验 D01～D12：形成 8 条业务闭环、统一运行主链、五领域数据所有权、Dispatch 同一 MySQL 短事务、Redis 等待阶段零资金预留、20 条跨 Phase 组合验收用例和阻塞事项清单。修复了 P2 需要 Agent-Model 白名单、但 P3 才创建 Model 的前置矛盾：修订提案为 P2 双 Key/Employee-Agent、P3 Model 目录/Agent-Model；P4 以唯一 requestRef 在没有 P7 Gateway 的情况下完整测试财务业务，P7 再建立 Invocation 一对一关联。

详细：业务检查见《01-业务与规则》D13，唯一执行链/问题列表见《02-架构与技术》D13，跨模块事务见《03-数据与接口》D13，阶段依赖见《04-阶段与任务》D13，SYS-01～SYS-20 见《05-测试与验收》D13，待决清单见《06-进度与协作》D13。

**结论**：总体架构已有经过审查的主方案，但仍是 REVIEWED / NOT FROZEN；全量逐接口/DDL/测试固件与重大产品默认规则尚未冻结，P1～P12 均未编码、未授权启动。

### P0-D14 技术方案已确认（2026-10-21）

用户已确认的 D14 基础栈已由 D15 的直接微服务部署决策补充。**TECHNOLOGY CHOICES APPROVED**：Java21、Gateway+Core（P1）+FinOps（P11）、Spring Boot4.1.1/MVC/Security、MyBatis-Plus Boot4 Starter 3.5.17、MySQL8.4 LTS/Flyway、Redis+Lua、RabbitMQ+Spring AMQP（P11）、Maven Wrapper/Compose/Testcontainers/CI、P7 MVC SSE 与 Provider Adapter、分阶段观测。RabbitMQ 不再作为“还需选择哪种 MQ”的开放项。决定/替代/复杂度和官方资料见《02-架构与技术》D14/ADR-007；验收见《05-测试与验收》TECH-01～TECH-12，状态同步《06-进度与协作》。

**并非 ENGINEERING VERIFIED**：新仓库仍无 Java 工程及实测迁移/Redis/MQ/构建，通过官方发布信息只能确定选型基线，无法证明项目依赖组合已经跑通。具体 Maven Wrapper、镜像 patch/digest 和实测结果待技术门禁核验；**P0 设计未冻结、P1～P12 未启动**。

### P0-D15：从 P1 起直接三部署边界微服务（2026-10-21）

**架构已获用户明确批准，取代以前“单体起步、P12 条件式拆微服务”的提案。** 不是五业务领域各开一个服务：**P1 两个独立启动进程** aigate-gateway（Spring Cloud Gateway Server WebMVC 的管理 HTTP 反向代理入口，P7 加 Runtime/Redis/SSE/Provider）和 aigate-core（Identity/Catalog/Governance/Quota+Billing/Invocation 幂等与唯一派发意图，core_db 的全部安全与双预算在同一 MySQL 事务）；**P11 增加 aigate-finops**（RabbitMQ/Inbox、只读聚合、自有 finops_db）。

拆分理由：Gateway 的长 SSE 与外部 Provider 网络 I/O 和 Core 的短 MySQL 强一致事务具备不同的资源、扩容与故障要求；FinOps 为可异步恢复的统计负载。核心安全+双预算不能直接拆到五个独立数据库，否则必须面对真实分布式事务/授权撤销的不确定性。P1 起验证两个进程/真实 HTTP、故障边界，P7 完整 Remote Permit，P11 验证真实独立统计。

技术增量：Spring Cloud 2025.1.2 + Spring Cloud Gateway Server WebMVC Starter（仅 Gateway），Gateway→Core 采用受控 Spring RestClient 内部 HTTP RPC；Nacos/OpenFeign/K8s 不因“微服务”名称必装。官方支持关系不是本仓库实际构建证据。

主要合同：业务《01》D15；ADR-008 与七问《02》D15；core_db/finops_db、内部远程派发《03》D15；修订 P1～P12《04》D15；MSC-01～MSC-24《05》D15；实际进度/门禁《06》D15。

**状态：ARCHITECTURE CHOICE APPROVED / P0 DETAILED DESIGN IN PROGRESS / P1 NOT STARTED。** 目前只有文档，未创建/部署任何微服务项目源码，也没有运行构建或测试。

### P0-D16：Gateway/Core 安全通信、唯一 Permit 与 Provider Secret（2026-10-22）

微服务直接拆分后，新增内部通信设计：P1 管理 API 从 Gateway 安全路由至 Core，Core 自行做 mgt_ 授权；/internal 使用 svc_ 服务身份凭证但不代替运行时 agk_+empk_ 和当前 ACL/治理/资金校验；生产 Gateway→Core 使用可信 TLS。P7 Core 在同一 MySQL 事务原子预留双账和写唯一 DispatchPermit/Intent，Gateway 只能在首次 permit 明确成功且 lease 有效后对 Provider 发起一次执行；RPC 超时查状态不重新发送。Provider Key 由 Core 加密保存、Gateway 按真实 Permit 受控临时获取，结果报告可幂等重复而 Provider 请求不可盲重试。

详见《02-架构与技术》D16/ADR-009、《03-数据与接口》D16 运行时 DTO、《04-阶段与任务》P1-EDGE/P3-CREDS/P7-RPC、《05-测试与验收》RPC-01～RPC-20、《01-业务与规则》D16，真实进度在《06-进度与协作》D16。

**状态**：D16 PROPOSED/NOT FROZEN；P0 仍在完善详细合同；所有 RPC 测试 NOT RUN；没有开始 P1 编码。

### P0-D17：FinOps 独立服务的角色权限和费用事实（2026-10-22）

在 D15 真实三服务部署、D16 内部服务认证的基础上，明确 **P11 外部 FinOps API 统一走 Gateway→Core→FinOps**：Core 验 mgt_、当前 RBAC/Team/Employee，产生最小允许的 TEAM/COMPANY/SELF typed Scope；FinOps 只认 Core 专用受信 service credential 并按 Scope 对自己的 finops_db 查询，不能访问 core_db 或信任客户端任意 teamId/employeeId。Core 的实时 pending-exposure/outbox-status 保留在 Core，不由异步财务统计近似推算。自部署实际运营费用凭证/草稿/另一管理员审批在 FinOps 自有数据库单事务提交，按真实原币种/完整月服务期统计，不造逐日费用。跨 Team Grant 与历史 Employee Team attribution 不因现时调动被改写。

详见《01-业务与规则》D17、《02-架构与技术》D17/ADR-010、《03-数据与接口》D17（权限/API/事实归属）、《04-阶段与任务》P11-FINOPS-ACCESS、《05-测试与验收》FNA-01～FNA-20 与《06-进度与协作》D17。

**D17 状态：PROPOSED/NOT FROZEN，FNA-01～20 NOT RUN；P0 仍未全部冻结，P1 NOT STARTED。**

### P0-D18：P1 双微服务的工程合同与唯一 Bootstrap 初始化（2026-10-22）

**新增 ADR-011**：P1 在一个 Git 仓库创建 Maven 父工程 + aigate-contracts 普通 DTO Jar + aigate-core 与 aigate-gateway 两个真实可执行 Spring Boot 应用 + aigate-e2e-tests。版本与依赖提案明确为 Java21、Boot4.1.1、Cloud2025.1.2 Gateway Server WebMVC、MyBatis-Plus Boot4 3.5.17、MySQL8.4/Flyway、Redis8.4、Maven Wrapper3.9.16、Testcontainers2，Gateway 不引 Core 数据库模块。

**P1 重要安全修订**：D12 原来 5 张管理表增加 bootstrap_control 第 6 张单例表，MySQL 行锁保证首次 Team+Employee+GLOBAL Admin+mgt_ Key+审计只能提交一次；新增两个运维 Bootstrap API 的明确定义，普通管理仍 15 个。P1 入口只有 Gateway 对外，Core 只通过内部 DNS/网络受访问限制并独立验权限，Redis 不处理管理 RBAC。

七文档具体内容：《01》D18 业务边界、《02》D18/ADR-011 多模块和版本决策、《03》D18 六表/路由/配置、《04》D18 P1-T01～T06、《05》D18 P1-25～P1-42、《06》D18 决策进度与风险清单。

**当前真实状态**：D18 是设计提案，尚未创建 Java 工程、未运行 Maven/Flyway/Compose/Testcontainers，镜像 digest 与安全/兼容门禁待核验；P0 未冻结，P1 未开始。

### P0-D19：P2 Agent、双运行 Key 和员工授权业务详细设计（2026-10-22）

从已批准的双服务架构转向逐业务块设计。本轮完成 **P2 的 Agent/Application 1:1 纳管、agk_ Agent Key、empk_ Employee Runtime Key、Employee-Agent ACL** 合同；复用 P1 已有 Employee 管理身份、不提前建 P3 Model 或 P7 真实模型执行。Core Flyway V2 规划四张新增表（agent、agent_api_key、employee_credential、employee_agent_permission，P2 总共10表），15个新的管理 API 与仅 svc_+双 Key 允许的 Core /internal/v1/runtime-identities/verify。Key 随机32字节、HMAC不同用途 Pepper、只在首次签发显示、受控轮换24h/每Owner最多两把可认证 Key，当前默认仅 GLOBAL PLATFORM_ADMIN 可管理。

详见《01-业务与规则》D19、《02-架构与技术》D19/ADR-012、《03-数据与接口》D19 完整 SQL+API、《04-阶段与任务》P2-T01～P2-T07、《05-测试与验收》P2-01～P2-40、《06-进度与协作》D19。

**真实进度**：D19 PROPOSED；全部新测试 NOT RUN、没有新增 Java/Flyway/Compose 工程源码，仍在 P0 设计，不自动启动 P1/P2；下一步 P3 Catalog 设计。

### P0-D20：P3 模型目录、双价格版本和 Agent-Model 白名单（2026-10-22）

继 D19/P2 双运行 Key 与 Employee-Agent ACL 后，D20 设计 P3 Catalog：Provider/LogicalModel/Deployment 独立身份、V3 七张 Core MySQL 表、统一 Model POINTS 版本价与按第三方 Deployment 原币种采购价、Core AES-GCM 供应商密钥、Agent-Model 真白名单。只有同时存在 P2 Employee-Agent 与 P3 Agent-Model 授权，未来 Runtime 才有模型使用资格。P3 只实现管理及可信 eligibility，不执行供应商 HTTP 或资金。

细节见《01-业务与规则》D20、《02-架构与技术》D20/ADR-013、《03-数据与接口》D20 表/API、《04-阶段与任务》P3-T01～T07、《05-测试与验收》P3-01～P3-32、《06-进度与协作》D20。**D20 PROPOSED / NOT RUN；P0 尚未总冻结，P1～P3 NOT STARTED。**

### P0-D21～D24：一次设计 P4～P7 四个核心业务块（2026-10-22）

按真实业务依赖一次完成四块**设计提案**，不启动编码。**P4/D21** 为 Core 增普通 Agent周/Employee周 POINTS、公司原币种总月预算/NORMAL份额、风险预留/可信用量结算/待对账/不可变 Ledger+Outbox（V4十表，P7 前无需假 Invocation）；**P5/D22** 为 TeamPool→Grant 发行即扣发行 Team、员工按 FEFO 使用、到期不退与发行/消费事实分离（V5三表）；**P6/D23** 为企业四级数据治理、不可自审批政策版本、Agent/Provider/地域/Deployment 出站授权、前检+最终 DB 复核及 Prompt 最小隐私（V6七类表）；**P7/D24** 首次 Gateway JSON+SSE+Redis Lua、Core 双身份/双 ACL/治理/预算同库原子签发唯一 Permit、真实 Provider 只能首次明确授权发送一次、可信 usage/未知费用与幂等结果报告（V7五表）。

**为什么要这样分**：P4 的双账必须先可靠，P5 才扩展第三来源，P6 安全先于第一次外部收费，P7 最后连接真正的网络调用；不为了微服务拆分钱或越过完整权限/治理。技术/架构取舍记录于《02-架构与技术》**ADR-014～017**；详细对象与接口在《03-数据与接口》D21～D24；P4～P7 全工作包见《04-阶段与任务》；《05-测试与验收》新增 **BIZP4-01～12 / BIZP5-01～12 / BIZP6-01～12 / BIZP7-01～16 共52个 NOT RUN** 场景；《06-进度与协作》同步事实。

**当前状态**：设计已记录但 V4～V7 全量 Flyway SQL、所有 JSON fixture、真实兼容/多进程测试未最终冻结；P0 仍未 FROZEN、P1～P12 NOT STARTED。

### P0-D25～D29：批量完成 P8～P12 五块详细设计（2026-10-22）

在 P4～P7 核心链路已经形成合同后，继续按依赖批量设计：**D25/P8** 可解释同型号多Deployment动态健康与安全选路（Core V8路由政策+观测表，测试D25-RT01～12）；**D26/P9** 经管理员审批和客户端 fallback_mode 明确同意的 NORMAL→LOW 公司付费保底及本地基础 SELF_HOSTED 周Token补贴（V9五表，测试D26-FAL01～14）；**D27/P10** 双人审批紧急任务、唯一活动授权、公司紧急Points和采购EMERGENCY专用币种份额、Redis4:2:1轮转公平（V10五表，测试D27-EM01～16）；**D28/P11** 真正上线第三个FinOps Spring Boot服务/独立finops_db V1七表、RabbitMQ Outbox/Inbox可靠消费、Core用户授权代理/财务事实口径及源事件核对（测试D28-FIN01～16）；**D29/P12** 三服务多实例安全部署、MySQL/Redis/MQ/Provider/SSE故障与1vs2 Gateway压测/Runbook（测试D29-OPS01～12）。

本轮在《02-架构与技术》新增 **ADR-018～022** 的候选方案/原因/代价；《03-数据与接口》D25～D29 按数据归属、API、主流程与事务记录实施合同；《04-阶段与任务》P8～P12 各自 Tasks/Acceptance/知识点；《05-测试与验收》新增**70个全部NOT RUN**场景；《01》业务边界、《06》真实进度与总冻结剩余阻塞同步。并统一：P7只开放NORMAL、P9启LOW、P10启EMERGENCY；FinOps V1属于自身数据库迁移版本空间，不是Core V11；P12验证已确定的三服务，不强制拆第四个。

**当前状态：P0全部Phase都已有业务模块设计方案，仍未完成全量可执行DDL/固定DTO/兼容实测与最终一致性冻结；P1～P12没有启动编码或运行测试。下一工作是P0集中规范化/冻结审查，不自动开P1。**

### P0-D30～D41：数据库全迁移SQL基准、统一协议与FinOps可靠重建（2026-10-22）

继续从业务设计转为精确工程合同：《03-数据与接口》D30给出Core P1 V1完整六表建表SQL；D19 P2已有V2四表完整SQL；D31 P3 V3七表、D32 P4 V4十表、D34 P5 V5三表、D35 P6 V6七表及角色约束迁移、D36 P7 V7五表、D37 P8 V8两表、D38 P9 V9五表、D39 P10 V10五表；D40是**独立finops_db的V1七表**。合计**Core54表+FinOps7表=61张SQL设计表**，P12不提前造无业务需要的表。D33明确API失败/幂等/六类受信RPC/锁序及所有迁移归属；D41补FinOps ≤5000条Outbox单次一致快照源manifest（超限不伪报FRESH）。

重要架构修订见《02-架构与技术》ADR-023（价格版本当前指针、真实Provider超额、Outbox提交顺序）和ADR-024（按buildId隔离双版本FinOps重建），《04-阶段与任务》P0 FZ-01～FZ-06与《05-测试与验收》FZSQL/FZAPI/FZOPS验收门禁。《06-进度与协作》更新真实状态。**61张表目前存在于docs的SQL code block，不是已创建的数据库**；没有Maven/Compose/MySQL/Testcontainers真实运行，所有SQL仍标待验证。业务架构已经覆盖P1～P12，但P0尚未冻结、P1没有开始。

### P0-D42～D43：P7内网RPC JSON与跨阶段持久化/事件恢复（2026-10-22）

在全部61张表 SQL设计文本之后继续收敛接口：《03》D42给出Gateway↔Core六类内部RPC的Schema V1、JSON与三种ACK丢失处理，并修正为Core独占HMAC-SHA256原业务摘要（Gateway不持Core HMACKey、不上传该摘要）；D43对P9 V9新增Invocation最终降级Model/Policy/资金来源持久快照、P10 V10新增紧急Authorization/Task引用。P11新增FinOps按eventId受信补取Core Outbox安全事件的内部只读协议，与D41不超过5000个单快照源Manifest联合保证缺口不被当0。

旧口径修正：P1公开错误Envelope按D33；旧D16 Runtime JSON命名不再实现并行别名；FinOps报表统一Gateway→Core鉴权→FinOps私有查询。对应的《05》FZAPI/FZSQL/FZRPC验收、《04》专项任务、《06》进度已同步。**SQL/DTO均为尚待MySQL、Gateway/Core、RabbitMQ集成验证的设计，不代表P0已冻结或任何Phase开始编码。**

### P0-D44：Gateway公开路由注册白名单（2026-10-22）

《03-数据与接口》D44建立P1～P12统一公开路由表：P1 Bootstrap和组织管理只有Gateway→Core、P2-P6逐阶段扩大管理资源、P7首开ChatCompletion/Invocation、P9 LOW/P10紧急需各自批准、P11所有FinOps外部请求仍先走Core当前RBAC/TypedScope、P12只开放受限运维管理。D16旧Gateway直连FinOps与旧RPC字段不再作为可实现合同。《02》新增ADR-025解释为什么Core独占HMAC安全密钥和Gateway严格阶段化路由；D43补充P10 EMERGENCY_POINTS的V10资金类型CHECK显式升级，避免紧急业务时数据库拒绝保存历史资金类型。验收见《05》FZROUTE-01～07，均NOT RUN；P0尚未冻结。


### P0-D45～D47：P1～P3公开 DTO、安全 JSON Schema 与冻结门禁纠偏（2026-10-22）

《03-数据与接口》新增 **D45** 的 P1 17条（15管理+2受控Bootstrap）、P2 15条及 P3 Catalog 公开接口 DTO/HTTP/字段/null/幂等/分页/错误合同，以及 **D46** Provider HostPolicy/Model Capability 封闭 v1 JSON Schema、ProviderCredential 在 **V3原表**增加签发者/Idempotency-Key/HMAC收据四列及唯一约束。Core仍设计54表、FinOps7表，合计61表；**规范SQL文本已修改，但真MySQL尚未执行**。

《02-架构与技术》新增 **ADR-026**：P0 必须先全量设计+最小基础兼容PoC；真正的 P7 SSE/Redis/Provider、P11 RabbitMQ/FinOps、P12 多Gateway/故障集成必须在对应 Phase 完成，不能在编码前强制这些尚不存在的系统先通过测试。《04》已更新 FZ-01～06 门禁解释与 P1-T01～T06执行清单；《05》新增 D47-API01～23 测试设计（全部 NOT RUN）；《06》同步真实状态。

**状态仍是 P0 DESIGN IN PROGRESS / NOT FROZEN；P1～P12未启动。** 当前最大文本缺口是 P4～P12 每API字段级规范化和跨文档复核，此外真实 Maven/Boot/Gateway/MySQL Flyway PoC无PASS证据；不应把D45～47文档进展误当通过构建/已发布应用。
