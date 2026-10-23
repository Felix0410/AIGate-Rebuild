# Architecture Decision Records（ADR）

> 本目录只存**现在有效的重大架构选择**。不按聊天顺序追加散段，也不把已过时的临时草案与最终方案并列。本项目此前其它ADR探索可通过Git提交历史回看，不再作为实现权威。

| ADR | 决策 | 关系 |
|---|---|---|
| [ADR-007](./ADR-007.md) | Java21+Spring Boot4技术基线 | 适用架构/阶段 |
| [ADR-008](./ADR-008.md) | 从P1起Gateway/Core分离而非先单体 | 适用架构/阶段 |
| [ADR-023](./ADR-023.md) | Core同库财务、不可变价格版本与固定锁序 | 适用架构/阶段 |
| [ADR-025](./ADR-025.md) | Gateway白名单与Core独占请求HMAC | 适用架构/阶段 |
| [ADR-026](./ADR-026.md) | 设计冻结、兼容PoC和Phase真实集成测试分层 | 适用架构/阶段 |
| [ADR-027](./ADR-027.md) | 运行时CanonicalDTO版本和HMAC Key轮换 | 适用架构/阶段 |
| [ADR-028](./ADR-028.md) | 封闭治理RuleRegistry与默认拒绝 | 适用架构/阶段 |
| [ADR-029](./ADR-029.md) | 供应商可信Usage与费用硬上界 | 适用架构/阶段 |
| [ADR-030](./ADR-030.md) | Redis容量池共享Lua与Epoch恢复 | 适用架构/阶段 |
| [ADR-031](./ADR-031.md) | Core Outbox+RabbitMQ+FinOps Inbox与双Build | 适用架构/阶段 |
| [ADR-032](./ADR-032.md) | Core MySQL全局阻断与Readiness证据 | 适用架构/阶段 |

所有ADR严格包含背景、问题、候选、最终选择、理由、优点、缺点和对未来的影响。状态“设计已选定”**不等于用户P0 FROZEN或兼容PoC PASS**。

修改原则：若实际Phase发现原取舍不成立，先保留历史ADR并提出新决策/状态变化，由总控修改权威文档；本地AI不得静默替换技术栈。
