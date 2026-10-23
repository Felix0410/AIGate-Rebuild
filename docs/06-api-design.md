# HTTP API 与内部接口设计

## 1. 入口纪律

所有公开管理接口经Gateway精确method/path allowlist转发给Core，Core独立进行当前mgt_验证和Role/Team scope检查；FinOps公网不可见。Runtime唯一公开 POST /v1/chat/completions 和 GET /v1/invocations/{id}，必须双运行Key。不得使用通配 `/api/v1/**`、`/v1/**` 透传，也不得将 `/internal/**` 暴露。

## 2. 核心传输约束

- JSON camelCase，严格校验unknown/null/数值类型；Long正整数与version非负整数；时间UTC ISO8601 Z。
- 分页 `{items,page,size,total}`，page从1、size1～100、默认20；排序稳定。Scope过滤必须先于SQL COUNT和分页。
- Money/POINTS以精确固定刻度**字符串**传输；额外小数位/浮点输入400。
- 错误统一 `{"error":{"code":"VERSION_CONFLICT","message":"...","requestId":"...","retryable":false}}`；不返回SQL、Prompt、密钥、真实敏感命中。
- GET不返回凭证明文；仅首次成功签发Key的201显示一次明文，重复HTTP请求绝不再回显原密钥。
- 创建/更改API明确使用物理Idempotency-Key Receipt或CAS；两者不得混淆其重放成功语义。

## 3. 契约分册

| 领域 | 阶段 | 规范 |
|---|---|---|
| 组织/Agent/Catalog与双价 | P1～P3 | [管理API I](./api/p01-p03.md) |
| Quota/Grant/Governance | P4～P6 | [管理API II](./api/p04-p06.md) |
| Runtime/路由/LOW/应急/FinOps/运营 | P7～P12 | [运行及管理API III](./api/p07-p12.md) |
| Gateway精确路径/Method | 全部 | [外部路由白名单](./api/gateway-routes.md) |
| Core内部6类调用 + typedScope | P1～P11 | [内部RPC](./api/internal-rpc.md) |

以上分册必须与所属[数据库迁移](./05-database-design.md)和[协议](./07-runtime-protocols.md)一起使用。旧“API资源清单”不具备覆盖字段/权限/幂等的能力，不可以单独作为实现输入。