# Roadmap · P0～P12

## 1. 阶段原则

P0先把全部业务设计完成并由用户签收；工程可行性PoC必须有真实记录，不能把后续P7/P11/P12的集成测试倒置为P1编码前置。每个Phase原则上一次完整实施、一次最终Git交付和Review，而非按E01～E04分割成多轮；保留当期全部参数校验、权限、异常、幂等、测试与基本观测；结束后Code Review、测试、架构检查、技术债/ADR/学习与面试总结，并由用户明确确认再进入下一Phase。

| Phase | Business Goal | Scope / 新对象 | 阶段重点 |
|---|---|---|---|
| P0 | 全平台规范与可验证工程基线 | 业务/架构/API/DDL/协议/测试/ADR | 用户冻结、真实最小PoC |
| P1 | 组织和管理入口 | Gateway+Core双Jar、六表、Bootstrap/Team/Employee/RBAC | 双进程网络+Admin安全 |
| P2 | Agent可被员工安全使用 | Agent/双运行Key/EmployeeAgent ACL四表 | 双身份、签发/轮换/吊销 |
| P3 | 模型目录与定价安全登记 | Provider/Model/Deployment/双价/Secret七表 | AES-GCM、SSRF、价格CAS |
| P4 | 普通额度与真实双账户 | Core十表周积分/采购币种/账本 | MySQL锁序/Outbox |
| P5 | 团队发放点数 | Pool、Grant、幂等发行三表 | FEFO与跨Team事实 |
| P6 | 数据出站治理 | 七表/角色CHECK/封闭RuleRegistry | 分级/批准/例外/审计 |
| P7 | 首次真实Chat JSON/SSE | 五表/CapacityPool/Redis/Permit | 真实出站一次、Usage/未知账 |
| P8 | 同型号健康路由 | 两表与Deployment受控健康修订 | 安全硬过滤/有限重选 |
| P9 | 经授权LOW和本地补贴 | 五表/Invocation后续ALTER | 用户同意与独立上限 |
| P10 | 两人批准紧急任务 | 五表/Invocation后续ALTER | 专用资金、4:2:1 |
| P11 | 独立FinOps | 第三进程/finops_db七表/RabbitMQ | 受控scope、源Manifest、真实费用 |
| P12 | 多实例故障恢复 | Core V11一表+AdminAudit ALTER | 全局停入场/恢复/压测 |

## 2. 每个阶段的完整工作包

[模块设计入口](./phases/README.md)给出每个Phase的Business Goal、Scope、Main Flow、Architecture、Tasks、Acceptance Criteria、Knowledge Points。单项的DDL、API和协议在其它规范分册，不让本地AI另选。

## 3. 开发实施与验收节奏

本路线表回答“每个Phase做什么”，而[开发执行流程](./13-collaboration.md#7-实际开发工作流唯一执行闭环)回答“每次任务如何交给本地AI、运行、提交、审查、讲解并验收”。每一Phase发布一份完整实施合同，先打通真实业务主链，再补当期校验、安全、并发和回归；阶段整体交付后集中Review，有真实阻塞或高风险才增加中间检查点。**P0由用户在IDEA亲手完成而非本地AI执行**：按[手把手P0操作指南](./guides/p00-idea-hands-on.md)逐步操作，且不要求各节点独立Git提交。P1～P12才由本地AI按Phase连续实现。PoC真实通过并经用户签收后才开启P1。

## 4. 当前进度

**P0：设计审查候选，尚未FROZEN；P1～P12均NOT STARTED。** 工程 PoC和真实SQL迁移全部 NOT RUN。详见[Progress](./10-progress.md)。