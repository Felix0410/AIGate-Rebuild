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