# 运行时协议与一致性

## 1. P7 Runtime 正常流程

```text
Gateway schema + 双Key
  → Core Preflight身份/ACL/治理/HMAC版本
  → Redis ENQUEUE/PROMOTE获ACTIVE
  → Core直接INSPECT Redis + 当下ACL/Policy/Price/双预算
  → MySQL一次COMMIT(Invocation/Operation/Reservations/唯一Permit)
  → Gateway收到首次确定的Permit +临时ProviderSecret
  → 实际一次Provider JSON/SSE HTTP
  → Core可信结果报告/结算或PENDING
  → 同事务Ledger/Outbox（P11异步FinOps）
```

## 2. 最关键的失败情形

| 失败 | 正确行为 |
|---|---|
| Preflight拒绝、Token/预算上界不可证实 | 4xx/422，Provider HTTP=0 |
| Redis/核心PoolGuard不可验证 | fail-closed；不能凭客户端租约字段取得Permit |
| Core Permit 504/ACK丢失 | 查持久state；**不能**再向Provider发送或领取新Permit |
| 真正明确完全未发送 | PROVEN_NOT_SENT才可释放Reservation |
| 可能已发、Provider超时/500、客户端SSE断开 | UNKNOWN、PENDING_RECONCILIATION；不能记0 |
| 可信usage超出预留 | 真实OVERRUN风险与受影响币种采购预算阻断 |
| Redis失效但旧任务可能运行 | 保留quarantine，不能按TTL把物理GPU释放 |
| RabbitMQ重复投递 | FinOps Inbox幂等，非第二笔Fact |
| P12全局BLOCK | 停新Permit，不删除已提交Permit真实费用风险 |

## 3. 协议分册

- [鉴权/Canonical HMAC](./protocols/runtime-canonical.md)：P7 v1/P9 v2/P10 v3，Core独占请求摘要与密钥轮换。
- [安全治理](./protocols/governance.md)：四级分级、可信下界、RuleRegistry与例外。
- [Provider Adapter](./protocols/provider-adapter.md)：HTTP/SSE、真实最坏费用、可信Usage。
- [Redis Admission](./protocols/redis-admission.md)：Pool Guard/epoch、队列Lua、4:2:1与崩溃恢复。
- [FinOps异步协议](./protocols/finops-events.md)：Outbox/RabbitMQ/Inbox/Manifest/双Build。
- [P12恢复](./protocols/global-admission.md)：Core MySQL singleton、readiness evidence与滚动部署。
- [通用合同与审查修正](./protocols/common-contracts.md)：跨Phase冲突消歧及关键字段审查。

## 4. 不支持的捷径

不以字符数估Token、不靠Redis TTL判定GPU停止、不接受Client dataClass/费用报价、不允许Gateway把HMAC放进公开API、不把HTTP200无usage当已结清、不在持MySQL资金锁期间等待Provider/SSE/Redis/Scanner。