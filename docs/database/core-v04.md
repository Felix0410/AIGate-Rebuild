# Core V04 · V4__normal_quota_billing.sql

> 实施时点：P4；本页为**设计SQL基准**，尚未经过MySQL8.4/Flyway实测。实际迁移文件由本地AI创建。已运行版本禁止原地编辑。

## 1. 目的与实施范围

Quota、采购月总预算和分区、账务预留/Ledger/Outbox全部在Core同一MySQL。

## 2. 前置条件

必须先通过Core V3；旧表及已提交历史不得重建或删改。

## 3. 规范 SQL（10 张新增表）

```sql
CREATE TABLE quota_policy (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  scope_type VARCHAR(24) NOT NULL,
  scope_id BIGINT UNSIGNED NOT NULL,
  revision INT NOT NULL,
  weekly_limit_points DECIMAL(20,6) NOT NULL,
  effective_from DATETIME(6) NOT NULL,
  effective_to DATETIME(6) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_quota_policy_revision(scope_type,scope_id,revision),
  KEY idx_quota_policy_effective(scope_type,scope_id,status,effective_from),
  CONSTRAINT fk_quota_policy_creator FOREIGN KEY(created_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_quota_policy_scope CHECK(scope_type IN ('AGENT_WEEK','EMPLOYEE_WEEK')),
  CONSTRAINT ck_quota_policy_status CHECK(status IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT ck_quota_policy_amount CHECK(weekly_limit_points>=0 AND revision>0),
  CONSTRAINT ck_quota_policy_period CHECK(effective_to IS NULL OR effective_to>effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE quota_bucket (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_id BIGINT UNSIGNED NOT NULL,
  bucket_kind VARCHAR(24) NOT NULL,
  agent_scope_id BIGINT UNSIGNED NOT NULL,
  period_start DATETIME(6) NOT NULL,
  period_end DATETIME(6) NOT NULL,
  limit_points DECIMAL(20,6) NOT NULL,
  used_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  reserved_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  policy_revision INT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_quota_owner_week(employee_id,bucket_kind,agent_scope_id,period_start),
  KEY idx_quota_employee_period(employee_id,period_end),
  CONSTRAINT fk_quota_employee FOREIGN KEY(employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_quota_bucket_kind CHECK(bucket_kind IN ('AGENT_WEEK','EMPLOYEE_WEEK')),
  CONSTRAINT ck_quota_scope CHECK(
    (bucket_kind='AGENT_WEEK' AND agent_scope_id>0)
    OR (bucket_kind='EMPLOYEE_WEEK' AND agent_scope_id=0)
  ),
  CONSTRAINT ck_quota_period CHECK(period_end>period_start),
  CONSTRAINT ck_quota_nonneg CHECK(limit_points>=0 AND used_points>=0 AND reserved_points>=0),
  CONSTRAINT ck_quota_within CHECK(used_points+reserved_points<=limit_points)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE provider_spend_budget (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  company_scope_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
  currency CHAR(3) COLLATE utf8mb4_bin NOT NULL,
  period_start DATETIME(6) NOT NULL,
  period_end DATETIME(6) NOT NULL,
  limit_amount DECIMAL(20,8) NOT NULL,
  settled_amount DECIMAL(20,8) NOT NULL DEFAULT 0,
  reserved_amount DECIMAL(20,8) NOT NULL DEFAULT 0,
  risk_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_provider_budget_period(company_scope_id,currency,period_start),
  CONSTRAINT ck_provider_budget_currency CHECK(currency REGEXP '^[A-Z]{3}$'),
  CONSTRAINT ck_provider_budget_period CHECK(period_end>period_start),
  CONSTRAINT ck_provider_budget_nonneg CHECK(
    limit_amount>=0 AND settled_amount>=0 AND reserved_amount>=0
  ),
  CONSTRAINT ck_provider_budget_risk_state CHECK(risk_state IN ('ACTIVE','FROZEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE provider_spend_partition (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  budget_id BIGINT UNSIGNED NOT NULL,
  partition_code VARCHAR(16) NOT NULL,
  limit_amount DECIMAL(20,8) NOT NULL,
  settled_amount DECIMAL(20,8) NOT NULL DEFAULT 0,
  reserved_amount DECIMAL(20,8) NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_provider_partition(budget_id,partition_code),
  CONSTRAINT fk_partition_budget FOREIGN KEY(budget_id) REFERENCES provider_spend_budget(id) ON DELETE RESTRICT,
  CONSTRAINT ck_partition_kind CHECK(partition_code IN ('NORMAL','EMERGENCY')),
  CONSTRAINT ck_partition_nonneg CHECK(limit_amount>=0 AND settled_amount>=0 AND reserved_amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_operation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  operation_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  employee_id BIGINT UNSIGNED NOT NULL,
  agent_id BIGINT UNSIGNED NOT NULL,
  logical_model_id BIGINT UNSIGNED NOT NULL,
  deployment_id BIGINT UNSIGNED NOT NULL,
  service_class VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
  status VARCHAR(32) NOT NULL,
  points_price_version_id BIGINT UNSIGNED NOT NULL,
  provider_price_version_id BIGINT UNSIGNED NULL,
  currency CHAR(3) COLLATE utf8mb4_bin NULL,
  employee_team_snapshot_id BIGINT UNSIGNED NOT NULL,
  employee_week_start DATETIME(6) NOT NULL,
  company_period_start DATETIME(6) NOT NULL,
  max_input_tokens BIGINT UNSIGNED NOT NULL,
  max_output_tokens BIGINT UNSIGNED NOT NULL,
  max_points DECIMAL(20,6) NOT NULL,
  max_currency_amount DECIMAL(20,8) NULL,
  usage_evidence_digest BINARY(32) NULL,
  overrun_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  overrun_currency_amount DECIMAL(20,8) NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_finance_operation_key(operation_key),
  KEY idx_finance_employee_created(employee_id,created_at,id),
  CONSTRAINT fk_finance_employee FOREIGN KEY(employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_finance_agent FOREIGN KEY(agent_id) REFERENCES agent(id) ON DELETE RESTRICT,
  CONSTRAINT fk_finance_model FOREIGN KEY(logical_model_id) REFERENCES logical_model(id) ON DELETE RESTRICT,
  CONSTRAINT fk_finance_deployment FOREIGN KEY(deployment_id) REFERENCES model_deployment(id) ON DELETE RESTRICT,
  CONSTRAINT fk_finance_points_price FOREIGN KEY(points_price_version_id) REFERENCES model_points_price_version(id) ON DELETE RESTRICT,
  CONSTRAINT fk_finance_purchase_price FOREIGN KEY(provider_price_version_id) REFERENCES deployment_purchase_price_version(id) ON DELETE RESTRICT,
  CONSTRAINT fk_finance_team_snapshot FOREIGN KEY(employee_team_snapshot_id) REFERENCES team(id) ON DELETE RESTRICT,
  CONSTRAINT ck_finance_status CHECK(status IN (
    'RESERVED','SETTLED','RELEASED','PENDING_RECONCILIATION','OVERRUN'
  )),
  CONSTRAINT ck_finance_class CHECK(service_class IN ('NORMAL','LOW','EMERGENCY','LOCAL_FALLBACK')),
  CONSTRAINT ck_finance_currency_pair CHECK(
    (provider_price_version_id IS NULL AND currency IS NULL AND max_currency_amount IS NULL)
    OR (provider_price_version_id IS NOT NULL AND currency IS NOT NULL AND max_currency_amount IS NOT NULL)
  ),
  CONSTRAINT ck_finance_max_nonneg CHECK(max_points>=0 AND
    (max_currency_amount IS NULL OR max_currency_amount>=0) AND
    overrun_points>=0 AND overrun_currency_amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE points_reservation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  finance_operation_id BIGINT UNSIGNED NOT NULL,
  reserved_points DECIMAL(20,6) NOT NULL,
  settled_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  state VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_points_operation(finance_operation_id),
  CONSTRAINT fk_points_reservation_operation FOREIGN KEY(finance_operation_id) REFERENCES finance_operation(id) ON DELETE RESTRICT,
  CONSTRAINT ck_points_reserved CHECK(reserved_points>=0 AND settled_points>=0 AND settled_points<=reserved_points),
  CONSTRAINT ck_points_state CHECK(state IN ('RESERVED','SETTLED','RELEASED','PENDING_RECONCILIATION','OVERRUN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reservation_allocation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  points_reservation_id BIGINT UNSIGNED NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  priority_order INT NOT NULL,
  reserved_points DECIMAL(20,6) NOT NULL,
  settled_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  released_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_allocation_source(points_reservation_id,source_type,source_id),
  KEY idx_allocation_source(source_type,source_id),
  CONSTRAINT fk_allocation_reservation FOREIGN KEY(points_reservation_id) REFERENCES points_reservation(id) ON DELETE RESTRICT,
  CONSTRAINT ck_allocation_source CHECK(source_type IN (
    'AGENT_WEEK','EMPLOYEE_WEEK','GRANT','COMPANY_FALLBACK','CORPORATE_EMERGENCY'
  )),
  CONSTRAINT ck_allocation_nonneg CHECK(
    reserved_points>=0 AND settled_points>=0 AND released_points>=0 AND
    settled_points+released_points<=reserved_points
  ),
  CONSTRAINT ck_allocation_priority CHECK(priority_order>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE provider_spend_reservation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  finance_operation_id BIGINT UNSIGNED NOT NULL,
  budget_id BIGINT UNSIGNED NOT NULL,
  partition_id BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) COLLATE utf8mb4_bin NOT NULL,
  reserved_amount DECIMAL(20,8) NOT NULL,
  settled_amount DECIMAL(20,8) NOT NULL DEFAULT 0,
  state VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_provider_reservation_operation(finance_operation_id),
  KEY idx_provider_reservation_budget(budget_id,partition_id,state),
  CONSTRAINT fk_provider_reservation_operation FOREIGN KEY(finance_operation_id) REFERENCES finance_operation(id) ON DELETE RESTRICT,
  CONSTRAINT fk_provider_reservation_budget FOREIGN KEY(budget_id) REFERENCES provider_spend_budget(id) ON DELETE RESTRICT,
  CONSTRAINT fk_provider_reservation_partition FOREIGN KEY(partition_id) REFERENCES provider_spend_partition(id) ON DELETE RESTRICT,
  CONSTRAINT ck_provider_reservation_values CHECK(reserved_amount>=0 AND settled_amount>=0),
  CONSTRAINT ck_provider_reservation_state CHECK(state IN (
    'RESERVED','SETTLED','RELEASED','PENDING_RECONCILIATION','OVERRUN'
  ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ledger_entry (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  finance_operation_id BIGINT UNSIGNED NULL,
  event_kind VARCHAR(48) COLLATE utf8mb4_bin NOT NULL,
  source_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  unit VARCHAR(16) NOT NULL,
  currency CHAR(3) COLLATE utf8mb4_bin NULL,
  delta_amount DECIMAL(20,8) NOT NULL,
  business_at DATETIME(6) NOT NULL,
  source_revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
  operation_event_key VARCHAR(192) COLLATE utf8mb4_bin NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_ledger_business_event(operation_event_key),
  KEY idx_ledger_operation(finance_operation_id,id),
  KEY idx_ledger_account(source_type,source_id,business_at,id),
  CONSTRAINT fk_ledger_finance_operation FOREIGN KEY(finance_operation_id) REFERENCES finance_operation(id) ON DELETE RESTRICT,
  CONSTRAINT ck_ledger_unit CHECK(
    (unit='POINTS' AND currency IS NULL)
    OR (unit='CURRENCY' AND currency IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id CHAR(36) COLLATE utf8mb4_bin NOT NULL,
  business_event_key VARCHAR(192) COLLATE utf8mb4_bin NOT NULL,
  aggregate_type VARCHAR(48) COLLATE utf8mb4_bin NOT NULL,
  aggregate_id BIGINT UNSIGNED NOT NULL,
  aggregate_revision BIGINT UNSIGNED NOT NULL,
  event_type VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  schema_version SMALLINT UNSIGNED NOT NULL,
  company_scope_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
  business_at DATETIME(6) NOT NULL,
  payload_json JSON NOT NULL,
  payload_digest CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  publish_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  publish_attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NULL,
  owner_token VARCHAR(64) COLLATE utf8mb4_bin NULL,
  lease_until DATETIME(6) NULL,
  last_error_code VARCHAR(64) NULL,
  published_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_outbox_event_id(event_id),
  UNIQUE KEY uk_outbox_business_event(business_event_key),
  KEY idx_outbox_due(publish_state,next_attempt_at,id),
  KEY idx_outbox_lease(lease_until,publish_state),
  KEY idx_outbox_aggregate(aggregate_type,aggregate_id,aggregate_revision),
  CONSTRAINT ck_outbox_status CHECK(publish_state IN (
    'PENDING','CLAIMED','PUBLISHED','RETRY','QUARANTINED'
  )),
  CONSTRAINT ck_outbox_attempts CHECK(publish_attempts>=0 AND schema_version>0),
  CONSTRAINT ck_outbox_claim_pair CHECK(
    (publish_state='CLAIMED' AND owner_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (publish_state<>'CLAIMED')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

## 4. 业务不变量与测试门禁

- 新建表数、外键、唯一索引及CHECK必须匹配本基准；不得为避免迁移报错删约束。
- 先执行真实MySQL8.4/Flyway新库迁移，再执行上一版本真实历史升级，记录`schema_history`与checksum。
- 使用应用账户验证不能执行DDL；检查业务数据并发和外键拒绝情形。
- 仅本Phase上线后才可以访问对应管理API与受信内部RPC。
- 本规范只证明设计选择已明确，**真实测试状态NOT RUN**。

参照 [数据库目录](../05-database-design.md)、[所属模块](../06-module-design.md)、[验收](../09-test-plan.md)。
