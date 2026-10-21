# 06 · Database Design

数据库：MySQL 8.4、InnoDB、utf8mb4；所有主键 BIGINT/UUID 可由雪花或数据库生成，但本轮默认使用 BIGINT 自增，跨模块引用稳定 ID；时间字段 DATETIME(6) 存 UTC；积分 DECIMAL(20,6)、供应商金额 DECIMAL(20,8)。所有 Flyway migration 只追加，不改动已执行版本。

## 组织与模型数据表

| 表 | 核心字段 | 关键约束 |
|---|---|---|
| team | id, name, status, created_at | unique(name) |
| employee | id, primary_team_id, employee_code, status | unique(employee_code), index(primary_team_id) |
| app_agent | id, team_id, name, status, fallback_model_id | index(team_id) |
| agent_api_key | id, agent_id, key_prefix, secret_hash, expires_at, status | unique(secret_hash), index(agent_id,status) |
| employee_credential | id, employee_id, prefix, secret_hash, expires_at, status | unique(secret_hash) |
| employee_agent_permission | employee_id, agent_id, enabled | unique(employee_id,agent_id) |
| agent_model_allowlist | agent_id, model_id, enabled | unique(agent_id,model_id) |
| provider | id, name, type, status | unique(name) |
| logical_model | id, code, status, input_limit | unique(code) |
| model_deployment | id, model_id, provider_id, endpoint_url, remote_name, secret_cipher, weight, status | index(model_id,status), 外部 URL 白名单验证 |
| price_version | id, target_type, target_id, price_kind, currency_or_points, amount_per_million, valid_from, valid_to | index(target_type,target_id,valid_from)；不可交叉生效 |

## 财务权威表

| 表 | 核心字段 | 关键约束 |
|---|---|---|
| quota_bucket | id, owner_type, owner_id, agent_id_nullable, period_start, period_end, balance, reserved, version | unique(owner_type,owner_id,agent_id,period_start)（NULL 组合需归一化） |
| team_pool | team_id, balance, reserved, version | pk(team_id) |
| grant_request | id, applicant_employee_id, issuer_team_id, points, status | index(issuer_team_id,status) |
| grant | id, issuer_team_id, employee_id, issued_points, remaining_points, reserved_points, expires_at, approval_id | unique(approval_id) 非空，index(employee_id,expires_at) |
| emergency_authorization | id, task_ref, employee_id, agent_id, budget_points, valid_until, status | index(employee_id,agent_id,valid_until) |
| invocation | id, employee_id, employee_team_snapshot, agent_id, idempotency_key, payload_hash, model_id, deployment_id, state, started_at | unique(agent_id,employee_id,idempotency_key) |
| reservation | id, invocation_id, total_points, state | unique(invocation_id) |
| reservation_allocation | id, reservation_id, source_type, source_id, reserved_points, settled_points | unique(reservation_id,source_type,source_id) |
| usage_settlement | id, invocation_id, input_tokens, output_tokens, cache_tokens, points, price_version_id, provider_cost, provider_currency, state | unique(invocation_id) |
| ledger_entry | id, business_type, business_id, account_ref, amount_points, entry_type, created_at | unique(business_type,business_id,account_ref,entry_type) |
| reconciliation_case | id, invocation_id, reason, state, evidence_ref, resolved_by, resolved_at | unique(invocation_id) |
| outbox_event | id, event_type, aggregate_id, payload_json, status, attempts, occurred_at | index(status,occurred_at) |

## 安全与分析

safety_policy(id,scope,status,current_version)、safety_policy_revision(id,policy_id,revision,rules_json,published_at)、safety_decision(id,invocation_id,policy_version_id,decision,risk_code,occurred_at)；只写最小元数据。

finops_event_receipt(event_id,consumer_id,processed_at) unique(event_id,consumer_id)、finops_daily_usage(day,employee_id,team_snapshot_id,agent_id,model_id,deployment_id,point_usage,cost,currency)、team_grant_daily(day,issuer_team_id,issued_points) 与 cost_alert(id,kind,target_id,threshold,state)。复合统计主键覆盖所有分组维度。

## 事务与索引纪律

授额：锁 Team 池 → 校验 → 扣池 → 创建 Grant → ledger/outbox，同事务。
预留：按资金来源的稳定排序锁住所有桶/Grant → 校验合计 → 原子扣可用、增 reserved → 记 Allocation。
结算：用唯一 invocationId 锁 reservation → 按可信 usage 更新各 Allocation、释放差额、写结算与 outbox。
未知：保留待核对状态和审计证据。任何请求不可在数据库事务中等待 Provider 网络 I/O。
金额禁止 float/double；未分配 tenantId 的单企业部署不可声称多租户隔离。详细数值示例见 02。
