# Core V10 · V10__emergency_authorization.sql

> 实施时点：P10；本页为**设计SQL基准**，尚未经过MySQL8.4/Flyway实测。实际迁移文件由本地AI创建。已运行版本禁止原地编辑。

## 1. 目的与实施范围

紧急申请/授权/唯一Slot及资金，追加Invocation授权/TaskRef和EMERGENCY_POINTS资金枚举。

## 2. 前置条件

必须先通过Core V9；旧表及已提交历史不得重建或删改。

## 3. 规范 SQL（5 张新增表，含所需旧表ALTER）

```sql
CREATE TABLE corporate_emergency_pool (
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
  UNIQUE KEY uk_corporate_emergency_period(company_scope_id,period_start),
  CONSTRAINT ck_emergency_pool_period CHECK(period_end>period_start),
  CONSTRAINT ck_emergency_pool_balance CHECK(limit_points>=0 AND used_points>=0 AND
    reserved_points>=0 AND used_points+reserved_points<=limit_points),
  CONSTRAINT ck_emergency_pool_state CHECK(risk_state IN ('ACTIVE','FROZEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE emergency_request (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  requester_employee_id BIGINT UNSIGNED NOT NULL,
  beneficiary_employee_id BIGINT UNSIGNED NOT NULL,
  agent_id BIGINT UNSIGNED NOT NULL,
  task_ref VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
  reason_code VARCHAR(64) NOT NULL,
  safe_reason_description VARCHAR(512) NULL,
  requested_limit_points DECIMAL(20,6) NOT NULL,
  requested_model_ids_json JSON NOT NULL,
  requested_valid_from DATETIME(6) NOT NULL,
  requested_valid_until DATETIME(6) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  version BIGINT NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_emergency_request(requester_employee_id,idempotency_key),
  KEY idx_emergency_beneficiary(beneficiary_employee_id,status,created_at),
  CONSTRAINT fk_emerg_req_requester FOREIGN KEY(requester_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_emerg_req_beneficiary FOREIGN KEY(beneficiary_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_emerg_req_agent FOREIGN KEY(agent_id) REFERENCES agent(id) ON DELETE RESTRICT,
  CONSTRAINT ck_emergency_request_state CHECK(status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
  CONSTRAINT ck_emergency_request_limit CHECK(requested_limit_points>0 AND version>=0),
  CONSTRAINT ck_emergency_request_period CHECK(requested_valid_until>requested_valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE emergency_authorization (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  request_id BIGINT UNSIGNED NOT NULL,
  beneficiary_employee_id BIGINT UNSIGNED NOT NULL,
  agent_id BIGINT UNSIGNED NOT NULL,
  task_ref VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
  approved_limit_points DECIMAL(20,6) NOT NULL,
  used_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  reserved_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  valid_from DATETIME(6) NOT NULL,
  valid_until DATETIME(6) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  approved_by BIGINT UNSIGNED NOT NULL,
  approved_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6) NULL,
  policy_revision BIGINT UNSIGNED NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_emergency_auth_request(request_id),
  KEY idx_emergency_auth_owner(beneficiary_employee_id,agent_id,status,valid_until),
  CONSTRAINT fk_emergency_auth_request FOREIGN KEY(request_id) REFERENCES emergency_request(id) ON DELETE RESTRICT,
  CONSTRAINT fk_emergency_auth_beneficiary FOREIGN KEY(beneficiary_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_emergency_auth_agent FOREIGN KEY(agent_id) REFERENCES agent(id) ON DELETE RESTRICT,
  CONSTRAINT fk_emergency_auth_approver FOREIGN KEY(approved_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_emergency_auth_state CHECK(status IN ('ACTIVE','REVOKED','EXPIRED')),
  CONSTRAINT ck_emergency_auth_period CHECK(valid_until>valid_from),
  CONSTRAINT ck_emergency_auth_balance CHECK(approved_limit_points>0 AND
    used_points>=0 AND reserved_points>=0 AND
    used_points+reserved_points<=approved_limit_points),
  CONSTRAINT ck_emergency_auth_revoke CHECK(
    (status='REVOKED' AND revoked_at IS NOT NULL)
    OR (status<>'REVOKED')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE emergency_authorization_model (
  authorization_id BIGINT UNSIGNED NOT NULL,
  logical_model_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY(authorization_id,logical_model_id),
  KEY idx_emergency_auth_model(logical_model_id,authorization_id),
  CONSTRAINT fk_emergency_auth_model_authorization FOREIGN KEY(authorization_id) REFERENCES emergency_authorization(id) ON DELETE RESTRICT,
  CONSTRAINT fk_emergency_auth_model_model FOREIGN KEY(logical_model_id) REFERENCES logical_model(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE emergency_active_slot (
  employee_id BIGINT UNSIGNED NOT NULL,
  agent_id BIGINT UNSIGNED NOT NULL,
  task_ref VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
  authorization_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(employee_id,agent_id,task_ref),
  UNIQUE KEY uk_active_emergency_authorization(authorization_id),
  CONSTRAINT fk_slot_employee FOREIGN KEY(employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_slot_agent FOREIGN KEY(agent_id) REFERENCES agent(id) ON DELETE RESTRICT,
  CONSTRAINT fk_slot_authorization FOREIGN KEY(authorization_id) REFERENCES emergency_authorization(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 同一V10末尾必须应用D43补充
ALTER TABLE invocation
  ADD COLUMN emergency_authorization_id BIGINT UNSIGNED NULL,
  ADD COLUMN emergency_task_ref VARCHAR(96) COLLATE utf8mb4_bin NULL,
  ADD CONSTRAINT fk_invocation_emergency_authorization
    FOREIGN KEY(emergency_authorization_id)
    REFERENCES emergency_authorization(id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_invocation_emergency_pair CHECK (
    (emergency_authorization_id IS NULL AND emergency_task_ref IS NULL)
    OR (emergency_authorization_id IS NOT NULL AND emergency_task_ref IS NOT NULL)
  );

-- V9的资金类型CHECK只覆盖NORMAL/LOW，P10必须用新增迁移显式扩展。
ALTER TABLE invocation
  DROP CHECK ck_invocation_funding_mode,
  ADD CONSTRAINT ck_invocation_funding_mode CHECK (
    funding_mode IS NULL OR funding_mode IN (
      'NORMAL_POINTS','CORPORATE_POINTS','LOCAL_WEEKLY_TOKENS','EMERGENCY_POINTS'
    )
  );
```

## 4. 业务不变量与测试门禁

- 新建表数、外键、唯一索引及CHECK必须匹配本基准；不得为避免迁移报错删约束。
- 先执行真实MySQL8.4/Flyway新库迁移，再执行上一版本真实历史升级，记录`schema_history`与checksum。
- 使用应用账户验证不能执行DDL；检查业务数据并发和外键拒绝情形。
- 仅本Phase上线后才可以访问对应管理API与受信内部RPC。
- 本规范只证明设计选择已明确，**真实测试状态NOT RUN**。

参照 [数据库目录](../05-database-design.md)、[所属模块](../phases/README.md)、[验收](../09-test-plan.md)。
