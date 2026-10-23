# Progress · 当前阶段与决策

> 更新日期：2026-10-23。当前是**P0设计文档重组与最终评审**，尚未签收FROZEN。P1～P12 NOT STARTED。

## 1. 当前状态

- 2026-10-23：补齐连贯的[开发设计说明书](./00-development-specification.md)和[P0独立实施/冻结检查](./phases/p00.md)，并合并P7～P12 API历史重复章节、规范运行HMAC章节标题；这些属于**文档整理**，不改变已批准数据库表数/阶段边界。
- 历史旧追加文档在当前Git树中已不存在，无需再进行破坏性删除；若发现未承接规则，应先移入对应专题规范再清理历史。

| 项目 | 状态 |
|---|---|
| P1～P12业务/架构/62表及主API详细设计 | 已覆盖，完成一轮静态设计审查 |
| D01～D56与R01～R10 | 有效合同已迁移至主题规范；旧逐次追加日志不再作为入口 |
| Java21/Boot4/GatewayMVC/MyBatis-Plus组合PoC | 官方版本矩阵已核对；实际Maven/双Jar/HTTP **NOT RUN** |
| MySQL8.4/Flyway真实迁移 | NOT RUN |
| Redis Lua/Provider SSE/RabbitMQ/FinOps | NOT RUN |
| 用户P0最终设计签收 | PENDING |
| 本地AI实际业务代码 | 尚未启动 |

## 2. 本轮文档静态核查证据

2026-10-23 对仓库 main 进行文档级核验（此记录不是自动化测试报告）：

| 核验项 | 实际结果 | 限制 |
|---|---|---|
| Markdown 文档引用 | 70 个 Markdown 文件逐一读取并检查相对路径，未发现缺失目标 | 仅核验相对路径是否存在，不代表所有锚点/语义正确 |
| 数据表声明 | Core V01～V11 检出55个 CREATE TABLE；FinOps V01 检出7个；总62个 | 基于设计SQL文本，不是MySQL执行 |
| 表名唯一性 | 62个CREATE TABLE名称无重复 | 未以数据库引擎验证DDL语法/FK/CHECK/迁移升级 |
| 历史追加文档 | 当前Git树未发现旧Dxx/Rxx草稿文件 | Git历史继续保留，内容承接需在Phase评审中持续复核 |
| 规范整理 | 正式开发总说明、P0冻结检查、API恢复章节去重、协议标题标准化已提交 | P0正式冻结与真实工程PoC尚未完成 |

上面只有**文档静态检查**，Java构建、Flyway、Gateway、Redis、RabbitMQ及真实Provider各自仍是NOT_RUN。P0冻结不能因本表自动变成PASS。

## 3. 第二轮全量复核（2026-10-23）

- 已逐份检查原70份Markdown；更名Phase模块索引并同步引用，新建[全量文件清单](./document-inventory.md)。
- P1～P12实施文件加入分项任务与测试证据，ADR-007/008/023/025～032加入验证/复审门槛；Requirements/Architecture/Domain/Knowledge/Interview文档补充追踪、失败边界与证据纪律。
- 12份数据库设计SQL文本：62个不同CREATE TABLE，136处REFERENCES目标均存在，13个ALTER目标均存在且建表先后顺序未发现前向引用。以上是**静态检查**，仍非实际MySQL/Flyway通过。
- 规范衔接：修订Gateway/内部RPC的schema v1/v2/v3说明和P6内置RuleRegistry已定义事实。下一门禁仍是P0真实PoC和用户签收。

## 4. P0技术兼容性静态复核（2026-10-23）

- 已根据Spring、MyBatis-Plus、MySQL、Maven官方资料形成[P0兼容性矩阵及COMP-01～10最小PoC执行合同](./tests/p00-technology-compatibility.md)。基线Boot4.1.1 + Cloud2025.1.3 + Gateway5.0.3 Server WebMVC + MyBatis-Plus3.5.17，保留Java21、MySQL8.4/Flyway和MavenWrapper3.9.16。
- 发现并明确：Gateway5.x新Starter及`spring.cloud.gateway.server.webmvc.*`新前缀；MyBatis-Plus的Boot4专用Starter；Boot4默认Jackson3和Flyway需`flyway-mysql`；修订ADR-007中的Cloud 2025.1.2为2025.1.3安全维护版。
- 现场执行环境只有JDK21，缺Maven与Docker，且无法连接Maven Central，因此P0构建/双Jar/MySQL/SSE真实验证均**NOT_RUN/BLOCKED**。此报告不完成P0门禁，不启动P1。

## 5. 工作分工

- 总控：所有架构/功能/字段/接口/权限/测试规格设计，以及GitHub正式文档。
- 本地AI：收到明确当前Phase实施包后按规范写源码、Flyway、Redis Lua、测试和运行日志。发现冲突只反馈，不擅改正式docs/架构。
- 用户：批准P0冻结，Phase交付后完成审查确认，选择是否进入下一Phase。

## 6. 阶段结束审查顺序

功能检查 → Code Review → 测试检查 → 架构检查 → 技术债 → Progress → Architecture → ADR（如需）→ 知识点 → 面试表达，等待用户确认后下一Phase。

## 7. 剩余有效门禁

1. 审查新主题文档是否准确覆盖已确定规则、API/表数量、Phase依赖及最新修订。
2. 真实Java21/Boot4.1.1/Gateway MVC/MyBatis-Plus/MySQL8.4/Flyway最小工程兼容PoC，遇到不兼容总控重新决定技术版本/迁移方案。
3. P0冻结由用户最终签收；其后才可给本地AI正式P1实施包。首次P7/P11/P12的全部故障测试仍归该Phase真实交付。

## 8. 变更记录

- 2026-10-21/22：D01～D44，直接微服务、完整业务、迁移与RPC基本规则。
- 2026-10-22/23：D45～D56，DTO、Provider/Redis/MQ/全局恢复合同。
- 2026-10-23：R01～R10静态审查发现的数据库与API闭环修订。
- 2026-10-23：整理为以功能/服务/Phase为索引的可交付开发规范；原追加式文档已不在当前仓库树，仍可从Git历史追溯。