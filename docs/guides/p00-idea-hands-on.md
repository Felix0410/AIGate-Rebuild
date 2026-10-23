# P0 · IntelliJ IDEA 手把手工程搭建指南（本人操作）

> 2026-10-23 开发协作修订。本指南是P0的**唯一操作路线入口**。P0由项目负责人亲自在IntelliJ IDEA中完成，ChatGPT总控负责逐屏引导、解释原理、排错、Code Review和正式设计文档；**不把P0源码生成/搭建/运行委托给本地AI**。此处列出全过程；聊天教学**一次只推进当前操作节点**，用户反馈屏幕/结果后再进行下一节点。
>
> 当前工程状态：GitHub设计文档就绪，真正JDK/Maven双Jar/Flyway/MySQL/SSE未在用户电脑完成测试；不要把本指南的“预期结果”写成实际PASS。P1以后才恢复本地AI按Phase生成业务代码。

## 1. 为什么要亲手完成P0？

AIGate从P1开始需要两个独立Spring Boot Java进程、一个真实MySQL和受约束的Gateway入口。亲手在IDEA完成P0，可掌握仓库/Git、SDK、Maven/BOM、模块、Spring Boot启动、数据库迁移、MyBatis Mapper、调试、安全链与HTTP流式的真实运行过程。以后让AI编写业务时，用户能够独立启动、诊断、调试、评审其结果，不受单次生成代码的质量限制。

P0不是让用户先手写完整业务模块：核心是**最小可运行技术兼容PoC**。真正的Bootstrap/Team/Employee/RBAC管理接口留到P1；P0临时认证/测试端点只在隔离profile生效。

## 2. 开发准备与技术基线

| 项目 | 约定 |
|---|---|
| IDE | IntelliJ IDEA；以实际菜单英文/中文为准，界面可能因版本与操作系统略有不同 |
| 源码仓库 | `https://github.com/Felix0410/AIGate-Rebuild.git`，**必须克隆已有仓库，不新建同名独立Git仓库** |
| Java | JDK21，Project SDK、Maven运行JDK、编译target均核对Java21 |
| 构建 | Maven聚合父POM；最终固定Maven Wrapper3.9.16；初始可暂用IDEA Bundled Maven生成父POM/Wrapper |
| 后端 | Boot4.1.1、Cloud BOM2025.1.3、Gateway5.0.3 Server WebMVC、MyBatis-Plus3.5.17 |
| MySQL | MySQL8.4真实实例，Docker或本机均可，但必须可复现且与H2不同 |
| 数据迁移 | 只执行`docs/database/core-v01.md`的V1六表，P1之后才实现真实业务逻辑 |
| 未来中间件 | Redis P7，RabbitMQ/FinOps P11，不属于P0环境搭建必需项 |

版本、唯一Starter、JSON/Flyway细节以[技术兼容检查与COMP01～10](../tests/p00-technology-compatibility.md)及[ADR-007](../adr/ADR-007.md)为准。

## 3. 按IDEA操作的教学顺序（一步一验，不并行、不一次生成所有源码）

### 节点01 · 克隆和识别现有仓库（立即开始）

1. 打开IntelliJ IDEA欢迎页，选择 **Clone Repository** / **Get from VCS**。已经打开其他项目时走 **Git → Clone** 或 **File → New → Project from Version Control**。不同版本菜单文字可能有所变化。
2. URL填`https://github.com/Felix0410/AIGate-Rebuild.git`；Directory选择本机工作目录（如自行选择的`projects/AIGate-Rebuild`），点击Clone，按IDE提示登录GitHub/信任项目。
3. 在左侧Project视图检查`README.md`、`docs/`、`docs/phases/p00.md`与本指南。当前仓库尚未实施工程初始化时，**没有`pom.xml`是正常的**，不要误当成克隆失败。
4. 从IDEA的Git工具窗口确认当前分支与远程为本仓库，确保不是旧`AIGate`项目；不要在克隆后选`Create Git Repository`创建第二个仓库。

**完成标志**：IDEA能够打开项目、浏览本仓库文档、Git与remote已识别；暂不编辑Java文件。用户把成功或报错告诉总控，再进入节点02。

### 节点02 · 配置JDK21和IDEA Maven

1. 在IDEA打开 **File → Project Structure → Project**（常见快捷键Windows/Linux `Ctrl+Alt+Shift+S`，macOS使用菜单），将 **SDK** 设置为JDK21；若没安装用 **Add SDK → Download JDK** 获取21，确认是JDK而非JRE。
2. 打开 **Settings/Preferences → Build, Execution, Deployment → Build Tools → Maven**；初始允许IDEA Bundled Maven，待生成Wrapper后切换到项目Wrapper。
3. 在IDEA内置Terminal执行`java -version`与`git --version`。注意Terminal的JAVA_HOME与IDEA设置的Project SDK可能不同；不一致要先定位，再继续。
4. 如IDEA启动JDK/Maven导入不一致，使用 **Settings → Build Tools → Maven → Runner → JRE** 核对运行JDK为21（具体UI以当前版本为准）。

**完成标志**：IDEA SDK为21；Terminal里Java21；Git可用。不要用Java25或项目默认language level来替代真实Java21执行证据。

### 节点03 · 原仓库内创建聚合父POM

1. **在当前克隆目录内**创建根目录`pom.xml`，不是File→New→Project另建一个平行项目；原有README/docs保留。
2. 采用Boot4.1.1 parent、Java21、Cloud2025.1.3 BOM；父`packaging=pom`，只有模块`aigate-gateway`、`aigate-core`，不要把业务Controller放在父工程。
3. 使用IDEA Maven工具窗口重新加载Maven项目，观察依赖能否下载；如需代理/镜像必须记录变更，不能未经总控批准换用其它Boot/Cloud版本。
4. 配置Maven Wrapper3.9.16；验证IDEA Maven执行JDK、`./mvnw -v`（Windows为`mvnw.cmd -v`）与JDK21一致。

**完成标志**：根聚合POM能被IDEA识别，Java21与BOM固定。具体POM由总控在对话中按步解释后用户亲自输入，**不是让本地AI生成**。

### 节点04 · 创建两个Maven子模块并首次启动

1. 在根项目右键 **New → Module**，选择Maven/Java，依次创建`aigate-core`与`aigate-gateway`；校验根`pom.xml`的`modules`、子`parent`和Maven工具窗口。
2. Core使用Spring MVC/Security/MyBatis-Plus Boot4 Starter/Flyway/MySQL；Gateway使用Server WebMVC及相应安全依赖。依赖版本和不应混用的Starter参照COMP合同，不提前引入Redis/MQ。
3. 分别建各自独立的Spring Boot主类与隔离端口、profile；从IDEA运行配置启动两个不同Java进程，检查日志不出现依赖版本冲突或自动配置错误。

**完成标志**：两个独立Boot进程可启动和停止；保留真实build/run日志。若DB尚未启动导致Core暂时报错，先按下一节点准备数据库再评估，不能绕过/删除正式Flyway依赖冒充成功。

### 节点05 · 安装/启动真实MySQL8.4并执行V1 Flyway

1. 根据用户操作系统决定Docker Desktop/本地MySQL8.4（优先选已具备且容易重复启动的方案）。在IDEA Database工具窗口或终端验证真实连接；社区/免费版本如无专用Database界面则用MySQL CLI，不以该IDE功能为前置门禁。
2. 根据[Core V1 SQL](../database/core-v01.md)创建准确的Flyway迁移文件，仅六表：`team`、`employee`、`role_assignment`、`management_credential`、`admin_audit_event`、`bootstrap_control`。
3. 启动Core触发Flyway；检查`flyway_schema_history`、六表和FK/CHECK/UNIQUE行为；重复启动无版本/校验错误。
4. 用户亲自实现最小Mapper读取fixture与事务约束失败的回滚断言；不提前实现P1的真实管理API。

**完成标志**：COMP03/04真实MySQL检查有证据，不使用H2或静态SQL计数代替。

### 节点06 · Gateway/Core真实HTTP联通、安全负例与Jackson3

1. 只在`p0-compat` profile注册受控测试路由`GET /p0/compat/team-check`，Gateway精确匹配该方法和路径，Core用临时受控认证查询V1 Team测试行。
2. 使用IDEA HTTP Client或Terminal `curl`实际访问；逐一试验未认证、错误Method、未知路径、`/internal/**`及伪造Forwarded/内部身份Header，确保不泄漏内部能力。
3. 验证Boot4默认Jackson3的unknown属性400、null、Instant、固定精度金额文本等序列化行为；记录明确请求/响应，而不是仅观察200。
4. 临时账号只来自本机环境变量，未启用profile时不可访问测试接口；严禁提交密码、真实Provider Secret和用户Prompt。

**完成标志**：COMP05/06/07可复现，双进程HTTP链路与安全边界成立。

### 节点07 · SSE烟雾测试、故障恢复和环境隔离

1. 不接真实AI供应商，做测试profile下的假SSE源：3帧且发送间隔可观测；通过Gateway `GET /p0/compat/sse`，用`curl -N`观察真实增量而不是关闭连接后一次性返回。
2. 断开客户端，检查上游不会因异常自动重发；停止Core或MySQL验证安全失败，恢复后重新执行已有用例。
3. 关闭`p0-compat`，确保临时管理入口彻底不可见；后续P1替换临时认证机制。SSE PoC不声称P7收费/唯一Permit被验证。

**完成标志**：COMP08～10真实执行并留日志，不将未执行项标PASS。

### 节点08 · 在IDEA中复查、提交Git与P0验收

1. IDEA Git视图检查只包含本Phase允许的父/子POM、Wrapper、Java PoC、Flyway V1、测试、环境配置；过滤`.idea/`中的本机敏感设置、`target/`、MySQL数据目录和环境凭证。
2. 执行`./mvnw clean verify`或Windows `mvnw.cmd clean verify`，记录真实结果、JUnit报告、Git SHA和COMP01～10逐项状态。
3. 在IDEA提交并Push，告诉总控“P0已提交”；总控读取GitHub进行一次完整Code Review、检查测试证据、更新Progress/Architecture/ADR及P0知识/面试复盘。
4. **只有用户确认P0 FROZEN之后**，才授权本地AI开发P1。P0中可随时向总控汇报IDEA界面问题，用户无需独立查找完整教程。

## 4. 分工与沟通纪律

- **用户**：在IDEA亲自点菜单、创建Maven文件、写最小演示代码、运行Docker/MySQL/HTTP测试、看报错、完成Git提交。
- **总控**：针对当前操作节点给出确切菜单、解释为什么需要、具体应输入的配置和代码、预期输出及故障排查；负责正式`docs/**`更新、Code Review和验收。
- **本地AI**：P0不承担实现。P1～P12才在用户批准后按完整Phase实现。
- 以**一个P0完整交付/最终Review**为单位，不机械拆成P0-A/B/C的3次Git提交；上面的节点01～08是教学操作节点，不是8个独立工作包、8次验收或8个Git提交。

## 5. 教学的第一步（只做当前步骤）

当前先按**节点01**操作：在IDEA通过Git clone打开仓库，确认`README.md`与`docs/`存在，确认远程仓库名为`AIGate-Rebuild`。完成之后把成功/具体报错发给总控，再按节点02设置JDK21。避免跳到下一节点或一次性生成一批代码。

## 6. 操作依据

- JetBrains官方克隆Git仓库与菜单：<https://www.jetbrains.com/help/idea/set-up-a-git-repository.html>
- JetBrains官方项目SDK：<https://www.jetbrains.com/help/idea/project-settings-and-structure.html>
- JetBrains官方Maven多模块创建：<https://www.jetbrains.com/help/idea/maven-support.html>
- 版本与验收唯一来源：[P0技术合同](../tests/p00-technology-compatibility.md)。
