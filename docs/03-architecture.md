# 系统架构设计（SAD）

## 1. 部署视图

```text
Employee / Agent / Admin
            |
       HTTPS Gateway (P1起，Spring Cloud Gateway Server WebMVC)
            | 公开 method + path 白名单；Runtime SSE
            v
         Core (P1起，Spring MVC + Spring Security + MyBatis-Plus)
            |   \-- MySQL core_db：账户/权限/资金/唯一Permit/Outbox/全局运营状态
            |   \-- Redis：短期容量准入、租约和RPM（P7起）
            |   \-- Provider HTTPS / 自部署推理（Gateway凭单次Permit执行）
            |
          RabbitMQ (P11起，仅Core发布事实)
            |
         FinOps (P11起，独立进程和finops_db)
```

Gateway不直接连接Core MySQL、不储存长期HMAC/财务事实；Core不将资金权威外包给FinOps。FinOps不直接接受公网或用户 mgt_，Core做当前RBAC后以短时TrustedTypedScope代理受信请求。

## 2. 应用与领域边界

| 应用 | 管理领域/职责 | 严格禁止 |
|---|---|---|
| aigate-gateway | 管理入口转发、运行时双Key传递、网络代理/SSE、受控Redis Admission、Provider Adapter | 写Core DB、自己决定数据出站或资金、ACK丢失后自动重发Provider |
| aigate-core | Identity、Catalog、Governance、Policy、Quota/Grant/预算、财务Reservation/结算、唯一DispatchPermit、Outbox、全局Block | 保存Prompt长期原文、接受Gateway的授权结论而不复核 |
| aigate-finops | 独立Inbox/Fact/Aggregate/Manifest校对、五指标查询、运营费用审批 | 修改Core账户、共享Core SQL账号、伪造缺失事件为0 |

P1仅前两应用；P11才部署第三个应用。按业务出现真实问题再增加组件，不为技术栈展示额外拆分服务。

## 3. 关键一致性链路

1. Gateway获取当前Phase允许的请求，绝不将内部路由公网代理。
2. Core身份/ACL/治理预检，Core独占CanonicalDTO HMAC与密钥ID。
3. Gateway通过Redis同slot Lua获Pool短期ACTIVE，Core独立INSPECT验证租约和runId/epoch。
4. Core以固定无环锁序校验当前数据、双账户和受信价格，写唯一invocation、financeOperation、Reservation、Permit并一次Commit。
5. Gateway只持一次性attemptToken/临时ProviderSecret在TLS内存中做最多一次Provider HTTP。
6. 已证明未发送才可释放；已发送且可信usage则SETTLED；出站不确定则PENDING/核对；超额记录OVERRUN与风险阻断。
7. Core同库写Ledger/Outbox；P11 RabbitMQ至少一次→FinOps独立Inbox去重→Manifest校验→readModel。

## 4. 安全与恢复边界

- 管理 mgt_、Bootstrap bst_、Agent agk_、Employee empk_、内部svc_禁止互换；所有关键操作Core复核最新角色。
- ProviderSecret AES-GCM加密存Core，HTTP临时下发严格绑定一次Permit；HMAC Key和Secret加密主Key不同用途。
- Redis是短期资源状态，不是物理GPU停止证据；冷重启/主切换Guard runId无证据时拒新派发。
- P12全局`runtime_admission_control`是Core MySQL权威，Permit共享锁（FOR SHARE）、Block独占锁（FOR UPDATE）；它和Redis Pool Epoch是独立两轴。
- 实际数据库、Gateway/MVC、Redis、RabbitMQ兼容性未运行；静态架构审查不代替工程可执行性。

## 5. 技术选型及理由

| 技术 | 是什么/解决问题 | 为什么选/不用的代价 | 引入复杂度 |
|---|---|---|---|
| Java21/Spring Boot4.1.1 | 后端服务运行平台 | 熟悉MVC/事务/测试生态 | Boot4与其它Starter兼容要PoC |
| Spring Cloud Gateway Server WebMVC | Gateway外网路径过滤和转发 | P1即双进程，明确公共入口 | 超时、Header清洗和SSE链路 |
| MySQL8.4+Flyway/MyBatis-Plus3.5.17 | 关系约束、交易与可版本迁移 | 资金/单次Permit必须有数据库事实 | 锁序、迁移与SQL复杂度 |
| Redis8.4+Lua | 多Gateway的短期原子队列/租约 | JVM局部Semaphore不能跨节点 | 运行代次、物理资源恢复 |
| RabbitMQ+Spring AMQP（P11） | Core→FinOps至少一次异步事实 | 避免FinOps越权访问Core DB | Outbox/Inbox/Manifest/重放 |

技术边界与取舍详见 [ADR目录](./adr/README.md)；运行协议见 [Runtime Protocols](./07-runtime-protocols.md)。