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
