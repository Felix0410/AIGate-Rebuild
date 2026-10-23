# Core V09 · V9__fallback_quota.sql

> 实施时点：P9；本页为**设计SQL基准**，尚未经过MySQL8.4/Flyway实测。实际迁移文件由本地AI创建。已运行版本禁止原地编辑。

## 1. 目的与实施范围

LOW的公司补贴/员工周Cap/本地Token与FallbackPolicy，并追加Invocation受信真实降级快照。

## 2. 前置条件

必须先通过Core V8；旧表及已提交历史不得重建或删改。

## 3. 规范 SQL（5 张新增表，含所需旧表ALTER）

```sql
CREATE TABLE fallback_route_policy (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  agent_id BIGINT UNSIGNED NOT NULL,
  requested_model_id BIGINT UNSIGNED NOT NULL,
  fallback_model_id BIGINT UNSIGNED NOT NULL,
  fallback_kind VARCHAR(24) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  policy_revision INT NOT NULL,
  valid_from DATETIME(6) NOT NULL,
  valid_until DATETIME(6) NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  approved_by BIGINT UNSIGNED NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_fallback_policy_revision(agent_id,requested_model_id,fallback_model_id,policy_revision),
  KEY idx_fallback_candidate(agent_id,requested_model_id,status,valid_until),
  CONSTRAINT fk_fallback_agent FOREIGN KEY(agent_id) REFERENCES agent(id) ON DELETE RESTRICT,
  CONSTRAINT fk_fallback_requested FOREIGN KEY(requested_model_id) REFERENCES logical_model(id) ON DELETE RESTRICT,
  CONSTRAINT fk_fallback_model FOREIGN KEY(fallback_model_id) REFERENCES logical_model(id) ON DELETE RESTRICT,
  CONSTRAINT fk_fallback_creator FOREIGN KEY(created_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_fallback_approver FOREIGN KEY(approved_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_fallback_kind CHECK(fallback_kind IN ('PAID_API','LOCAL_BASIC')),
  CONSTRAINT ck_fallback_status CHECK(status IN ('DRAFT','APPROVED','DISABLED')),
  CONSTRAINT ck_fallback_valid CHECK(valid_until>valid_from AND policy_revision>0),
  CONSTRAINT ck_fallback_actual_change CHECK(fallback_model_id<>requested_model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE corporate_fallback_pool (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  company_scope_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
  period_start DATETIME(6) NOT NULL,
  period_end DATETIME(6) NOT NULL,
  limit_points DECIMAL(20,6) NOT NULL,
  used_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  reserved_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  risk_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_corporate_fallback_month(company_scope_id,period_start),
  CONSTRAINT ck_corporate_fallback_period CHECK(period_end>period_start),
  CONSTRAINT ck_corporate_fallback_bal CHECK(
    limit_points>=0 AND used_points>=0 AND reserved_points>=0
    AND used_points+reserved_points<=limit_points
  ),
  CONSTRAINT ck_corporate_fallback_state CHECK(risk_state IN ('ACTIVE','FROZEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employee_fallback_weekly_cap (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_id BIGINT UNSIGNED NOT NULL,
  period_start DATETIME(6) NOT NULL,
  period_end DATETIME(6) NOT NULL,
  limit_points DECIMAL(20,6) NOT NULL,
  used_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  reserved_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_employee_fallback_week(employee_id,period_start),
  CONSTRAINT fk_fallback_cap_employee FOREIGN KEY(employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_fallback_cap_period CHECK(period_end>period_start),
  CONSTRAINT ck_fallback_cap_nonneg CHECK(
    limit_points>=0 AND used_points>=0 AND reserved_points>=0
    AND used_points+reserved_points<=limit_points
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE local_fallback_quota (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_id BIGINT UNSIGNED NOT NULL,
  period_start DATETIME(6) NOT NULL,
  period_end DATETIME(6) NOT NULL,
  limit_tokens BIGINT UNSIGNED NOT NULL,
  used_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
  reserved_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
  policy_revision INT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_local_fallback_employee_week(employee_id,period_start),
  CONSTRAINT fk_local_fallback_employee FOREIGN KEY(employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_local_fallback_period CHECK(period_end>period_start),
  CONSTRAINT ck_local_fallback_within CHECK(used_tokens+reserved_tokens<=limit_tokens AND policy_revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE local_fallback_reservation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  finance_operation_id BIGINT UNSIGNED NOT NULL,
  quota_id BIGINT UNSIGNED NOT NULL,
  reserved_tokens BIGINT UNSIGNED NOT NULL,
  settled_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
  state VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_local_fallback_operation(finance_operation_id),
  KEY idx_local_fallback_quota(quota_id,state),
  CONSTRAINT fk_local_reservation_finance FOREIGN KEY(finance_operation_id) REFERENCES finance_operation(id) ON DELETE RESTRICT,
  CONSTRAINT fk_local_reservation_quota FOREIGN KEY(quota_id) REFERENCES local_fallback_quota(id) ON DELETE RESTRICT,
  CONSTRAINT ck_local_reservation_points CHECK(settled_tokens<=reserved_tokens),
  CONSTRAINT ck_local_reservation_state CHECK(state IN (
    'RESERVED','SETTLED','RELEASED','PENDING_RECONCILIATION','OVERRUN'
  ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 同一V9末尾必须应用D43补充
ALTER TABLE invocation
  ADD COLUMN actual_model_id BIGINT UNSIGNED NULL,
  ADD COLUMN fallback_route_policy_id BIGINT UNSIGNED NULL,
  ADD COLUMN fallback_policy_revision INT NULL,
  ADD COLUMN funding_mode VARCHAR(32) NULL,
  ADD CONSTRAINT fk_invocation_actual_model FOREIGN KEY (actual_model_id)
    REFERENCES logical_model(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_invocation_fallback_policy FOREIGN KEY (fallback_route_policy_id)
    REFERENCES fallback_route_policy(id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_invocation_funding_mode CHECK (
    funding_mode IS NULL OR funding_mode IN (
      'NORMAL_POINTS','CORPORATE_POINTS','LOCAL_WEEKLY_TOKENS'
    )
  ),
  ADD CONSTRAINT ck_invocation_fallback_snapshot CHECK (
    (fallback_route_policy_id IS NULL AND fallback_policy_revision IS NULL)
    OR (fallback_route_policy_id IS NOT NULL AND fallback_policy_revision>0
      AND actual_model_id IS NOT NULL)
  );
```

## 4. 业务不变量与测试门禁

- 新建表数、外键、唯一索引及CHECK必须匹配本基准；不得为避免迁移报错删约束。
- 先执行真实MySQL8.4/Flyway新库迁移，再执行上一版本真实历史升级，记录`schema_history`与checksum。
- 使用应用账户验证不能执行DDL；检查业务数据并发和外键拒绝情形。
- 仅本Phase上线后才可以访问对应管理API与受信内部RPC。
- 本规范只证明设计选择已明确，**真实测试状态NOT RUN**。

参照 [数据库目录](../05-database-design.md)、[所属模块](../phases/README.md)、[验收](../09-test-plan.md)。
