# AIGate-Rebuild · 正式开发文档完整清单与复核记录

> 基准日期：2026-10-23；文档状态：P0 DESIGN_REVIEWED / NOT_FROZEN。此清单覆盖GitHub当前所有Markdown文档；表中“内容用途明确”是**静态审查结果**，不能替代Java/MySQL/Redis/RabbitMQ/Provider工程实测。

## 1. 审查原则与结果

- 本次逐份读取原70个Markdown文件，对项目总说明、需求、架构、领域、12个数据库设计DDL、管理及内部API、13个Phase、11个ADR、7个协议和4个测试分册进行内容/命名/交叉引用审阅。
- 修复重复编号：原`docs/06-module-design.md`迁到`docs/phases/README.md`，顶层`06-api-design.md`保留为唯一06编号；ADR-007/008/023/025～032的历史编号**不重新编号**。
- 每个Phase补齐可直接执行的任务分解、当期迁移/API/协议与专项测试入口；ADR补充真实验证及重审门槛。修复P6 RuleRegistry“尚未定义”的过时说法及内部RPC P10字段/版本描述。
- 静态SQL检查：12份设计SQL有**62个互不重复CREATE TABLE**、**136处FOREIGN KEY REFERENCES目标均存在**、**13个ALTER TABLE目标均存在**，未发现本文件顺序引用尚未创建的表。**未在MySQL8.4/Flyway真实执行，不保证DDL及语义正确**。
- 文档内容采用“独立完整的实施合同 + 主题权威分册”的方式：索引文档不机械复制所有DTO，SQL文档按迁移版本单独完整列出，Phase文档以链接和工作项组成可执行合同。

## 2. 命名规范

| 文档类别 | 标准命名 | 规则 |
|---|---|---|
| 顶层正式章节 | `docs/00～13-*.md`（如`06-api-design.md`） | 主章节编号唯一，文件名小写英文kebab-case |
| 目录索引 | `README.md`、`docs/phases/README.md`等 | 目录README承担导航，不另占章节号 |
| 决策记录 | `docs/adr/ADR-NNN.md` | 保留历史ID及空档，不重新编号已采纳决策 |
| 分阶段合同 | `docs/phases/pNN.md` | P0～P12，各阶段一份，内容含Goal/Scope/Flow/Architecture/Tasks/Acceptance/Knowledge |
| 核心数据库 | `docs/database/core-vNN.md` | 设计文档版本，真正Flyway文件名以各文档内Vn__*.sql为准 |
| FinOps数据库 | `docs/database/finops-v01.md` | 独立DB/独立Flyway版本号，不与Core V1冲突 |
| API/协议/测试专题 | `docs/api/*.md`、`docs/protocols/*.md`、`docs/tests/*.md` | 按业务主题与Phase范围命名，避免新增聊天编号日志 |
| 清单自身 | `docs/document-inventory.md` | 维护全部文档目录与静态复核结论 |

## 3. 全量文件清单

### 仓库入口、总体与过程规范（16份）

主文档允许精简索引，但必须为规范提供明确跳转。

| 文件 | 内容与职责 | 审查定位 |
|---|---|---|
| [`README.md`](../README.md) | 项目入口、设计状态与完整手册导航 | 文档静态核对；待相应Phase真实验证 |
| [`docs/00-development-specification.md`](./00-development-specification.md) | 一体化开发说明书；业务、架构、主流程、阶段及门禁 | 文档静态核对；待相应Phase真实验证 |
| [`docs/01-project-overview.md`](./01-project-overview.md) | 产品定位、业务闭环与非目标 | 文档静态核对；待相应Phase真实验证 |
| [`docs/02-requirements.md`](./02-requirements.md) | FR/NFR、角色、需求与Phase/SQL/API/测试追踪 | 文档静态核对；待相应Phase真实验证 |
| [`docs/03-architecture.md`](./03-architecture.md) | Gateway/Core/FinOps职责、信任边界、失败与部署视图 | 文档静态核对；待相应Phase真实验证 |
| [`docs/04-domain-model.md`](./04-domain-model.md) | 领域关系、聚合、状态演进和不可变事实 | 文档静态核对；待相应Phase真实验证 |
| [`docs/05-database-design.md`](./05-database-design.md) | Core与FinOps分库、迁移顺序、类型/约束纪律 | 文档静态核对；待相应Phase真实验证 |
| [`docs/06-api-design.md`](./06-api-design.md) | 公开HTTP/内部调用序列化、认证、错误和幂等总则 | 文档静态核对；待相应Phase真实验证 |
| [`docs/07-runtime-protocols.md`](./07-runtime-protocols.md) | JSON/SSE、Permit、Redis、MQ、失联与费用异常索引 | 文档静态核对；待相应Phase真实验证 |
| [`docs/08-roadmap.md`](./08-roadmap.md) | P0～P12阶段目标、依赖与技术引入时机 | 文档静态核对；待相应Phase真实验证 |
| [`docs/09-test-plan.md`](./09-test-plan.md) | 单元/集成/E2E/故障测试策略与证据格式 | 文档静态核对；待相应Phase真实验证 |
| [`docs/10-progress.md`](./10-progress.md) | 当前P0进度、静态检查证据、未运行事项 | 文档静态核对；待相应Phase真实验证 |
| [`docs/11-knowledge-map.md`](./11-knowledge-map.md) | 按实际Phase学习与复盘要求 | 文档静态核对；待相应Phase真实验证 |
| [`docs/12-interview-notes.md`](./12-interview-notes.md) | 架构表达、设计/实现证据边界与面试模板 | 文档静态核对；待相应Phase真实验证 |
| [`docs/13-collaboration.md`](./13-collaboration.md) | 总控与本地AI分工、签收及文档变更规则 | 文档静态核对；待相应Phase真实验证 |
| [`docs/README.md`](./README.md) | 文档导航、职责与规范优先级 | 文档静态核对；待相应Phase真实验证 |

### 架构决策 ADR（12份）

11份有效决策与1份目录，保留历史ADR编号空洞。

| 文件 | 内容与职责 | 审查定位 |
|---|---|---|
| [`docs/adr/ADR-007.md`](./adr/ADR-007.md) | Java21/Spring Boot4技术基线及PoC门禁 | 文档静态核对；待相应Phase真实验证 |
| [`docs/adr/ADR-008.md`](./adr/ADR-008.md) | P1起Gateway/Core分离、P11增FinOps的理由 | 文档静态核对；待相应Phase真实验证 |
| [`docs/adr/ADR-023.md`](./adr/ADR-023.md) | Core同库双账/价格/Permit事务策略 | 文档静态核对；待相应Phase真实验证 |
| [`docs/adr/ADR-025.md`](./adr/ADR-025.md) | Gateway精确路由和Core请求HMAC归属 | 文档静态核对；待相应Phase真实验证 |
| [`docs/adr/ADR-026.md`](./adr/ADR-026.md) | 设计冻结与真实PoC/Phase测试分层 | 文档静态核对；待相应Phase真实验证 |
| [`docs/adr/ADR-027.md`](./adr/ADR-027.md) | CanonicalDTO版本及HMAC密钥轮换 | 文档静态核对；待相应Phase真实验证 |
| [`docs/adr/ADR-028.md`](./adr/ADR-028.md) | 封闭Policy RuleRegistry和安全默认拒绝 | 文档静态核对；待相应Phase真实验证 |
| [`docs/adr/ADR-029.md`](./adr/ADR-029.md) | 可信用量、真实价格与供应商成本上界 | 文档静态核对；待相应Phase真实验证 |
| [`docs/adr/ADR-030.md`](./adr/ADR-030.md) | Redis容量Pool共享Lua及runId/epoch恢复 | 文档静态核对；待相应Phase真实验证 |
| [`docs/adr/ADR-031.md`](./adr/ADR-031.md) | Outbox/RabbitMQ/Inbox/Manifest双Build | 文档静态核对；待相应Phase真实验证 |
| [`docs/adr/ADR-032.md`](./adr/ADR-032.md) | Core MySQL全局Block与Readiness证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/adr/README.md`](./adr/README.md) | 有效ADR索引、状态与历史编号约定 | 文档静态核对；待相应Phase真实验证 |

### API接口合同（5份）

接口DTO、Method/Path及可信RPC均为设计定义。

| 文件 | 内容与职责 | 审查定位 |
|---|---|---|
| [`docs/api/gateway-routes.md`](./api/gateway-routes.md) | 按Phase展开的Gateway Method+Path白名单 | 文档静态核对；待相应Phase真实验证 |
| [`docs/api/internal-rpc.md`](./api/internal-rpc.md) | Gateway/Core/FinOps的内网受信接口与协议版本 | 文档静态核对；待相应Phase真实验证 |
| [`docs/api/p01-p03.md`](./api/p01-p03.md) | P1～P3管理DTO、双Key、价格与Secret操作合同 | 文档静态核对；待相应Phase真实验证 |
| [`docs/api/p04-p06.md`](./api/p04-p06.md) | P4～P6财务/Grant/安全策略管理合同 | 文档静态核对；待相应Phase真实验证 |
| [`docs/api/p07-p12.md`](./api/p07-p12.md) | P7～P12 Chat、路由、LOW、紧急与FinOps/恢复合同 | 文档静态核对；待相应Phase真实验证 |

### 数据库DDL设计（12份）

Core 55表+FinOps 7表；所有脚本均需实际Flyway验证。

| 文件 | 内容与职责 | 审查定位 |
|---|---|---|
| [`docs/database/core-v01.md`](./database/core-v01.md) | P1 Core V1组织与管理身份六表DDL | DDL静态核对；待相应Phase真实验证 |
| [`docs/database/core-v02.md`](./database/core-v02.md) | P2 Core V2双运行身份四表DDL | DDL静态核对；待相应Phase真实验证 |
| [`docs/database/core-v03.md`](./database/core-v03.md) | P3 Core V3供应商、模型和价七表/ALTER | DDL静态核对；待相应Phase真实验证 |
| [`docs/database/core-v04.md`](./database/core-v04.md) | P4 Core V4资金账户与账本十表DDL | DDL静态核对；待相应Phase真实验证 |
| [`docs/database/core-v05.md`](./database/core-v05.md) | P5 Core V5 TeamPool/Grant三表DDL | DDL静态核对；待相应Phase真实验证 |
| [`docs/database/core-v06.md`](./database/core-v06.md) | P6 Core V6治理七表/角色约束ALTER | DDL静态核对；待相应Phase真实验证 |
| [`docs/database/core-v07.md`](./database/core-v07.md) | P7 Core V7 Invocation/Permit/Pool五表/ALTER | DDL静态核对；待相应Phase真实验证 |
| [`docs/database/core-v08.md`](./database/core-v08.md) | P8 Core V8健康路由两表/ALTER | DDL静态核对；待相应Phase真实验证 |
| [`docs/database/core-v09.md`](./database/core-v09.md) | P9 Core V9 LOW和公司池五表/ALTER | DDL静态核对；待相应Phase真实验证 |
| [`docs/database/core-v10.md`](./database/core-v10.md) | P10 Core V10紧急授权五表/ALTER | DDL静态核对；待相应Phase真实验证 |
| [`docs/database/core-v11.md`](./database/core-v11.md) | P12 Core V11全局准入单例及审计ALTER | DDL静态核对；待相应Phase真实验证 |
| [`docs/database/finops-v01.md`](./database/finops-v01.md) | P11 FinOps独立七表DDL | DDL静态核对；待相应Phase真实验证 |

### 各Phase实施合同（14份）

P0～P12各有一份，另设模块总索引。

| 文件 | 内容与职责 | 审查定位 |
|---|---|---|
| [`docs/phases/README.md`](./phases/README.md) | P0～P12实施合同总索引 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p00.md`](./phases/p00.md) | P0设计冻结/最小PoC与退出证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p01.md`](./phases/p01.md) | P1业务Goal/Scope/Main Flow/架构/任务/验收/证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p02.md`](./phases/p02.md) | P2业务Goal/Scope/Main Flow/架构/任务/验收/证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p03.md`](./phases/p03.md) | P3业务Goal/Scope/Main Flow/架构/任务/验收/证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p04.md`](./phases/p04.md) | P4业务Goal/Scope/Main Flow/架构/任务/验收/证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p05.md`](./phases/p05.md) | P5业务Goal/Scope/Main Flow/架构/任务/验收/证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p06.md`](./phases/p06.md) | P6业务Goal/Scope/Main Flow/架构/任务/验收/证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p07.md`](./phases/p07.md) | P7业务Goal/Scope/Main Flow/架构/任务/验收/证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p08.md`](./phases/p08.md) | P8业务Goal/Scope/Main Flow/架构/任务/验收/证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p09.md`](./phases/p09.md) | P9业务Goal/Scope/Main Flow/架构/任务/验收/证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p10.md`](./phases/p10.md) | P10业务Goal/Scope/Main Flow/架构/任务/验收/证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p11.md`](./phases/p11.md) | P11业务Goal/Scope/Main Flow/架构/任务/验收/证据 | 文档静态核对；待相应Phase真实验证 |
| [`docs/phases/p12.md`](./phases/p12.md) | P12业务Goal/Scope/Main Flow/架构/任务/验收/证据 | 文档静态核对；待相应Phase真实验证 |

### 协议设计（7份）

字段/状态/错误/失效处理等技术合同。

| 文件 | 内容与职责 | 审查定位 |
|---|---|---|
| [`docs/protocols/common-contracts.md`](./protocols/common-contracts.md) | 跨Phase精度、资金、安全与版本消歧公约 | 文档静态核对；待相应Phase真实验证 |
| [`docs/protocols/finops-events.md`](./protocols/finops-events.md) | Core Outbox、RabbitMQ、FinOps Inbox/Manifest/Build | 文档静态核对；待相应Phase真实验证 |
| [`docs/protocols/global-admission.md`](./protocols/global-admission.md) | P12全局Block、ReadinessProbe及安全恢复 | 文档静态核对；待相应Phase真实验证 |
| [`docs/protocols/governance.md`](./protocols/governance.md) | 可信数据分级、四类固定Rule与全八格出站矩阵 | 文档静态核对；待相应Phase真实验证 |
| [`docs/protocols/provider-adapter.md`](./protocols/provider-adapter.md) | Provider文本JSON/SSE、可信usage与风险上界 | 文档静态核对；待相应Phase真实验证 |
| [`docs/protocols/redis-admission.md`](./protocols/redis-admission.md) | 容量Pool Lua、Guard、调度公平与失效隔离 | 文档静态核对；待相应Phase真实验证 |
| [`docs/protocols/runtime-canonical.md`](./protocols/runtime-canonical.md) | CanonicalDTO/HMAC密钥与v1/v2/v3契约 | 文档静态核对；待相应Phase真实验证 |

### 测试用例（4份）

设计用例清单，不表示真实PASS。

| 文件 | 内容与职责 | 审查定位 |
|---|---|---|
| [`docs/tests/cases-p01-p03.md`](./tests/cases-p01-p03.md) | P1～P3组织、密钥与模型目录案例 | 文档静态核对；待相应Phase真实验证 |
| [`docs/tests/cases-p04-p07.md`](./tests/cases-p04-p07.md) | P4～P7额度、治理、Provider调用与Redis案例 | 文档静态核对；待相应Phase真实验证 |
| [`docs/tests/cases-p08-p12.md`](./tests/cases-p08-p12.md) | P8～P12路由、降级、紧急、FinOps与恢复案例 | 文档静态核对；待相应Phase真实验证 |
| [`docs/tests/design-audit-regressions.md`](./tests/design-audit-regressions.md) | P0审查修订涉及的跨Phase回归案例 | 文档静态核对；待相应Phase真实验证 |

本清单统计原有 **70 份文档**；加上本清单，新仓库应有 **71 份Markdown文件**。更新后的数量需在同一次提交后重新核验。

## 4. 未关闭的正式交付门禁

1. P0的Java21/Spring Boot4.1.1/Gateway MVC/MyBatis-Plus/MySQL8.4/Flyway最小PoC与双进程真实安全E2E仍为NOT_RUN；未知兼容问题不能假装解决。
2. P1～P12真实业务源码和单元/集成/故障测试尚未启动；不能凭本清单将任何后续Phase标PASS。
3. 真正的Provider账单、Tokenizer证据、容量证明、安全规则、区域审批由实际授权业务方提供；缺失时应安全拒绝，而不是由AI补造。
4. 后续实施发现精确字段/唯一约束、API/Protocol、测试互相矛盾时，由总控改正式文档和必要ADR，执行AI不得擅自设计；发现后复核此目录并更新[Progress](./10-progress.md)。

当前审查得到的是**文本层可用于P0最终审查的设计体系**，并非所有中间件与业务功能已经通过工程验收。
