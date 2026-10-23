# 开发协作与文档治理

## 1. 单一事实来源

文档以主题而非聊天/时间顺序组织：Requirements → Architecture → Domain → Database → API → Protocols → Roadmap → Module → Tests → Progress → ADR。一个字段只有一个权威持久定义；阶段任务引用规范而不拷贝多份可漂移版本。旧追加式D01～D56/R01～R10文档在Git历史中留存，**不继续作为最新实现指令**。

## 2. 职责边界

- **总控（ChatGPT）**：所有需求、数据、接口、协议、测试、ADR的设计和仓库文档维护；发布精确阶段任务，审查代码/提交结果。
- **本地AI**：只根据已发布实施合同写/测当前Phase的代码，包括迁移SQL、Java、Redis Lua、Docker、CI等；不得自己选架构/增加业务字段/写正式docs。
- **用户**：判断Phase启动/完成，批准重大架构决策。

## 3. 不允许的行为

本地AI不得把未定实体字段假设成默认值、把UNKNOWN费用记0、把Redis重启后的空集合当物理资源空闲、跳过SecurityApprover、自动额外调用付费Provider、修改已运行Flyway版本、删除其它Phase设计、扩大Gateway allowlist或进入下一Phase。

如发现合同自相矛盾，停止相关子任务，提交「业务场景→冲突的两个定义→实际DB/API/网络证据→受影响测试」，由总控更新规范及必要ADR。

## 4. Phase交付

每个实施包必须交付：修改文件清单、接口与迁移、运行命令、pass/fail/skip、风险与技术债、Code Review、知识点和面试表述。该Phase结束后等待用户批准再推进。

## 5. 规范状态约定

`DESIGN_REVIEWED` = 静态检查；`NOT_RUN` = 未运行；`PASS/FAIL/BLOCKED` = 有日志的工程检查；`FROZEN` = 用户确认的稳定规范。两者不能混用。

## 6. 文档审查清单与交付输入

当前全部规范的文件名和职责以[文档总清单](./document-inventory.md)为准。启动某Phase时，执行包至少包含该Phase的`pNN.md`、首次需要的Core/FinOps数据库版本、相应API与RPC、受影响协议、专项测试分册和已有未关闭技术债。执行AI不能只读`README.md`或从历史Dxx编号自行拼业务。

Phase完成后的真实PR/提交与测试记录由总控审阅、更新Progress/Architecture/必要ADR/知识与面试表达；用户确认前不得修改下一Phase为STARTED。静态设计、测试NOT_RUN和真实PASS需要明确区分。
