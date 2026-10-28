# P7 启动记录 · 2026-10-28

用户已提交P6并授权下一阶段。起点为 `46c6493`，开始前工作区干净。
当前为 **IN_PROGRESS**，这份记录只覆盖已编写的基础，不是完整阶段交付报告。

## 已编写的基础

- `V7__invocation_runtime.sql`逐字提取自V7权威DDL：5张新表，治理审计Invocation外键与Deployment容量池外键；不修改V1～V6。
- Contracts提供封闭`ChatRequestV1`解析：UTF-8原始正文64KiB、1～64条文本消息、字段/类型/范围、重复字段/null/未来字段拒绝。
- 规范字节固定字段顺序及十进制默认值，不trim或Unicode归一化Prompt。流式适配正文固定请求最终usage，不让客户端选择改变计费语义。
- Core提供`RequestDigestKeyring`工具：HMAC-SHA256、V1域分隔、原keyId+schema核验、常量时间比较；撤销原Key不回退当前Key。暂未装配到运行链，尚不具备持久Invocation或Permit能力。
- 解析对象与密钥环不在诊断字符串中展示Prompt或密钥；未在Gateway加入Core密钥。
- 新增真实MySQL新库约束与V6历史升级测试，并更新既有回归的迁移数、表数和清理范围。

## 验证

- 首次完整`verify`：154项，153通过、1失败、0错误、0跳过，耗时8分28秒。Contracts 9、Core 78、Gateway 1、E2E 66。
- 唯一失败为原P3故障测试仍要求Invocation/Permit表不存在；V7建表后改为检查三张Runtime表无业务记录，同时保留Provider HTTP为0、Chat入口404的断言。
- 修正后定向复测：7项ChatRequestV1单测和1项真实P3响应丢失/Core停机故障用例全通过，`BUILD SUCCESS`，29.895秒。此次定向Reactor只包含Contracts与E2E，没有重新执行Core；Core的4项新HMAC单测在首次完整验证中通过。
- 本轮新增13项测试：7项封闭解析/规范化、4项HMAC/轮换、2项真实MySQL迁移与约束，均有通过证据。V6→V7保留37张旧表的数据摘要和V1～V6校验和，重复迁移执行数为0。
- `git diff --check`通过。修正旧断言后未再次执行整个154项套件，不能将定向复测描述为第二次完整回归通过。
- 临时日志与摘要：`aigate-e2e-tests/target/p7-start-full.log`、`p7-start-recheck.log`、`p7-start-validation.json`；均被Git忽略，clean会删除。

首次完整验证遇到Mockito动态attach限制，改为JVM启动时加载Mockito测试代理；随后确认Docker daemon未运行，已启动Docker Desktop。

## 后续实施与验收

1. Core受保护Runtime密钥配置及跨用途材料隔离；Invocation持久化及幂等重放。
2. CapacityPool管理/部署绑定/两次CAS启闭与受保护真实容量证据；真实Redis同slot Lua、FIFO/RPM/epoch/runId/quarantine。
3. 六类受信RPC，独立INSPECT、治理锁内复查、冻结双价与双账户Reservation及唯一Permit同事务。
4. Gateway最后INSPECT、受保护端点配置、固定连接IP/TLS/无重定向与无自动重试的ProviderTextAdapter。
5. JSON与有界SSE、背压/超时/输出治理；可信usage/UNKNOWN/PROVEN_NOT_SENT、结果幂等收据、真实Ledger/Outbox及对账。
6. 双Gateway容量1、三种ACK丢失、Redis冷重启、SSE断流等真实FakeProvider计数故障测试。

公开Chat白名单仍关闭。没有真实Provider收费调用、Redis或Permit完成的声明；也不把本次迁移/HMAC测试覆盖解释为P7-CAP或完整P7 E2E已通过。
本轮改动未提交，不自动进入P8。


可复现命令（Mockito jar版本按本机Maven解析结果，本次为5.23.0）：

```sh
DOCKER_HOST=unix:///Users/felix/.docker/run/docker.sock ./mvnw -B -ntp verify \
  -DargLine=-javaagent:/Users/felix/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar
```

定向复测使用上面相同环境及argLine，增加`-pl aigate-e2e-tests -am`、`-Dtest=ChatRequestV1Tests,RequestDigestKeyringTests`、`-Dsurefire.failIfNoSpecifiedTests=false`、`-Dit.test=CatalogIT#lostCredentialResponseAndCoreOutageNeverIssueTwiceOrCallProvider`、`-Dfailsafe.failIfNoSpecifiedTests=false`。由于E2E不依赖Core jar，`-am`不会包含Core；HMAC测试以完整验证的Core报告为准。
