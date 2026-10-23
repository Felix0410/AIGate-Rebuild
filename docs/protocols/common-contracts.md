# 跨Phase统一工程合同和审查修订

## 1. 数据/资金/安全公约

- 公开错误用嵌套error四字段；管理员POST/PUT/PATCH严格数值、null、未知字段；所有管理授权在Core最新Role/Team实际状态下重验。
- 普通预算与Quota金额两种固定十进制字符串；Provider收费真实上界不可信时不得放行。
- CAS只保证同资源期望version竞态和409，不自动代表同Idempotency-Key可以重现成功响应。只有V1/V2/V3/FinOps或P12明确物理收据的接口可提供强重放语义。
- Redis和MySQL的双重准入不能组成原子物理网络发送协议，必须保留已可能出站的UNKNOWN账务风险。

## 2. 设计审查已确定的最终修订（按业务归类）

| 模块 | 必须使用的最终方案 |
|---|---|
| P3 | Provider AES-GCM ciphertext列VARBINARY(8192)；Deployment不接P7容量池ID；Model DTO预留activeRoutingRevisionId |
| P5 | Grant发行/撤销理由、操作人和时间落V5真实列；重复撤销不退Pool |
| P6 | PolicyRevision与ExceptionRequest含version；审核回version；Policy发布双CAS及未发布NOT_CONFIGURED管理读 |
| P7 | Pool创建/绑定/安全激活/阻断完整管理路由，resourceIdentityCode + evidenceRef，默认为DISABLED；Redis真实runId失效时新出站被拒 |
| P8 | routing发布同时校验Model.version和activeRoutingRevisionId |
| P10 | EmergencyRequest版本与不可变requested_model_ids_json，授权模型只能是申请子集 |
| P11 | FinOps Draft和Rebuild业务收据物理唯一；Draft创建revision=1、批准后revision=2 |
| P12 | V11唯一singleton+Audit ALTER；ReadinessProbe是Core真实带HMAC审计ID，不可由客户端伪造；两个Core的FOR SHARE读取不会相互独占 |

## 3. 禁止跨阶段依赖

- P3不提前引用P7尚不存在的capacityPool记录；P7容量池激活不要求P12的readinessEvidenceIds。
- P11真实RabbitMQ/FinOps只在P11出现，P0只要求其设计和必要最小独立可行性PoC。
- P12真正管理GlobalAdmission不替代P7局部PoolGuard；不要在P1一次创建全部62表。

## 4. 一致性状态

该统一合同是2026-10-23静态审查候选，未有真Java/MySQL/Redis/MQ或Provider运行通过证据。执行AI不得自发改变协议；遇到新发现先上报总控。
