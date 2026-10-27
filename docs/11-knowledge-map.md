# Knowledge Map · AIGate 学习地图

本项目学习路径与真实开发Phase一致：先解释业务问题→最简单可运行方案→执行流程→核心原理→与类似方案区别→项目使用→测试失败→面试表述。**不要为了扩大技术栈而引入组件。**

| 学习主题 | 项目场景 | Phase | 必须能独立解释 |
|---|---|---|---|
| Maven/Spring Boot4/微服务边界 | Gateway+Core两进程 | P1 | 自动装配、依赖BOM、Gateway MVC与Core职责 |
| MySQL索引、FK、事务、CAS | Bootstrap/RBAC/价格 | P1/P3 | 唯一约束、行锁、死锁与乐观锁 |
| Spring Security/服务身份 | mgt_/agk_/empk_/svc_ | P1/P2 | 两Key+ACL、认证与授权区别、撤销时效 |
| AES-GCM/HMAC | ProviderSecret、RequestDigest | P3/P7 | 加密与摘要用途不同、密钥轮换/唯一值 |
| BigDecimal、资金原子性 | 周额度、原币种采购、Grant | P4/P5 | 精确预留/结算、超额与UNKNOWN |
| 安全治理/SSRF | 数据分级、出站、DNS重绑定 | P3/P6 | fail-closed、不同信息边界 |
| Redis Lua/队列/公平 | Pool ACTIVE、RPM、4:2:1 | P7/P10 | 为什么锁必须原子、epoch与物理资源差别 |
| HTTP/SSE与网络失败 | Provider JSON/SSE | P7 | half-close、背压、ACK lost不能重发 |
| 健康路由/熔断 | 同型号部署重选 | P8 | 硬过滤与评分分层、不能越过安全 |
| RabbitMQ Outbox/Inbox | Core到FinOps至少一次 | P11 | 消息不等于事务、幂等和Manifest恢复 |
| 多实例、可观测、灾备 | 栅栏/全局Block | P12 | SELECT FOR SHARE vs UPDATE、RTO/RPO事实 |

每阶段只有完成Code Review、测试检查、架构复盘和面试总结后才算真正完成学习闭环。

## 2. 按真实Phase记录而非预先假定掌握

每阶段维护以下六项学习证据：**业务问题**（为什么有此模块）、**最小主链**（请求到表及响应）、**新增机制**（若不用会怎样）、**边界/替代**（何时不用高级组件）、**运行证据**（测试与故障注入）、**可口述的技术选择**（收益与复杂度）。每项须引用已通过的提交或真实日志；仅设计阶段标DESIGNED，未运行标NOT_RUN。

| 阶段 | 最先讲明的业务问题 | 学习验收重点 |
|---|---|---|
| P0 | 为什么先固定架构/表/协议、再编程 | 解释需求→接口→DDL→Phase→测试追踪及最小PoC失败如何修ADR |
| P1～P3 | 谁能管理、谁能用哪个Agent/Model、密钥和价格由谁控制 | Spring Security作用域、MySQL约束、密钥只显示一次、加密vs哈希 |
| P4～P6 | 谁承担资金、Grant归谁、哪些数据允许出站 | 事务/资金不变量、审批分权、Policy发布CAS与Scanner失败默认拒绝 |
| P7～P10 | 一次HTTP为何能重复收费，容量为何不能靠本地Semaphore | Redis原子性/epoch、Provider SSE、唯一Permit、LOW和紧急策略的资金与授权 |
| P11～P12 | 读模型可否延迟、全局恢复为何必须有受信证据 | Outbox/Inbox、重复与漏消息、MySQL锁与多进程故障边界 |

每完成一个Phase在[Progress](./10-progress.md)记录已获得的证据，然后更新[面试记录](./12-interview-notes.md)，不要把设计中的未来经验写成亲自完成。


## P3 已落地的学习路线

1. `CatalogController → CatalogService/CatalogCredentialService → CatalogMapper`：HTTP白名单、当前RBAC、事务与DTO投影如何分工。返回强类型DTO，内部带密文DO不得直接序列化。
2. 价格草稿不切当前指针；发布时锁父资源并CAS，价格状态、父指针和审计一次提交。BigDecimal固定6/8位字符串，UNNECESSARY拒绝隐式舍入，原币种不做汇率换算。
3. HMAC是不可逆收据认证，AES-GCM是需要内部恢复的Secret保密；随机IV、AAD和版本化密钥分别解决重复nonce、跨行搬移密文和历史追验。
4. 普通配置、独立技术批准、P6数据治理、P7一次出站许可是不同事实。P3Manifest按完整规范配置摘要匹配，不能把evidenceRef字符串当签字批准。
5. DNS解析安全与真实网络连接安全不同。P3验证全部答案和有界等待；P7仍需连接固定IP、TLS主机验证、重定向与代理保护。

实际测试/限制见[Progress](./10-progress.md)；这些是本地测试经验，不代表生产供应商、性能或后续Phase能力。
