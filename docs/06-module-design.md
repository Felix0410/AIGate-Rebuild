# 模块设计 · P1～P12实施合同索引

## 1. 如何使用这套文档

本目录按**功能模块**组织，不再按D编号追加。每个Phase有一份独立的实施文件（Business Goal、Scope、Main Flow、Architecture、Tasks、Acceptance Criteria、Knowledge Points）；它引用同一套数据库/API/协议权威，不在不同地方复制价格或状态字段。

本地AI每次只获取当前已批准Phase的文档及相关数据库/API/测试链接。若一个字段在项目实际迁移/接口中存在冲突，先报告总控更改正式设计，不在实现端猜测。

## 2. 模块目录

| Phase | 业务目标 | 数据库归属 |
|---|---|---|
| [P1](./phases/p01.md) | 组织管理/双应用 | Core V1 按数据库目录 |
| [P2](./phases/p02.md) | Agent双Key/ACL | Core V2 按数据库目录 |
| [P3](./phases/p03.md) | Catalog/双价/Provider Secret | Core V3 按数据库目录 |
| [P4](./phases/p04.md) | 周额度/采购预算/账本 | Core V4 按数据库目录 |
| [P5](./phases/p05.md) | TeamPool/Grant | Core V5 按数据库目录 |
| [P6](./phases/p06.md) | 数据治理/安全审批 | Core V6 按数据库目录 |
| [P7](./phases/p07.md) | 实际Chat JSON/SSE/Redis Permit | Core V7 按数据库目录 |
| [P8](./phases/p08.md) | 同模型路由健康 | Core V8 按数据库目录 |
| [P9](./phases/p09.md) | 受控LOW保底 | Core V9 按数据库目录 |
| [P10](./phases/p10.md) | 两人紧急审批/4:2:1 | Core V10 按数据库目录 |
| [P11](./phases/p11.md) | FinOps/MQ/读模型 | FinOps V1 7张 |
| [P12](./phases/p12.md) | 多实例/全局恢复 | Core V11 1张 |

## 3. 依赖和不可逆事实

- P1先两个独立Java服务；P2双运行Key；P3双价格和ProviderSecret；P4资金账户；P5 Grant；P6治理；**只有P7才发送真实Provider**。
- P8依赖P7同逻辑模型；P9引入经同意的跨模型降级；P10为经审批的紧急请求。LOW/EMERGENCY不绕过当前Permission、Policy和财务。
- P11才增加FinOps服务/RabbitMQ/独立数据库；P12新增Core V11全局恢复单例表。
- 已发出可能收费的Provider请求不允许因网络错误自动重试；不可信usage留PENDING，Redis TTL不能证明物理GPU停止。
- 所有功能都须从当期真实业务和故障场景开始，不能一开始引入未来Phase未需要的中间件。

## 4. 阶段关闭

完成一轮功能检查、Code Review、测试、架构、债务、进度、ADR（如需）、知识点、面试说明并交用户签收；未经确认不自动进入下一Phase。

查看[Roadmap](./08-roadmap.md)、[测试策略](./09-test-plan.md)与[协作规则](./13-collaboration.md)。
