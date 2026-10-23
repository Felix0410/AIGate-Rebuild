# AIGate-Rebuild · 项目总览

> 文档状态：P0 设计审查候选；尚未通过工程 PoC；P1～P12 尚未开始编码。  
> 责任：总控确定并维护设计；本地 AI 只实现、测试、提交运行证据。

## 1. 产品定位

AIGate 是企业内部统一 AI 调用与治理平台。它把员工、Agent、逻辑模型、真实供应商部署、额度和公司成本纳入同一调用闭环，提供受控的 JSON/SSE Chat Completions 入口。

### 1.1 目标

- 每次调用可说明**谁**（Employee+Agent）、**调用什么**（LogicalModel/Deployment）、**数据能否出站**、**如何取得容量**、**由谁支付**以及**如何结算**。
- 支持第三方 API 与企业自部署推理；自部署有真实容量、周 Token 补贴和可单独审计的运营成本。
- 资金事实以 Core MySQL 为准；统计事实可异步更新，但不得反向修改资金。
- 作为 Java 后端实习/校招项目，能解释设计动机、事务与失败边界、阶段演进及实际验收证据。

### 1.2 不在首版范围

不支持通用多模态、动态上传脚本/任意 DLP 规则、随意绕过账务的 Provider 重试、真正跨供应商的物理 Exactly Once、自动将 POINTS 转为币种金额、自动拆分更多微服务。能力扩展必须有真实需求与 ADR。

## 2. 核心业务链路

1. 平台管理员初始化 Team/Employee 和管理身份。
2. 创建 Agent、签发双运行凭证、授予 Employee→Agent、Agent→LogicalModel 权限。
3. 配置逻辑模型、实际 Deployment、已发布积分价/原币种采购价及 Provider 凭证。
4. 配置员工周额度、Agent 周额度、公司月采购预算及经授权的 Team Grant。
5. 治理模块检查全部消息、数据分级、Provider/地域与批准矩阵。
6. Gateway 在 Redis 受控容量池排队获租约；Core 做最终双身份/ACL/Policy/预算事务，并签发唯一 Permit。
7. Gateway 最多一次出站并代理 JSON/SSE；可信 Usage 在 Core 同库结算，不确定费用保留风险与对账案件。
8. P8～P10 扩展同型号路由、经同意的降级、两人审批紧急任务和 4:2:1 加权授位。
9. P11 从 Core Outbox 经 RabbitMQ 投影独立 FinOps；P12 验证多副本与受控灾难恢复。

## 3. 交付范围与阶段

P1～P12 精确范围见 [Roadmap](./08-roadmap.md)；每阶段独立工作包见 [Module Design](./phases/README.md)。**P1只有Gateway/Core两个独立应用与6张Core表**；FinOps第三服务和独立数据库P11才上线；P12才新增Core V11全局状态表。

## 4. 设计版本和真实性

本套文档是 2026-10-23 基于 D01～D56、R01～R10 重组后的**唯一规范候选**。完整设计共 **Core55表（V1～V11）＋FinOps7表（V1）＝62表**。全部 SQL、Java/Spring、Redis Lua、RabbitMQ 和 Provider E2E 运行状态为 **NOT RUN**，不能把静态检查当兼容通过。