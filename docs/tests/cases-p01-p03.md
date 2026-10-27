# P1～P3工程、运行身份、Catalog测试

> 所有案例都是实施/验证规格，**没有代码运行证据，全部NOT RUN**。按首次出现的真实Phase执行；静态设计审查和真实数据库/网络测试不是同一种证据。

## 1. 本册适用范围

实现时必须对照[测试总策略](../09-test-plan.md)、[Phase任务](../phases/README.md)，每个case记录Git SHA、命令、镜像版本、passed/failed/skipped、实际Provider出站计数及未知财务风险状态。

## 2. 业务验收场景组 1


现有 P1-01～P1-24、MSC-01～08、RPC-01～07 仍保留；D18 额外细化**从一开始真实搭双 Spring Boot 服务**与**数据库级只初始化一次**的验收。全部为待实施用例，不得把写入测试文档计为 PASS。

| ID | Fixture / 执行动作 | 必须验证 | 类型 |
|---|---|---|---|
| P1-25 | Maven Wrapper 3.9.16 + JDK21，父聚合构建 contracts/core/gateway/e2e-tests | contracts 普通 Jar，Core/Gateway 是两个各有 Main-Class 的独立可执行 Spring Boot Jar；全项目 release=21 | 工程 |
| P1-26 | 查看 Gateway 的 Maven dependency:tree 与打包 fat jar | Gateway 没有 MyBatis、MySQL JDBC、Flyway、Core 模块、管理 HMAC Pepper 依赖；Core 不引用 Gateway 模块 | 架构 |
| P1-27 | Boot4.1.1 + Cloud2025.1.2 的 Gateway WebMVC starter 真运行，并请求 /api/v1/teams | 官方 MVC YAML 命中真实内部 Core HTTP，而不是 Boot3 WebFlux 路由；没有运行两个 App 于同一 JVM | 集成 |
| P1-28 | 在真实 MySQL8.4 创建空 core_db、执行一次 V1 Flyway、再次重复启动 | 六张表以及固定 id=1 bootstrap_control 单例行存在，schema_history V1 唯一成功；FK、CHECK、UNIQUE/索引生效 | DB |
| P1-29 | 两个请求用不同 teamName 和不同 employeeCode 并发调用 /api/v1/bootstrap/initialize | 单例锁使恰一事务提交 201、另一409；数据库只有一个首建 Team+Employee+GLOBAL ADMIN+credential+audit，initialized_by 正确 | 并发 |
| P1-30 | 再次初始化，换新 Idempotency-Key，或重启后继续启用 INIT | 始终409 BOOTSTRAP_ALREADY_INITIALIZED；不能创建第二个初始管理员或再次显 mgt_ Key | 安全 |
| P1-31 | 初次初始化在 Credential/Audit 写入前制造 SQL 异常 | 六表状态整体回滚，bootstrap_control 仍 initialized_at NULL，外部没有明文 Key 响应；故障移除后可正常初始化 | DB/安全 |
| P1-32 | Bootstrap 默认 OFF、无 Token、短弱 Token、非受控来源/伪造 bst_ Header | 仅 OFF 正常无公开 Bootstrap；错误配置启动失败或请求401/403；普通管理 API 不接受 bst_ | 安全 |
| P1-33 | 已初始化，RECOVERY 为已存在 ACTIVE GLOBAL ADMIN 签新管理 Key | 201 一次性展示；旧身份角色未更改、新凭证可用、成功写审计、重放409不再显示明文 | 安全 |
| P1-34 | RECOVERY 请求 target 为普通员工/disabled/已撤销 Admin，或企图创建额外角色 | 全部拒绝且无新管理员角色/Key；全无 ACTIVE Admin 情形拒绝并触发离线运维恢复流程 | 安全 |
| P1-35 | 真实 Docker Compose Core 停机、Gateway 保持运行，访问 GET/PATCH | Gateway 连接失败受控503、超时504、无 Core 私有地址/Key 泄露；恢复 Core 后请求恢复正常 | 分布式 |
| P1-36 | 对 Gateway 外部依次请求 /internal/v1/ping、URL 编码 internal、/actuator/env、/v1/chat/completions、/api/v1/finops/summary | 无私有接口/Runtime/FinOps 透传；未知路径404/405；没有访问 Core 的任意 /** fallback | 路由 |
| P1-37 | 外部同时伪造 X-Internal-*、X-AIGate-Service-*、X-Authenticated-*、Forwarded、X-Forwarded-* | Gateway 正确移除，Core 不信任伪造员工角色；有效 mgt_ 被 Core 真正认证，不因 svc_ Header 伪造放行 | 安全 |
| P1-38 | Core GET /internal/v1/ping：合法 Gateway svc_、无凭证、错误凭证、轮换超期 | 200 只返回 service/status；无效401；Core 内部不接受普通 mgt_ 作为 svc_，Gateway 对外根本没有路由 | 安全 |
| P1-39 | 禁止数据库 root/DDL 的 core_app 用户登录，并尝试访问 finops_db（即使未来已创建） | DML/必要读可用；CREATE/DROP 及跨库 SELECT 失败；Gateway 无任何 DB 配置，Flyway 迁移 job 独立使用 DDL 身份 | DB隔离 |
| P1-40 | Redis8.4.7 连接正常/故障，但 Core MySQL 正常、管理 Key 有效 | Gateway Redis 健康可诊断；P1 管理 RBAC/数据库记录从不依赖 Redis 事实，不以缓存绕过 Core 授权 | 基础设施 |
| P1-41 | Testcontainers2 使用 testcontainers-mysql/testcontainers-junit-jupiter 与 GenericContainer Redis | 真 MySQL/Redis 容器环境跑六表迁移、Mapper CRUD、分页插件、权限/事务；不加载旧 artifactId 或 H2 假事务 | 兼容 |
| P1-42 | GitHub Actions 空工作区构建、Compose 从头拉镜像并以 Core/Gateway 两进程跑端到端 | 真实命令、依赖树、Boot4/Cloud、MySQL/Redis 镜像 digest、DB V1、测试通过/失败/跳过、commit SHA 全部有证据；不能以未执行代替成功 | CI |

**强制固定数据**：两个并发 Bootstrap 请求分别 Platform-A/ADM_A001 与 Platform-B/ADM_B001（避免 Team UNIQUE(name) 偶然代替单例锁）；另创建 Team5/Team6 两个普通组和跨 TeamManager/普通员工，以 mgt_ 一次性签发/过期/撤销断言身份。数据库初始 seed 仅 bootstrap_control(id=1)，**不得在迁移里写测试员工或明文生产管理 Key**。

**真实启动/验收命令接口**（命令是待执行约定而非运行记录）： ./mvnw -B -ntp -version；./mvnw -B -ntp clean verify；docker compose -f infra/compose.ci.yml up -d --build --wait；执行网关真实 HTTP E2E（包含 POST /api/v1/bootstrap/initialize、GET /api/v1/me/profile）；docker compose -f infra/compose.ci.yml down -v。CI 的机密值在运行时使用 CSPRNG 生成，只通过 secrets/env 传入，不 echo 到日志。未固定镜像 digest、网络/Filter、Flyway CLI 容器与 JUnit fixture 之前不应标记“已可运行”。

**新增 TECH 门禁**：Boot4 的 starters 使用 webmvc、flyway，Cloud Gateway WebMVC 的实际 YAML 为 spring.cloud.gateway.server.webmvc.routes；MyBatis-Plus 3.5.17 分页另需 mybatis-plus-jsqlparser 3.5.17；Testcontainers2 的 mysql/junit 模块名变更；Flyway MySQL 专用模块/production migration job 要通过真实 MySQL8.4 证明。Maven Wrapper 候选3.9.16，MySQL 候选8.4.13，Redis 候选8.4.7：仅查到发布不等于镜像 Manifest/digest 已验证。

**状态**：P1-25～P1-42 所有测试均 NOT RUN。P1 工程合同仍属 P0 设计，原 P1-01～24 不受删减；Bootstrap 六表修订要同步进所有测试 fixtures。



## 3. 业务验收场景组 2


前置：P1 Gateway+Core 真 HTTP、MySQL8.4 V1 六表、Bootstrap 完成，已创建 GLOBAL PLATFORM_ADMIN（有真实测试 mgt_）、Team A/Team B、Employee Alice/Bob/DisabledE；在测试环境临时注入 svc_ 与 Agent/Employee HMAC 双用途 Pepper。所有随机 Key 使用 fake test profile 并在用后销毁，**测试日志也不记录原 agk_/empk_ 明文**；可通过仅测试进程内存保存初次签发响应的 Secret 供后续 HTTP 检验。

| ID | 具体测试 | 必须断言 |
|---|---|---|
| P2-01 | V1 已有 Team/Employee/Role，执行 Flyway V2 后重新启动 | 新增 agent、agent_api_key、employee_credential、employee_agent_permission 四表；旧六表/数据不变，schema_history 含 V2 且不重复执行 |
| P2-02 | V2 直接插重复 applicationCode、重复 Employee+Agent pair、重复 digest、无效外键 | MySQL UNIQUE/FK 拒绝，无污染数据；索引名/表所有权符合 D19 |
| P2-03 | V2 插 invalid status、过期早于 validFrom、非法 grace pair | MySQL CHECK 拒绝，数据库在真实 MySQL8.4 上测试而非 H2 |
| P2-04 | GLOBAL Admin POST APP_FINANCE、Team A，再 GET Agent | 201/200、正确 DTO、ownerTeamId/A、version=0、审计一条、无 Agent 运行代码 |
| P2-05 | applicationCode 在大小写、非法字符、空白或相同 code 重复 | 400 INVALID/409 DUPLICATE_APPLICATION_CODE；大小写语义按 utf8mb4_bin 唯一，正则只允许大写字母开头 |
| P2-06 | ownerTeam 不存在/已 DISABLED | 404/409，Agent 不创建、审计没有成功事件 |
| P2-07 | 两个 Admin PATCH 同 Agent expectedVersion=0 | 仅一个200并更新version=1，另一个409；owner Team/状态不能被旧版本覆盖 |
| P2-08 | GLOBAL Admin 查询列表 status/ownerTeam/page/size，TEAM_MANAGER/Employee 尝试管理 Agent | Admin id ASC/total正确；非 Admin 403，不能越权改变别的 Team 资产 |
| P2-09 | 给 ACTIVE Agent 签发 agk_、给 ACTIVE Employee 签发 empk_ | 各自201只显示首次原 Key，prefix agk_/empk_，长度为32随机字节编码；DB 只存 BINARY(32) digest、prefix/pepperKeyId |
| P2-10 | GET agk_/empk_ 分页 metadata、管理审计及错误日志 | 任意地方不返回原始 Key、HMAC Pepper、secret_digest、完整 Authorization；201 响应 no-store |
| P2-11 | 同一 Admin/Idempotency-Key 重放创建同种 Key（包括并发） | 首次仅创建一条，重复409 KEY_ALREADY_ISSUED_NOT_REVEALABLE，不重显示密钥 |
| P2-12 | 同一 Admin 在同 credential 表对不同 Owner 复用相同 Idempotency-Key | 不给另一个 Owner 签发，无多发或跨对象泄漏 |
| P2-13 | 签发 expiresAt=now+59m、now+91d、过期时点、未知属性 | 400；now+1h～now+90d 合法，时间边界由固定 Clock 测试 |
| P2-14 | Agent/Employee 已 DISABLED 仍尝试签 Key | 409 INVALID_STATE、无新增有效 Key 或成功审计 |
| P2-15 | mgt_、bst_、svc_ 直接放 agk_ 或 empk_ 位置 | Core verify 401 且无伪装身份成功 |
| P2-16 | agk_ 与 empk_ 互换两个 Header、在 body 伪造 employeeId | 401；body 未知字段400、不能替代缺失 Key |
| P2-17 | 两类 Runtime Pepper 配错、轮换只保留 previous 或试图用 P1 management Pepper | 没有允许值 prod 拒绝启动；在受控previous窗口可校验旧 digest，对不受信版本不能绕过 |
| P2-18 | 初始 Key 调 rotate、新 Key expiresAt合法，旧 Key grace24h | 201新Key明文一次，旧 Key graceExpiresAt<=原 expiresAt 且<=now+24h；DB记录 parentKeyId |
| P2-19 | 固定 Clock 在旧 Key graceEnds 前1us/恰到期、自然 expiresAt 前后 | 窗口内有效、到边界立即401；新 Key 不受旧 Key 到期影响 |
| P2-20 | 旧 Key 手工 revoke，尽管 grace 未结束 | 立刻401，状态不能因重新 enable Agent 而复活已 REVOKED Key |
| P2-21 | Agent/Employee 已有两把仍有效 Key，Admin 又同时 issue/rotate | Owner 锁约束在多个并发线程下一共最多2把可认证 Key，多余409 KEY_LIMIT_EXCEEDED |
| P2-22 | 旋转请求 oldKeyId 不属于 URL 指定 Owner、已 revoke/已到期 | 404（路径归属）或409（无效），不影响其他 Owner Key |
| P2-23 | 轮换成功响应丢失，用同 Idempotency-Key 再请求 | 409无旧明文/新Key明文重复显示；新 Key 只创建一条；可通过 Metadata 查看凭证事实 |
| P2-24 | Admin PUT Agent X→Alice Permission，随后 Core internal verify | 201 GRANTED，audit成功，唯一 Pair，且两个 Key+Owner均有效→200 |
| P2-25 | 只有两个有效 Key、Permission missing | verify403；Agent ownerTeam=Employee primaryTeam 也不能自动允许 |
| P2-26 | Agent X→Alice GRANTED，但 Bob 用 Bob empk_ 与 X agk_ | Bob 无 ACL→403，不能用 body/role伪造 Alice |
| P2-27 | 并发两个相同 PUT Permission，再重复 PUT | 唯一 Pair 只有一行，首次201、其他204或按事务竞态确定幂等成功；审计变更仅实际状态切换时追加 |
| P2-28 | DELETE Permission 两次、再 PUT 恢复 | DELETE 204幂等、verify403；恢复单行 REVOKED→GRANTED，version递增且审计能追踪历史，不得新建第二条 |
| P2-29 | /internal verify 缺 svc_，但有真实两 Key | 401 INTERNAL_UNAUTHENTICATED，不把真实 Key 当服务身份 |
| P2-30 | svc_ 正确、agk_ 有效而 empk_ 缺失/错误/过期 | 401 UNAUTHENTICATED、不回显哪个 Secret 或其 digest |
| P2-31 | svc_ 正确、两 Key 有效、Permission GRANTED | 200最小 {agentId,applicationCode,employeeId,agentKeyId,employeeCredentialId,permissionVersion,verifiedAt}；没有管理角色/全文 Key |
| P2-32 | 外部经 Gateway 请求 /internal/v1/runtime-identities/verify 或 /v1/auth/verify | 完全不可路由/404；Gateway /v1/chat/completions 仍不开放、Provider 请求数0 |
| P2-33 | 模型/额度/Provider/Docker Redis 均未具备，只运行 Core internal verify | 验证业务完整正常，不访问不存在的 P3表、不预留财务/队列 |
| P2-34 | Agent PATCH DISABLED 后使用未到期 agk_、Alice empk_ | 401；Key 仍保留审计且不被物理删除，Agent再启用可仅恢复未被撤销且仍有效的 Key |
| P2-35 | P1 Employee DISABLED / Team 禁用规则影响 Agent/Employee / OwnerTeam变更 | Employee 即时401；Agent ownerTeam 后来 DISABLED 也必须拒绝验证，Agent创建/转Team要求目标 ACTIVE；主Team变更不隐式增删 ACL |
| P2-36 | 权限 revoke 事务先 COMMIT，再发新 verify | 新 verify403；并发已验证且先返回的请求有确定操作先后，不虚称与任何 Provider 出站有跨事务原子性 |
| P2-37 | 吊销 agk_/empk_ 事务先 COMMIT，再发新 verify | 新 verify401；Admin 成功变更+审计在同一事务，Audit insert失败则 revoke整体回滚 |
| P2-38 | Gateway 真实代理 15 个新增 P2 管理接口，Core 停机时查询/写 | 管理 mgt_ 在 Core 验证，503/504有界，不自动重试签 Key，headers不泄密 |
| P2-39 | Team Manager/普通 Employee 试图签 Key、管理 ACL、用 Key 查询别人的 metadata | 403/404且不能根据 resourceId 泄漏别人 Key归属；管理 RBAC不因为同 Team/Agent ACL而升级 |
| P2-40 | P1 全量回归、Flyway V1→V2、Core/Gateway 两真实进程与 CI 运行 | 汇总运行命令、passed/failed/skipped、DB V2 schema、端点响应断言、Commit SHA；原管理 Key/Bootstrap 无行为回退，Provider出站0 |

**测试固件**：Team A=10 / Team B=20；Employee Alice=101 ACTIVE 属 A、Bob=102 ACTIVE 属 B、DisabledE=103；Agent X APP_FINANCE 属 Team A、Agent Y APP_SUPPORT 属 Team B；为 X、Y 分别创建受控 Key，Alice/Bob 各有 empk_；建立 X→Alice GRANTED、X→Bob MISSING、Y→Bob GRANTED 三组关系。所有权限查询在真实 MySQL 与 Core 权威当前状态校验，使用固定 UTC Clock 并显式测试 expiresAt/graceExpiresAt。P1 ID 自增真实测试不得假设固定 id=10/20/101，测试使用已创建实体 DTO 实际ID关联，上述数值只代表语义 fixture 标识。

**与 P3/P7 相邻的跨阶段用例**：SYS-02 在 P2 即验证没有 Model 仍可做独立可信双身份；SYS-03 待 P3 有真实 LogicalModel 和 Agent-Model ACL 才启用；SYS-09 待 P7 最终派发 DB 事务和真实 Provider Mock 才真正验证撤销阻止费用出站。不得把 P2 私有 verify200 当 P7 已能安全收费的证据。

**状态**：P2-01～P2-40 全部 NOT RUN；V2 DDL、API 和用例为设计，不存在 SQL/代码/CI 运行证据。P0 总设计和 P1 前置尚未完成。



## 4. 业务验收场景组 3


测试用例全部针对**真实 Core MySQL8.4 + Gateway/Core 两个进程**，外部 Provider 使用仅本地可计数 Fake；严禁试图使用真实付费 API Key 或生产 Model。每个场景都断言管理审计、响应 DTO、数据库 FK/UNIQUE/版本与外部出站计数。

| ID | 输入/故障 | 精确断言 |
|---|---|---|
| P3-01 | V1→V2→V3 Flyway 干净迁移及重启 | V3 七张表只迁移一次、旧 Employee/Agent 数据不变 |
| P3-02 | 同 Provider code、Model code、Deployment code 并发创建 | UNIQUE 仅一行，另一409 |
| P3-03 | 有缺失 Provider/Model FK、非法 status/mode 的 SQL | MySQL CHECK/FK 拒绝 |
| P3-04 | PlatformAdmin 建第三方 Provider | 201、唯一 Code、版本0、审计 |
| P3-05 | TeamManager/普通员工尝试 Provider CRUD | 403，无法绕过 Gateway |
| P3-06 | Provider DISABLED 后尝试激活其 Deployment | 409，无可用候选 |
| P3-07 | Admin 建 TEXT-Q4-STABLE、能力/版本合法 | 唯一 modelCode、身份不可暗换 |
| P3-08 | 试图通过 PATCH 改 modelCode/family/revision/capabilityProfile | 400，必须新建 LogicalModel |
| P3-09 | LogicalModel DISABLED，但 AgentModel ACL 仍 GRANTED | eligibility 拒绝；权限关系不代表可执行 |
| P3-10 | 两 Admin 同 expectedVersion 修改同对象 | 仅一成功、另一409，无丢更新 |
| P3-11 | 两个确实同型号版本部署分别 THIRD_PARTY_API/SELF_HOSTED | 共享 Model 身份/员工 POINTS 价，但 hosting/cost_model 独立 |
| P3-12 | 不同模型仅都是 OpenAI-compatible | 不得合并为同一 LogicalModel |
| P3-13 | 非法 Endpoint schema http/file/gopher（第三方） | 400，记录安全拒绝，无 HTTP 出站 |
| P3-14 | 外部 Host 解析到 127.0.0.1、link-local 或云元数据地址 | SSRF 拒绝，不能读取内部资源 |
| P3-15 | DNS 从公网变私网、候选端点声称可302跳转 | P3每次登记/激活/eligibility重新校验全部解析结果且HTTP计数0；P7真实连接与302保护另行实测，不能冒充P3已完成真实派发保护 |
| P3-16 | SELF_HOSTED endpoint 私网但未 allowlist/审批 | 不参与 P7 候选；不默认 CONFIDENTIAL |
| P3-17 | Deployment DRAFT→ACTIVE、MAINTENANCE/禁用 | 仅有效发布资源能列为候选，变更有审计 |
| P3-18 | THIRD_PARTY_API 却 cost_model=INTERNAL_RESOURCE | 400/DB CHECK 拒绝 |
| P3-19 | POINTS 单价草稿/发布 | 单模型统一内部单位 POINTS 且历史版本保留 |
| P3-20 | 同 LogicalModel 两个部署但采购费用不同 | 员工 POINTS 单价不变，第三方按自己币种采购价 |
| P3-21 | 两个不同管理员并发发布互相重叠 PriceVersion | 必须唯一当前生效；只有一个提交或串行无重叠 |
| P3-22 | UPDATE 已发布历史价金额、伪造已结算价格 | 不能通过业务 API 改写；历史引用仍可查 |
| P3-23 | 只有内部积分价，没有供应商采购价 | THIRD_PARTY_API eligibility 标记不可收费执行 |
| P3-24 | 同一 Deployment 使用 USD/CNY 的不同采购 revision | 以发布时有效且冻结的单一原币种，不隐式汇率 |
| P3-25 | 采购价负数、超过精度、缺额外计价 Token 类别 | 拒绝非法或不完整收费价格合同 |
| P3-26 | ProviderCredential 签发/轮换读取 DB | 只存 AES-GCM 密文、随机 IV 和 keyId，绝无完整明文 |
| P3-27 | GET 凭证/日志/审计/异常响应 | 不包含供应商密钥或加密主密钥；no-store |
| P3-28 | 密钥吊销或错误密钥版本；无 Permit 试图领取 | 拒绝，不自动继续使用旧已吊销凭证 |
| P3-29 | 给 P2 Agent 授一个真实 LogicalModel | UNIQUE agent-model、201/204 幂等及审计 |
| P3-30 | EmployeeAgent 已授权但 AgentModel 未授权，反之亦然 | Core 私有 eligibility 均403，双 ACL 全通才200 |
| P3-31 | AgentModel 并发 revoke/grant、停用 Agent/Model | 后续 eligibility 按当前事实拒绝，版本安全 |
| P3-32 | 所有 Catalog 业务完成，客户端直发 /v1/chat/completions | 404/受控拒绝，Fake Provider 收到0，资金与 Redis 队列不变 |

**新增兼容/端点安全门禁**：测试 Docker/JVM 不从外网任意访问主机地址，Provider 凭证回显/trace/metrics 绝无敏感值；模拟不受支持 cache/附加收费类别不允许 P7 出站。P3 不测试 P8 真实加权路由/熔断；P7 才通过 Permit 执行 Provider。固定单价、业务日期、URL、Host/IP 以及具体 MySQL fixture 仍需 P0 进一步冻结。

**状态**：P3 本地实现与测试已通过，具体案例映射见target/p3-delivery.json，整体签收与限制见Progress；矩阵不是测试通过证明。



## 5. 业务验收场景组 4


**范围**：新增以下**测试规格**，不是自动化测试报告。预计单测以校验器/Jackson DTO、集成用真实 MySQL8.4 和独立 Gateway/Core 两应用、危险网络用 FakeDNS/FakeProvider/黑洞IP/重定向模拟。固定Key样例不得作为可认证密钥；随机Secret在测试运行时生成，断言响应/日志/DB均不存明文。协议真凭据/真实Provider不可用于CI。

| ID | 阶段 | 输入/动作 | 必须断言 |
|---|---|---|---|
| D47-API01 | P1 | POST Team正常、重复同名不同大小写、空白名、超80字符/未知字段 | 201 TeamDTO+version=0；唯一冲突409；非法body400；审计只记成功 |
| D47-API02 | P1 | PATCH team expectedVersion=0并发两个不同name | 只有一个200版本递增，另一个409 VERSION_CONFLICT，无丢失更新 |
| D47-API03 | P1 | PATCH Employee `{"expectedVersion":1,"status":null}`、科学计数ID、未知字段 | 全部400，不能把null误当不修改 |
| D47-API04 | P1 | Manager查询别Team Employee单项、列表/total分页 | 403/权限范围内真实列表，过滤必须在SQL COUNT之前 |
| D47-API05 | P1 | PUT/DELETE相同Role；试删最后GLOBAL PLATFORM_ADMIN | 首次PUT201、重复204、DELETE204；不能锁死唯一活跃管理员 |
| D47-API06 | P1 | 同签发者同Idempotency-Key重复发管理Key/重放不同Owner | 首次201仅一把；重放409且不泄密；不同Owner使用同Key仍409 |
| D47-API07 | P1 | 同时调用Bootstrap initialize两次、已初始重新init/恢复 | 仅一成功、单例锁有效、BST不能当mgt、RECOVERY不授新角色 |
| D47-API08 | P1 | Gateway编码路径、带Forwarded伪造svc_ header、未来P7/P11路径 | 所有公开非法路由404/405/拒绝，Core私有接口不外泄 |
| D47-API09 | P1 | 模拟Core COMMIT成功但Gateway收到504，重放管理Mutation | 客户端不得导致再次签发、重复审计或明文重现 |
| D47-API10 | P2 | 新Agent合法code、lowercase/非法符号、PATCH改applicationCode | 前者201，后者400；不可变身份不能通过PATCH偷改 |
| D47-API11 | P2 | 两管理员同Owner并发新agk_/empk_与轮换，当前可认证Key限制2 | Owner行锁有效，不会超过2个同时可认证Key；多者409 KEY_LIMIT_EXCEEDED |
| D47-API12 | P2 | agk_与empk_互换、过期/吊销、Team DISABLED、缺EmployeeAgent ACL | 无效Key401、有效双Key缺ACL403；没有隐式Team授权 |
| D47-API13 | P2 | 同签发者同Idempotency-Key跨Agent/Employee运行凭证的每表唯一性 | 各表物理作用域一致：同表不同Owner仍不能重复；跨两表不是自动同一唯一空间 |
| D47-API14 | P2 | GET两类Key Metadata/ACL、rotate首次响应、重复rotate | 只有真实首次201包含key，所有GET无secret/digest，重放409/不重复旋转 |
| D47-API15 | P3 | Provider `baseUrlHostPolicy` 含未知字段、suffix wildcard、非法CIDR、PUBLIC私网/80端口、null | 400，无非法配置/审计成功记录 |
| D47-API16 | P3 | SELF_HOSTED显式批准私网、伪造MetadataIP、DNS变更/重绑定/redirect | 经审核测试网段内可登记；禁用不安全IP、重定向与P7动态解析，不能只依赖创建时DNS |
| D47-API17 | P3 | Model `capabilityProfile` 缺maxTotalTokens、toolCalling=true、非法上限或字段名 | 400，不能通过任意Map绕过协议版本 |
| D47-API18 | P3 | POST ProviderCredential两线程同Actor/同Key/同Body以及不同Body、不同Deployment | 仅1条凭证+1次审计；相同安全请求可200回Metadata；不同HMAC 409；从不回显secret |
| D47-API19 | P3 | 两管理员同Deployment同revision不同Key、禁用activeCredential | 只有1个版本写入；ACTIVE指针必须匹配合法未吊销Secret，否则禁止新收费派发 |
| D47-API20 | P3 | 两管理员并发发布同ModelPoints price、旧Price历史被查询/旧expectedVersion重放 | 当前指针仅一个获CAS成功；已发布原价不可改；错误并发409，旧价仍可审计 |
| D47-API21 | P3 | 创建第三方与本地双Deployment，但未完成P6政策/P7 Permit | Catalog可登记，真实Provider HTTP发送数严格0 |
| D47-API22 | P0-P3 | Java21/Boot4/CloudGatewayWebMVC/MyBatisPlusTestcontainers依赖PoC、Flyway按所有阶段迁移 | PoC只报告真实PASS/FAIL/BLOCKED，不能用代码未运行的「预计兼容」冒充PASS |
| D47-API23 | P0-P12 | 按Phase Gate对应执行之前Phase不可能存在的MQ/SSE/多Gateway测试 | P0只要求合同与必要PoC；P7/P11/P12真实实现后分别验收，不产生「先写完才能开始写」循环 |

**P0现时状态**：以上 D47-API01～23 全部 **NOT RUN**。P1～P3 DTO/Schema 是文档冻结候选而不是正式P0冻结；P4～P12逐API契约尚未逐项总审。P0 可证明本轮有新增测试用例说明，不能证明 Java/数据库的任何一条测试已通过。正式各Phase交付须连同原 P1-xx、P2-xx、P3-xx、FZSQL/FZAPI/FZROUTE/MSC 的对应测试执行并提交真实日志。

### 证据登记模板（不编造结果）

`[caseId, phase, environment, JDK/Maven/DB/image digest, command, GitSHA, startedAt, passed, failed, skipped, artifacts, reviewer, actualStatus]`；actualStatus仅 PASS/FAIL/BLOCKED/NOT_RUN。P0 文档审查项使用 DESIGN_REVIEWED，不得填写测试 PASS。存在已批准的兼容PoC风险豁免时同时记录具体未验证条件、负责人、截止Phase与接受人；豁免不等于任何 P1 安全功能可跳过其本Phase实测。




## 完成条件

异常输入/越权/幂等/账务竞态/超时/重启不得仅用模拟UnitTest代替必要真实MySQL/Redis/MQ/两进程E2E。未实际运行前一律NOT_RUN；原草案描述中的阶段归属若与[Roadmap](../08-roadmap.md)有差异，以Roadmap和最新Database/API规范为准。


## P3 合同闭合专项测试

| ID | 输入/故障 | 精确断言 |
|---|---|---|
| P3-33 | 创建请求偷带deploymentId、缺Adapter字段/未知字段 | 400；合法请求的持久ID由Core回填并匹配新行 |
| P3-34 | 管理请求自报 evidenceRef/verifiedAt 或私网批准、清单缺失 | DRAFT可登记，ACTIVE/NONE签发拒绝；不能凭自报字段成为可信审批 |
| P3-35 | 受保护清单摘要匹配/不匹配、过期、allowNoAuth及重启撤回 | 只接受精确配置；失效后下一次eligibility无候选；NONE限已批准SELF_HOSTED |
| P3-36 | Manifest未知字段、重复摘要、非法时间/文件权限或符号链接 | 启动失败且不输出文件原文 |
| P3-37 | AES-GCM 4096字节/4097字节、多字节、空白保留、随机IV、篡改tag/AAD/keyId | 合法可真实解密，超长/错误拒绝，库中只有ciphertext/IV/keyId |
| P3-38 | 同Actor幂等并发、跨deployment/revision/secret/存在性冲突、响应丢失 | 一条凭证/一次审计；重放200原metadata、不加密或改指针；冲突409 |
| P3-39 | 当前及历史AES/Issue密钥配置复用、缺失/部分配置、轮换追验 | 启动拒绝坏配置；合法历史可解密/重放；未知历史keyId安全失败，无新签发 |
| P3-40 | 未授权双ACL、mgt_混用、伪造body/重复头、公开internal路径 | 401/403/400/404按RPC合同，DTO只含白名单 |
| P3-41 | Provider策略更新使ACTIVE部署或摘要失效 | 409 HOST_POLICY_CONFLICT，旧配置/版本/审计不变 |
| P3-42 | 审计故障时创建/发布/签发/吊销/ACL撤销 | 同事务整体回滚，不出现虚假成功或残留指针 |
| P3-43 | P1/P2全量、V3干净迁移/历史升级、双JVM/Compose | 汇总实际pass/fail/skip、环境和Git基线；外部CI未运行不得声称PASS |
