# 需求规格说明（SRS）

## 1. 业务角色

| 主体 | 权限/责任 |
|---|---|
| PLATFORM_ADMIN（GLOBAL） | Team/Employee/Agent、模型、价、池、账务预算、运营配置；仍受安全审批分权约束 |
| TEAM_MANAGER（TEAM） | 仅本Team受权人员与TeamPool/Grant范围 |
| POLICY_EDITOR（GLOBAL） | 创建治理草稿与提交，不得自批 |
| SECURITY_APPROVER（GLOBAL） | 复核/发布治理配置，不得审核自己提交的规则 |
| FINOPS_READER（GLOBAL/TEAM） | 只读有限指标；TEAM不可查询无归属采购或运营成本 |
| Employee + Agent | 两把独立运行Key，以及Employee-Agent和Agent-Model两类许可均成立才可能调用 |
| 内部 svc_ | Gateway→Core、Core→FinOps 的专用服务身份，不能等同用户凭证 |

## 2. 功能需求（FR）

| 编号 | 需求 | 首次实现 |
|---|---|---|
| FR-01 | 受控Bootstrap、管理身份、Team/Employee/RBAC、审计 | P1 |
| FR-02 | Agent、agk_/empk_签发/轮换/吊销及EmployeeAgent ACL | P2 |
| FR-03 | Provider/Model/Deployment、双价格版本、Secret加密及AgentModel ACL | P3 |
| FR-04 | 周积分、公司正常/紧急原币种月预算分区、Ledger与Outbox | P4 |
| FR-05 | TeamPool一次发放、FEFO Grant消费、过期/撤销不退款 | P5 |
| FR-06 | 四级数据、双人治理审查、出站矩阵和例外 | P6 |
| FR-07 | 独立容量池管理、双身份真正出站、JSON/SSE、唯一Permit、可信用量账务 | P7 |
| FR-08 | 同逻辑模型多个Deployment健康路由/限一次重选 | P8 |
| FR-09 | 用户明确同意后的LOW保底、公司积分池与自部署Token补贴 | P9 |
| FR-10 | 双人紧急审批、专用额度与4:2:1授位 | P10 |
| FR-11 | 五指标FinOps、Inbox/Manifest、双Build与运营成本审批 | P11 |
| FR-12 | 多Gateway/Core、故障演练、可审核的全局阻断/恢复 | P12 |

## 3. 功能规则与边界

- 任何调用都不能绕过**双Key、两ACL、数据策略、适用价版本、额度、容量和Core唯一Permit**。
- 逻辑模型身份与真实Deployment分离；LOW跨模型变更需要受控批准和用户显式同意；P7/P8只同型号。
- 员工POINTS与供应商采购原币种是两本不同账，不经汇率转换；未知供应商费用为PENDING而非0。
- 高优先级只影响**未获得ACTIVE的等待队列**，不抢占物理推理任务或账务额度。
- Provider已经可能收到请求时，网络错误/ACK丢失不授权第二次出站。
- Grant发行一次扣TeamPool，使用时扣Grant；Grant撤销/过期不把已发行积分退回原池。
- FinOps五指标分别为员工POINTS、Team发行POINTS、第三方采购成本估算、企业自部署运营成本及LOW本地Token；跨单位/币种不加总。
- 合规不由客户端自报：未知敏感分类默认至少INTERNAL、疑似密钥/RESTRICTED禁止出站、CONFIDENTIAL仅受限批准自部署且非流式缓冲审查。
- 生产部署所需供应商合同、Tokenizer、真实价格、配额、地区、敏感词与运营票据由管理员审批提供；缺失时拒绝实际收费，不以测试常数替代。

## 4. 非功能需求（NFR）

| 领域 | 必需能力 | 验收证据 |
|---|---|---|
| 安全 | 全阶段精确Gateway方法白名单、内外服务身份隔离、AES-GCM、Secret不回显、SSRF阻断 | 真实负向E2E |
| 一致性 | Core资金、Reservation、Ledger和Outbox同库事务；幂等写有物理约束 | MySQL并发/重放测试 |
| 可恢复 | Redis runId/epoch、唯一Permit、UNKNOWN收费风险保留、FinOps Manifest重建 | SIGKILL/断网和补拉测试 |
| 可观测 | 请求ID、无Prompt/Key的结构化日志、资金/租约/Outbox状态与延迟 | 真实指标与告警 |
| 工程 | Java21、Boot4.1.1、Gateway MVC、MyBatis-Plus Boot4、MySQL8.4、Redis8.4、RabbitMQ(P11) | 可复现PoC、CI与容器镜像版本 |
| 可维护 | 六角色有限授权、阶段门禁、文档权威和ADR | Phase完成报告与Code Review |

## 5. 验收优先级

功能相关失败必须fail-closed，资金不能超发，未知不能报0。具体逐阶段测试详见 [测试策略](./09-test-plan.md) 和 `tests/` 目录。正式业务实现与对应安全/并发/异常测试同一阶段一次交付。

## 6. 需求 → 交付规格 → 验收的可追溯矩阵

本矩阵用于避免FR仅有一句描述就交给执行AI自行补表或接口：实现范围以Phase为界，精确字段以版本SQL/API为界，失败/安全行为以协议与专项测试为界。链接指向已设计文档而非实际通过证据。

| 需求 | Phase | 迁移/领域权威 | API/协议及真实测试入口 |
|---|---|---|---|
| FR-01 | [P1](./phases/p01.md) | [对应版本SQL](./database/core-v01.md) | [API](./api/p01-p03.md) / [测试](./tests/cases-p01-p03.md) |
| FR-02 | [P2](./phases/p02.md) | [对应版本SQL](./database/core-v02.md) | [API](./api/p01-p03.md) / [测试](./tests/cases-p01-p03.md) |
| FR-03 | [P3](./phases/p03.md) | [对应版本SQL](./database/core-v03.md) | [API](./api/p01-p03.md) / [测试](./tests/cases-p01-p03.md) |
| FR-04 | [P4](./phases/p04.md) | [对应版本SQL](./database/core-v04.md) | [API](./api/p04-p06.md) / [测试](./tests/cases-p04-p07.md) |
| FR-05 | [P5](./phases/p05.md) | [对应版本SQL](./database/core-v05.md) | [API](./api/p04-p06.md) / [测试](./tests/cases-p04-p07.md) |
| FR-06 | [P6](./phases/p06.md) | [对应版本SQL](./database/core-v06.md) | [API](./api/p04-p06.md) / [测试](./tests/cases-p04-p07.md) |
| FR-07 | [P7](./phases/p07.md) | [对应版本SQL](./database/core-v07.md) | [API](./api/p07-p12.md) / [测试](./tests/cases-p04-p07.md) |
| FR-08 | [P8](./phases/p08.md) | [对应版本SQL](./database/core-v08.md) | [API](./api/p07-p12.md) / [测试](./tests/cases-p08-p12.md) |
| FR-09 | [P9](./phases/p09.md) | [对应版本SQL](./database/core-v09.md) | [API](./api/p07-p12.md) / [测试](./tests/cases-p08-p12.md) |
| FR-10 | [P10](./phases/p10.md) | [对应版本SQL](./database/core-v10.md) | [API](./api/p07-p12.md) / [测试](./tests/cases-p08-p12.md) |
| FR-11 | [P11](./phases/p11.md) | [对应版本SQL](./database/finops-v01.md) | [API](./api/p07-p12.md) / [测试](./tests/cases-p08-p12.md) |
| FR-12 | [P12](./phases/p12.md) | [对应版本SQL](./database/core-v11.md) | [API](./api/p07-p12.md) / [测试](./tests/cases-p08-p12.md) |

跨阶段必须同时核对[全局不可变条件](./04-domain-model.md)、[可信运行协议](./07-runtime-protocols.md)及[设计审查回归](./tests/design-audit-regressions.md)。需求如果改变用户身份、会计单位、收费/重试或安全拒绝语义，先修本SRS与对应ADR，再调整数据库、API、Phase及测试；**禁止只改一份文档让其他合同互相冲突**。
