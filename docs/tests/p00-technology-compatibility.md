# P0 · 技术兼容性核查及最小工程 PoC 执行合同

> 日期：2026-10-23；状态：**官方版本兼容声明已核实（STATIC_SUPPORTED），实际构建、MySQL迁移、HTTP/SSE仍NOT_RUN**。不将本文视为P0验收通过。这里是项目负责人**亲自使用IntelliJ IDEA**实现并验证的技术验收规范；P0不交给本地AI。总控指导操作并独占修改正式架构规范。IDEA操作顺序详见[手把手指南](../guides/p00-idea-hands-on.md)。

## 1. 业务问题与范围

AIGate计划从P1部署两个独立Java服务：Gateway只负责公开入口、安全路由，Core独占身份、审计和MySQL权威；P7才需要Redis/真实Provider SSE，P11才需要RabbitMQ/FinOps。P0只证明JVM、Maven、Boot/Cloud、Servlet Gateway、MyBatis、MySQL/Flyway的最小端到端组合能真实跑通，提前发现版本不兼容，不能先写P1～P12全业务代码后再排错。

## 2. 官方资料交叉检查与最终P0候选版本

| 技术组合 | P0固定组合 | 静态兼容结论 | 仍待执行 |
|---|---|---|---|
| JDK + Spring Boot | **Java21 + Boot4.1.1** | Boot4.1.1支持Java17～26；支持Servlet Tomcat11 | Java21编译、两个Jar独立运行 |
| Spring Cloud | **2025.1.3** BOM | Spring官方2026-08-20发行说明明确兼容Boot4.1.x；原2025.1.2也支持，但2025.1.3有安全修复 | Maven解析及启动兼容性检测 |
| Gateway MVC | **Spring Cloud Gateway5.0.3 Server WebMVC** | 由2025.1.3 BOM管理；Starter名为`spring-cloud-starter-gateway-server-webmvc`，不是旧名gateway-mvc | 精确Method/Path路由、安全拦截、SSE流式转发 |
| Spring MVC/Security | Boot4.1.1管理 | Servlet WebMVC/Security是正式技术路线 | PoC认证、JSON/HTTP错误 |
| MyBatis-Plus | **3.5.17 Boot4 Starter** | 官方提供`mybatis-plus-spring-boot4-starter` | Team Mapper真MySQL查询/事务 |
| JSON | Boot4默认**Jackson3** | Boot官方默认Jackson3，Jackson2仅作为临时兼容选项 | DTO、unknown字段400、Instant和金额精度 |
| MySQL | **8.4 LTS**，Connector/J BOM管理 | MySQL官方Connector/J支持8.4+及Java21 | 真DDL/FK/CHECK/事务 |
| Flyway | Boot4`spring-boot-starter-flyway` + `org.flywaydb:flyway-mysql` | Boot官方对MySQL要求数据库专属模块 | 空库V1迁移/重启无重复 |
| Maven | **Wrapper3.9.16** | Apache已正式发行；满足Boot4 Maven要求 | Wrapper下载、全Reactor verify |
| Redis8.4 | P7首次引入 | Redis 8.4为GA，补丁必须含已披露安全修复 | P7 Lua/失效测试；P0不算PASS |
| RabbitMQ | P11首次引入 | 不预断言具体Broker/Client版本已经验证 | P11 publish-confirm/消费回归 |

**补丁与风险记录**：Spring Boot **4.1.2于2026-10-22发布**，目前暂不改变已批准的Boot4.1.1 P0比较基线；如果升级必须实际A/B重跑本合同并由总控修订ADR。Spring Cloud 2025.1.3是对同一已支持列车的修复更新，替换原ADR 2025.1.2（包括安全修复），由BOM统一管理Gateway5.0.3，禁止手写另一个Gateway版本。P0静态支持≠项目真实PASS。

## 3. IDEA手工搭建工程的精确依赖决策

根工程最小构成：根`pom.xml`（parent `org.springframework.boot:spring-boot-starter-parent:4.1.1`、`java.version=21`、Cloud BOM `org.springframework.cloud:spring-cloud-dependencies:2025.1.3`）、`mvnw/mvnw.cmd/.mvn/wrapper`固定Maven3.9.16、`aigate-gateway/pom.xml`、`aigate-core/pom.xml`。

| 所属 | 精确依赖（groupId:artifactId） | 版本 |
|---|---|---|
| Gateway | `org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc` | Cloud BOM管理 |
| Gateway/Core | `org.springframework.boot:spring-boot-starter-security` | Boot BOM管理 |
| Core | `org.springframework.boot:spring-boot-starter-webmvc` | Boot BOM管理 |
| Core | `org.springframework.boot:spring-boot-starter-jdbc` | Boot BOM管理 |
| Core | `com.baomidou:mybatis-plus-spring-boot4-starter` | 3.5.17 |
| Core | `org.springframework.boot:spring-boot-starter-flyway` | Boot BOM管理 |
| Core | `org.flywaydb:flyway-mysql` | Boot BOM管理 |
| Core | `com.mysql:mysql-connector-j` (`runtime`) | Boot BOM管理 |
| Test | `org.springframework.boot:spring-boot-starter-test`、`org.springframework.security:spring-security-test`、`org.testcontainers:mysql` | BOM管理 |

**不得引入**：旧`spring-cloud-starter-gateway-mvc`、Gateway WebFlux/Netty方案、`spring-cloud-starter-gateway-server-webflux`、Boot3的MyBatis-Plus Starter、`mybatis-spring-boot-starter`或另一套MyBatis-Spring；不新增Nacos/Feign/Config Server/Redis/RabbitMQ。Gateway的配置前缀必须为`spring.cloud.gateway.server.webmvc.*`，不是旧`spring.cloud.gateway.mvc.*`。HTTP公开只注册明确Method+Path，不放行`/internal/**`及任意`/api/v1/**`裸通配。

JSON选择Jackson3默认配置，测试直接覆盖未知字段400、显式null、UTC时间、decimal字符串以及content不被trim等行为；若某MyBatis JSON TypeHandler仍依赖Jackson2，必须局部确认并向总控反馈，不允许为了绕过错误在整个系统偷偷强制Jackson2。Provider输出SSE只在P7真正实现，但P0必须用**不收费的假SSE源**先检验Gateway WebMVC能增量转发。

## 4. PoC Happy Path与禁止越界

P0只做`p0-compat`测试profile：Gateway容器和Core是两个独立JVM，Core连接独立MySQL8.4；Flyway只创建Core V1设计的六表。临时`GET /p0/compat/team-check`受Gateway精确GET路由、Core临时受控认证并从`team`表读取测试Fixture，响应只包含安全检查结果；再用`GET /p0/compat/sse`验证假源3帧JSON SSE分批到达。

P0无需提前实现P1管理Key/Bootstrap实体的完整流程。临时认证可在隔离本地容器网络采用随机环境变量凭证与HTTP Basic，但绝不提交密钥、不可在非`p0-compat` profile注册控制器，P1开始前移除临时端点/账号；不能声称这种认证是正式mgt_体系。PoC不可使用H2替代实际MySQL，不使用伪造“已验证”日志。

## 5. P0-COMP逐项真实验收（用户亲自在IDEA操作，教学节点不拆提交）

| 编号 | 实际动作 | 判定条件 |
|---|---|---|
| COMP-01 | `java -version`、`./mvnw -version`、根`./mvnw -B clean verify` | JDK21、Maven3.9.16、全Reactor真实退出码0 |
| COMP-02 | `./mvnw -B dependency:tree`并保留effective-pom | Boot4.1.1/Cloud2025.1.3/Gateway5.0.3/MP3.5.17正确解析，无错Starter混入 |
| COMP-03 | 启动MySQL8.4空库；按`database/core-v01.md`写并运行真实Flyway V1 | 仅6表、FK/UNIQUE/CHECK实际生效；第二次启动schema_history不新增错误版本 |
| COMP-04 | Mapper执行Team fixture SELECT及一个约束失败的事务 | 真实读写与回滚都可重现 |
| COMP-05 | 分别启动Gateway/Core Jar，访问`GET /p0/compat/team-check` | 身份校验/安全转发成功，两个进程无共享JVM |
| COMP-06 | 用未认证、错误Method、未知路由、`/internal/**`、伪造`Forwarded`/`svc_` Header访问 | 拒绝401/403/404/405，无真实内部接口与敏感信息泄漏 |
| COMP-07 | 合法/非法未知JSON属性、null、Instant、decimal序列化测试 | 明确期望/实际JSON；不是“能启动就算Jackson兼容” |
| COMP-08 | `curl -N`收3帧假SSE（每帧隔开可观测时间）再中断连接 | 客户端逐段及时收到，不能末尾一次性缓冲；中断不重放上游 |
| COMP-09 | Core停止、MySQL中断、重启复测 | 安全失败，不返回假200，不导致临时认证泄露 |
| COMP-10 | 不启用`p0-compat` profile访问临时接口 | 必须不可见；记录PoC清理计划 |

P0-COMP08是Gateway技术路线的重要验收，不代表P7真实Provider费用或Exactly-Once已证明。若有重要COMP失败，保持P0 NOT_FROZEN并将精确堆栈/最小复现送总控；用户遇到错误不得未经总控评审自行更换架构或技术。

## 6. 证据必须提交

至少提交`p0-evidence/environment.md`（OS/JDK/镜像版本和SHA）、`dependency-tree.txt`、`reactor-verify.log`、`flyway-mysql.log`、`http-allowlist.log`、`sse-timing.log`、`test-results.md`（COMP01～10逐项PASS/FAIL/BLOCKED及命令）、`technical-debt.md`。日志脱敏，测试真实输出不能由AI生成样例冒充。正式文档仅总控修改。

**静态核查所在的总控环境（非用户IDEA电脑）**：可执行OpenJDK21；`mvn`与Docker均不存在，且无法解析Maven Central域名下载依赖。因此此前**只完成静态官方版本核验**，没有任何Java构建、Docker/MySQL、Flyway、Gateway或SSE的真实PASS。P0仍NOT_FROZEN。

## 7. 官方证据

- Boot要求 <https://docs.spring.io/spring-boot/system-requirements.html>；SpringCloud 2025.1.3公告 <https://spring.io/blog/2026/08/20/spring-cloud-2025-1-3-has-been-released/>
- Gateway WebMVC 5.0.3 <https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/starter.html>；Gateway配置 <https://docs.spring.io/spring-cloud-gateway/reference/configprops.html>
- MyBatis-Plus安装 <https://baomidou.com/getting-started/install/>；Boot4 JSON <https://docs.spring.io/spring-boot/reference/features/json.html>
- Boot Flyway <https://docs.spring.io/spring-boot/how-to/data-initialization.html>；Connector/J <https://dev.mysql.com/doc/connector-j/en/connector-j-versions.html>
- Maven3.9.16 <https://maven.apache.org/docs/3.9.16/release-notes.html>；Redis8.4 <https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/release-notes/redisce/redisos-8.4-release-notes/>
- SSE安全背景（Spring Framework7.0.9已修复相关CVE） <https://spring.io/security/cve-2026-59313/>

关联：[P0](../phases/p00.md)、[ADR-007](../adr/ADR-007.md)、[进度](../10-progress.md)。