# Interview Notes · 面试表达

以下是**设计说明话术**，不是个人实际运行成绩。没有真实压测/测试日志前，禁止用“实现了”“线上验证了”“吞吐达到X”描述未做的工作。

## 1. 项目一句话

AIGate是企业内部可治理、可计费的AI调用网关。它用Gateway/Core/FinOps分阶段部署，将员工双身份、数据出站策略、逻辑模型/真实Provider、双账户资金、短期容量与可靠成本报表统一成可审计调用链路。

## 2. 高频架构问题

**为什么一开始采用Gateway和Core两个服务？** 外部网络代理与内部资金/权限数据库是不同信任边界；Gateway可能长时间维持SSE连接，Core需要短事务保证唯一出站许可和资金一致。代价是内网RPC/TLS、故障和版本协商，已在ADR决定。

**为什么Core不是五个微服务？** 额度、Permit、Ledger、Outbox需要同一个MySQL事务，不因技术栈丰富而拆分不可原子资金事实。FinOps的异步读模型与资金写主不同，P11才值得分离。

**Redis租约能证明GPU安全空闲吗？** 不能。Redis只能判定短期软件准入；物理推理可能在TTL后继续运行，所以要runId/epoch/Task证据与隔离，不能盲重放Provider请求。

**如何避免重复收费？** Core有唯一Invocation→Permit记录；Gateway只接受第一次明确Permit结果，ACK丢失只能查状态。无法对外部网络提供物理Exactly Once，因此必须保留UNKNOWN、对账与预算上界。

**为什么使用Outbox + RabbitMQ + Inbox？** Core同事务存账本和Outbox，Broker至少一次会有重复；FinOps用Inbox去重、Manifest找漏、双build重建。相比FinOps跨库直查，更能保持边界，但引入一致性校对复杂度。

**为什么P12引入全局Core MySQL单例？** 多Core/多Gateway需要可审计且跨进程的收费准入开关，不能信本地变量和Redis冷启动。共享行锁保证正常Permit并行，运营Block独占锁给出明确先后。

## 3. 面试记录模板

问题 → 业务场景 → 最初可行设计 → 真实遇到的故障或证据 → 权衡过的方案 → 当前选择及代价 → 亲自执行的测试及结果 → 技术债/后续演进。

每完成一个Phase，将真实测试结果和Code Review发现补到此文档；不要把现在的设计候选误写成已上线项目。