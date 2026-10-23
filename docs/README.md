# AIGate-Rebuild · 技术与开发文档

> **文档体系版本：v1.0 · 2026-10-23**  
> **当前状态：P0 详细设计重组完成候选，待用户正式冻结；P1～P12 尚未启动。**  
> 本仓库的正式文档以**业务主题和模块**组织，不再按聊天/设计日期逐段追加。**P0由用户在IntelliJ IDEA亲手创建工程并完成PoC，总控逐步教学；P1～P12源码、Flyway、Redis Lua与自动化测试由本地AI按完整Phase合同实施。**设计和文档始终由总控维护。

## 一、从哪里开始

**P0当前操作步骤**：[IDEA手把手克隆仓库、配JDK、搭Maven双模块与真实验证](./guides/p00-idea-hands-on.md)。**统一开发执行流程**：[Phase开发、任务下发、Git Review、知识讲解和用户签收](./13-collaboration.md#7-实际开发工作流唯一执行闭环)；当前P0由你亲手完成，不交给本地AI；P1～P12默认一个Phase一次完整交付，避免机械拆分。

**当前P0技术入口**：[Java21/Boot4/Cloud Gateway/MyBatis/MySQL/Flyway兼容性矩阵与本地AI PoC执行合同](./tests/p00-technology-compatibility.md)，静态核对已完成，真实PoC待执行。

[全部文件清单与逐份审查结果](./document-inventory.md)列出每一份文档、命名规则、修订内容和仍未完成的工程验证门禁。

**规范总入口**：[完整开发设计说明书](./00-development-specification.md)（从业务问题、三应用边界、数据、接口到P0～P12逐章阅读）；**设计冻结执行合同**：[P0检查与兼容PoC](./phases/p00.md)。

- **第一次了解项目**：读[项目总览](./01-project-overview.md) → [需求规格](./02-requirements.md) → [架构](./03-architecture.md) → [Roadmap](./08-roadmap.md)。
- **准备实施某个Phase**：读[模块设计索引](./phases/README.md)选对应Phase → 该Phase的数据库版本 → API分册 → 涉及的协议 → 对应测试。
- **需要解释架构选型**：读[ADR](./adr/README.md)，每篇都记录背景/候选/收益/代价。
- **准备Code Review和面试**：读[协作规范](./13-collaboration.md)、[知识地图](./11-knowledge-map.md)、[面试笔记](./12-interview-notes.md)。

## 二、规范文档清单

| 类别 | 文档 | 内容 |
|---|---|---|
| 总说明 | [00 Development Specification](./00-development-specification.md) / [P0执行合同](./phases/p00.md) | 连贯的业务与开发合同、设计冻结及PoC门禁 |
| 产品 | [01 Project Overview](./01-project-overview.md) | 定位、业务闭环、范围 |
| 需求 | [02 Requirements](./02-requirements.md) | FR/NFR、角色、边界、不变量 |
| 架构 | [03 Architecture](./03-architecture.md) | 三应用/信任边界/数据归属/技术 |
| 领域 | [04 Domain Model](./04-domain-model.md) | 实体关系、聚合与不可变事实 |
| 数据 | [05 Database Design](./05-database-design.md) | Core V1～V11 + FinOps V1共62表 |
| 接口 | [06 API Design](./06-api-design.md) | 外部DTO/错误/鉴权/分页与接口分册 |
| 运行协议 | [07 Runtime Protocols](./07-runtime-protocols.md) | Provider、Redis、SSE、HMAC、MQ、恢复 |
| 开发路线 | [08 Roadmap](./08-roadmap.md) | P0～P12及边界门禁 |
| 模块 | [Module Design](./phases/README.md) | P0～P12各自执行说明 |
| 测试 | [09 Test Plan](./09-test-plan.md) | 逐Phase/竞态/真实依赖测试 |
| 项目进展 | [10 Progress](./10-progress.md) | 实际完成/未执行与技术债 |
| 知识与表达 | [11 Knowledge Map](./11-knowledge-map.md) / [12 Interview Notes](./12-interview-notes.md) | 按真实开发进度学习和复盘 |
| 协作与开发流程 | [13 Collaboration](./13-collaboration.md) | 单次任务包→AI实现→本地运行→Git Review→教学→Phase签收完整闭环 |
| 决策 | [ADR Index](./adr/README.md) | 已选架构及未来决策机制 |

### 数据库分册

[Core V01](./database/core-v01.md) · [V02](./database/core-v02.md) · [V03](./database/core-v03.md) · [V04](./database/core-v04.md) · [V05](./database/core-v05.md) · [V06](./database/core-v06.md) · [V07](./database/core-v07.md) · [V08](./database/core-v08.md) · [V09](./database/core-v09.md) · [V10](./database/core-v10.md) · [V11](./database/core-v11.md) · [FinOps V01](./database/finops-v01.md)

### API分册

[P1～P3](./api/p01-p03.md) · [P4～P6](./api/p04-p06.md) · [P7～P12](./api/p07-p12.md) · [Gateway白名单](./api/gateway-routes.md) · [内部RPC](./api/internal-rpc.md)

### 核心协议分册

[请求签名](./protocols/runtime-canonical.md) · [治理](./protocols/governance.md) · [Provider](./protocols/provider-adapter.md) · [Redis](./protocols/redis-admission.md) · [FinOps](./protocols/finops-events.md) · [全局恢复](./protocols/global-admission.md) · [统一约束](./protocols/common-contracts.md)

### P0 IDEA实际操作

[从IDEA Clone Repository开始的亲手工程搭建指南](./guides/p00-idea-hands-on.md)：按操作节点逐步验证，**不是P0-A/B/C分包，也不委托本地AI**。

### 测试分册

[P1～P3](./tests/cases-p01-p03.md) · [P4～P7](./tests/cases-p04-p07.md) · [P8～P12](./tests/cases-p08-p12.md) · [审查回归](./tests/design-audit-regressions.md)

## 三、当前唯一有效的工程事实

| 事项 | 当前事实 |
|---|---|
| 架构 | P1起Gateway+Core两个独立进程；P11新增FinOps第三进程 |
| 数据 | Core55张表、FinOps7张表＝62张完整设计表，严格按Phase增量迁移 |
| 开发状态 | P0设计已审查并重组；没有实际业务源码、迁移结果或通过的CI |
| 测试状态 | 真实Java/Boot/MySQL/Redis/RabbitMQ/Provider工程验证均NOT RUN |
| 设计冻结 | 等待用户签收，不能仅凭文档存在声称P0 FROZEN |
| 分工 | P0用户在IDEA亲自操作、总控指导；P1～P12本地AI按整Phase编码与测试；总控维护正式设计/Review |

## 四、规范优先级与修订

**现有主题规范是开发事实来源，不是逐次追加的Dxx/Rxx日志。** 同一字段先看所属数据库版本；外部请求/响应看对应API分册；真实故障和内部RPC看协议分册；流程和范围看Phase说明；重大取舍看ADR。若这些规范之间仍发生冲突，本地AI暂停相关实现，由总控修文档后继续。

旧的六份中文长文档已迁入上述主题结构，历史决策和变化过程仍可通过Git版本历史回看，但它们不再作为当前实现指南。完整设计的真实性能/兼容验证尚待本地AI在经过批准的执行包中完成。
