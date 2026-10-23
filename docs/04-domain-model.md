# 领域模型与业务不变量

## 1. 实体及关系

```text
Team 1 ── * Employee ── * ManagementCredential
  |              |           EmployeeCredential
  |              └─ * EmployeeAgentPermission * ── Agent ── * AgentModelPermission * ── LogicalModel
  |                                            |                                  |
  └─ TeamPool ── * TeamGrant                     └─ AgentApiKey                  ├─ ModelPointsPriceVersion
                                                                              └─ * ModelDeployment ── Provider
                                                                                         ├─ PurchasePriceVersion
                                                                                         ├─ ProviderCredential
                                                                                         └─ CapacityPoolPolicy
Employee + Agent + LogicalModel → Invocation → FinanceOperation → Reservation/Ledger
                                          └─ DispatchPermit → ResultReportReceipt/ReconciliationCase
Core Outbox → FinOps Inbox → Fact → DailyAggregate / ReadModelState
```

实际外键和迁移顺序以 [数据库设计](./05-database-design.md) 为准；这里是业务理解视图，不是替代SQL的字段清单。

## 2. 限界上下文和主写入方

- Identity：Employee、Team、RoleAssignment、Bootstrap及三类凭证；Core唯一写主。
- Catalog：Provider/LogicalModel/Deployment与双价修订、Agent授权；Core唯一写主。
- Funding：普通周额度、采购预算及分区、Team发行、Grant、Reservation、Ledger、Outbox；Core同MySQL。
- Governance：PolicyDraft、Rule、批准矩阵、例外、敏感事件审计；Core当前Policy版本权威。
- Invocation：一次请求、Policy/Price/实际部署冻结快照、单Permit、结果及未知风险；Core唯一写主。
- FinOps：只消费已确认Core事实且独立维护运营成本、衍生快照和查询Scope；不写Core余额。

## 3. 不变量

| ID | 不变量 |
|---|---|
| INV-01 | agk_与empk_分别有效、EmployeeAgent与AgentModel ACL都成立；禁用角色/Agent/Team立即影响下一次认证 |
| INV-02 | 一个外部Invocation至多持有一张唯一DispatchPermit，ACK或HTTP504不会触发新Provider出站 |
| INV-03 | 只有已发布的逻辑POINTS价和实际Deployment原币种采购价可以冻结为财务快照 |
| INV-04 | 正常周积分、公司采购月预算与Grant余额不能突破真实MySQL约束；不存在部分提交 |
| INV-05 | 未经批准的数据出站为DENY，客户端永不能降低可信分级 |
| INV-06 | 跨模型降级需用户同意+管理员批准、真实资金；紧急需两个不同角色审批且不越过资金与治理 |
| INV-07 | 可信usage才允许SETTLED，无法证明未发送时不能释放已可能收费的风险 |
| INV-08 | FinOps事件至少一次但同一build/业务修订至多计入一次；缺源事件为UNKNOWN/DELAYED，不可FRESH |
| INV-09 | Redis Lease丢失/过期不证明物理任务终止；未知活跃任务须保守隔离 |
| INV-10 | P12全局BLOCK停止新Permit，但不能追溯取消已提交Permit可能产生的供应商成本 |

## 4. 周期、金额与模型分类

POINTS为DECIMAL(20,6)十进制字符串、采购币种DECIMAL(20,8)，不允许JSON double表示财务精确金额。周/月以管理员批准的公司IANA ZoneId界定并转换UTC存储；不同币种不自动换算。数据级别依次PUBLIC、INTERNAL、CONFIDENTIAL、RESTRICTED，缺可信标签至少INTERNAL。