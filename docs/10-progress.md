# Progress · 当前阶段与决策

> 更新日期：2026-10-26。P1/P2已有提交，用户已启动P3并明确授权补齐相关设计、文档和实现。P3现为 **IMPLEMENTED / LOCAL_PASS / PENDING_USER_ACCEPTANCE**；P4尚未启动。

## 1. 当前实际状态

| 项目 | 当前证据与状态 |
|---|---|
| P1组织、管理凭证、Bootstrap/RBAC | 已实现并在本轮完整回归通过；此前用户已提交 |
| P2双运行Key、Agent/Employee ACL | 已实现，基线提交 `d653f87`；本轮15组真实集成回归通过 |
| P3七表、Catalog管理、双价与AgentModel ACL | 已实现，V3规范DDL逐字匹配，真实干净迁移/历史升级通过 |
| P3 ProviderSecret与永久签发收据 | AES-256-GCM、独立HMAC、历史keyId追验、4096UTF-8字节及篡改边界通过 |
| P3审批与eligibility | 受保护配置清单、服务端ID、双身份/双ACL及当前目录条件验证通过；不授予P7出站许可 |
| 当前Java/DB组合 | Java21.0.8、Boot4.1.1、Cloud2025.1.3、MyBatis-Plus3.5.17、MySQL8.4本地构建及双JVM实测通过 |
| 完整测试 | **79 run / 79 passed / 0 failures / 0 errors / 0 skipped**；30单测、49真实集成；2026-10-26本地执行 |
| IDEA | 构建PASS；37个修改Java/XML文件无ERROR；SQL方言、测试自定义Header等非阻塞提示保留，未全局关闭检查 |
| Git / CI / 用户签收 | 当前P3改动未提交；远程CI未运行；P3整体签收待用户确认 |
| Redis、RabbitMQ、真实Provider收费/JSON/SSE、FinOps | 尚未在本阶段实现或验证，按P7/P11及后续阶段推进 |

**真实命令**：

```sh
DOCKER_HOST=unix:///Users/felix/.docker/run/docker.sock ./mvnw -B -ntp clean verify
```

最终 `BUILD SUCCESS`，耗时5分27秒。测试包括原45项回归、P3的15项边界单测、17组真实Catalog集成和2组真实迁移。MySQL镜像固定digest，Core/Gateway独立JVM，另有Compose部署/故障回归；V1/V2旧数据和checksum保持，Core V3新增7表（共17业务表及Flyway历史）。实际DB、Key、审批输入均为隔离测试数据，开发者数据库未修改。

报告位于被Git忽略的 `aigate-e2e-tests/target/p3-delivery.json`、`p3-verify.log`、Surefire/Failsafe XML及脱敏应用日志；`target`可被clean删除，不能把这些临时报告当作Git提交记录。基线SHA为 `d653f87`（完整SHA见报告），本阶段还没有新提交。

**本轮自审与限制**：

- P3公开新增24个method/path，总56个；内网GET eligibility不经过Gateway，公开Chat/秘密领取仍关闭。Gateway胖JAR不得包含数据库/Core实现依赖。
- 审批输入是Core专用受保护文件，变更需受控重启、绝对时间到期失效；缺文件默认无批准。普通HTTP配置和evidenceRef不能自我批准。该机制不能代替P6机密数据出境审查。
- 管理写沿用P1协调锁，再锁父记录/凭证，审计同事务；正确但保守串行，没有做管理写吞吐压测。
- P3只解析DNS，不打开Provider HTTP。JDK/OS DNS缓存可能延迟外部变更观测；P7仍需真实连接IP固定、TLS/重定向/代理验证。不能把P3无出站的测试声称为已验证P7网络栈。
- 当前/历史密钥来自受保护配置，尚未接生产KMS或真实供应商。历史材料丢失会安全拒绝解密或重放，不允许重新签发绕过。
- 本次按用户授权同步API/RPC、Adapter协议、阶段文档、ADR-033、架构、知识与面试材料。未改变三服务边界、V3七表或未来阶段顺序。

## 历史静态设计记录（2026-10-23）

以下保留当时的静态审查和环境事实；其中NOT_RUN/BLOCKED描述的是历史时点，不覆盖上方2026-10-26的真实结果。

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

## 5. 开发执行流程就绪（2026-10-23）

已在[协作规范](./13-collaboration.md#7-实际开发工作流唯一执行闭环)固定每个Phase的任务下发、最小Happy Path、本地验证、Git提交、总控Code Review、技术讲解、修复反馈、阶段关闭和用户签收；P0由用户按IDEA操作指南亲自执行COMP01～10。**仅流程文档已完成；P0工程构建与运行仍NOT_RUN，P1尚未启动。**

## 6. 协作方案更新：P0亲手使用IDEA，后续整Phase交付（2026-10-23）

- 用户决定**P0亲自使用IntelliJ IDEA逐步搭建与验证**，不委托本地AI生成工程。已建立[IDEA手把手P0指南](./guides/p00-idea-hands-on.md)，真实运行仍NOT_RUN；现阶段仅开始IDEA克隆和JDK准备，不将操作指南当运行证据。
- P1～P12默认一份整Phase合同、一次完整代码交付及集中Review；原E01～E04仅代表同一交付内的检查维度，不作为分次提交或分批代码生成。只有真正的兼容/安全/并发阻塞才安排少数中间检查。
- P0旧P0-A/B/C拆分不再作为执行包；COMP01～10仍逐项验收，IDEA八个节点只是教学操作顺序。

## 7. 工作分工

- 总控：所有架构/功能/字段/接口/权限/测试规格设计，以及GitHub正式文档。
- 本地AI：P0不参与；P1～P12收到整Phase合同后按规范写源码、Flyway、Redis Lua、测试和运行日志。发现冲突只反馈，不擅改正式docs/架构。
- 用户：亲自在IDEA完成P0兼容PoC；批准P0冻结，后续Phase交付后完成审查确认，选择是否进入下一Phase。

## 8. 阶段结束审查顺序

功能检查 → Code Review → 测试检查 → 架构检查 → 技术债 → Progress → Architecture → ADR（如需）→ 知识点 → 面试表达，等待用户确认后下一Phase。

## 9. 当前剩余门禁

1. 用户审查P3设计、改动、实际报告并决定签收/提交；未签收前不自动启动P4。
2. 提交后由真实远程CI执行相同verify；本地PASS不等于远程CI已经运行。
3. 生产安全运维提供真实供应商、受保护密钥和审批证据；P6/P7实现真实数据治理、资金许可与网络连接安全后，才能讨论真实收费出站。

## 10. 变更记录

- 2026-10-21/22：D01～D44，直接微服务、完整业务、迁移与RPC基本规则。
- 2026-10-22/23：D45～D56，DTO、Provider/Redis/MQ/全局恢复合同。
- 2026-10-23：R01～R10静态审查发现的数据库与API闭环修订。
- 2026-10-23：整理为以功能/服务/Phase为索引的可交付开发规范；原追加式文档已不在当前仓库树，仍可从Git历史追溯。
- 2026-10-26：按用户授权完成P3合同闭合、编码和79项本地测试，新增ADR-033；待用户验收及Git提交。
