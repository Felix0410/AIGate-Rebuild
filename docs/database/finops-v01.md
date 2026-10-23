# FinOps V01 · V1__finops.sql

> 首次实施：P11。独立于Core迁移与schema history；真实MySQL8.4/Flyway尚未运行。

## 1. 独立数据归属

FinOps应用独占finops_db，存7张表：消费Inbox、Fact、日汇总、运营费用/更正、管理审计与读模型状态。不能访问或修改Core财务写主，也不能跨库外键。

## 2. 规范SQL

```sql
CREATE TABLE finops_processed_event (
  consumer_name VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
  event_id CHAR(36) COLLATE utf8mb4_bin NOT NULL,
  source_core_outbox_id BIGINT UNSIGNED NULL,
  payload_digest BINARY(32) NOT NULL,
  schema_version SMALLINT UNSIGNED NOT NULL,
  processed_at DATETIME(6) NOT NULL,
  PRIMARY KEY(consumer_name,event_id),
  KEY idx_inbox_processed(processed_at,event_id),
  CONSTRAINT ck_inbox_schema CHECK(schema_version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finops_fact (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  build_id BIGINT UNSIGNED NOT NULL,
  source_kind VARCHAR(24) COLLATE utf8mb4_bin NOT NULL,
  source_business_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  source_revision BIGINT UNSIGNED NOT NULL,
  metric_type VARCHAR(48) COLLATE utf8mb4_bin NOT NULL,
  business_date DATE NOT NULL,
  company_scope_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
  employee_id BIGINT UNSIGNED NULL,
  usage_team_snapshot_id BIGINT UNSIGNED NULL,
  issuer_team_id BIGINT UNSIGNED NULL,
  recipient_employee_id BIGINT UNSIGNED NULL,
  agent_id BIGINT UNSIGNED NULL,
  logical_model_id BIGINT UNSIGNED NULL,
  deployment_id BIGINT UNSIGNED NULL,
  provider_id BIGINT UNSIGNED NULL,
  resource_pool_id BIGINT UNSIGNED NULL,
  service_class VARCHAR(24) NULL,
  unit VARCHAR(16) NOT NULL,
  currency CHAR(3) COLLATE utf8mb4_bin NULL,
  points_amount DECIMAL(20,6) NULL,
  currency_amount DECIMAL(20,8) NULL,
  token_count BIGINT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_fact_source_revision(build_id,source_kind,source_business_id,source_revision,metric_type),
  KEY idx_fact_business_team(build_id,business_date,usage_team_snapshot_id,metric_type),
  KEY idx_fact_issuer(build_id,issuer_team_id,business_date,metric_type),
  KEY idx_fact_employee(build_id,employee_id,business_date,metric_type),
  CONSTRAINT ck_fact_source CHECK(source_kind IN ('CORE_EVENT','LOCAL_EXPENSE')),
  CONSTRAINT ck_fact_unit CHECK(
    (unit='POINTS' AND currency IS NULL AND points_amount IS NOT NULL AND currency_amount IS NULL AND token_count IS NULL)
    OR (unit='CURRENCY' AND currency IS NOT NULL AND currency_amount IS NOT NULL AND points_amount IS NULL AND token_count IS NULL)
    OR (unit='TOKENS' AND currency IS NULL AND token_count IS NOT NULL AND points_amount IS NULL AND currency_amount IS NULL)
  ),
  CONSTRAINT ck_fact_metric CHECK(metric_type IN (
    'EMPLOYEE_USAGE_POINTS','TEAM_GRANT_ISSUED_POINTS',
    'PROVIDER_API_COST_ESTIMATE','SELF_HOSTED_OPERATING_COST','LOCAL_FALLBACK_TOKENS_USED'
  ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finops_daily_aggregate (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  build_id BIGINT UNSIGNED NOT NULL,
  company_scope_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
  business_date DATE NOT NULL,
  metric_type VARCHAR(48) COLLATE utf8mb4_bin NOT NULL,
  unit VARCHAR(16) NOT NULL,
  currency_code CHAR(3) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  employee_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  usage_team_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  issuer_team_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  agent_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  logical_model_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  deployment_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  provider_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  resource_pool_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  service_class VARCHAR(24) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  confirmed_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  confirmed_amount DECIMAL(20,8) NOT NULL DEFAULT 0,
  token_count BIGINT NOT NULL DEFAULT 0,
  fact_count BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_daily_dimension(
    build_id,company_scope_id,business_date,metric_type,unit,currency_code,
    employee_id,usage_team_id,issuer_team_id,agent_id,logical_model_id,
    deployment_id,provider_id,resource_pool_id,service_class
  ),
  KEY idx_agg_company_day(build_id,business_date,metric_type,unit,currency_code),
  KEY idx_agg_team_day(build_id,usage_team_id,business_date,metric_type),
  KEY idx_agg_employee_day(build_id,employee_id,business_date,metric_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finops_read_model_state (
  id TINYINT UNSIGNED NOT NULL,
  active_build_id BIGINT UNSIGNED NOT NULL,
  building_build_id BIGINT UNSIGNED NULL,
  last_processed_at DATETIME(6) NULL,
  last_verified_manifest_at DATETIME(6) NULL,
  last_verified_manifest_digest BINARY(32) NULL,
  lag_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  schema_revision INT NOT NULL DEFAULT 1,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  CONSTRAINT ck_read_state_singleton CHECK(id=1),
  CONSTRAINT ck_read_state_lag CHECK(lag_status IN (
    'FRESH','DELAYED','UNKNOWN','REBUILDING'
  )),
  CONSTRAINT ck_read_state_build CHECK(building_build_id IS NULL OR building_build_id<>active_build_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE self_hosted_operating_expense (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  expense_code VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
  resource_pool_id BIGINT UNSIGNED NOT NULL,
  expense_category VARCHAR(32) NOT NULL,
  service_period_month DATE NOT NULL,
  currency CHAR(3) COLLATE utf8mb4_bin NOT NULL,
  amount DECIMAL(20,8) NOT NULL,
  source_kind VARCHAR(32) NOT NULL,
  source_ref VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  evidence_digest BINARY(32) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT UNSIGNED NOT NULL,
  creation_operation_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  creation_request_hmac BINARY(32) NOT NULL,
  creation_hmac_key_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  approved_by BIGINT UNSIGNED NULL,
  revision INT NOT NULL DEFAULT 1,
  approved_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_expense_code(expense_code),
  UNIQUE KEY uk_expense_creation_op(created_by,creation_operation_key),
  UNIQUE KEY uk_expense_origin(source_kind,source_ref,resource_pool_id,service_period_month),
  KEY idx_expense_month_status(service_period_month,status,currency),
  CONSTRAINT ck_expense_category CHECK(expense_category IN (
    'GPU_RENT','DEPRECIATION','ELECTRICITY','HOSTING','OPERATIONS'
  )),
  CONSTRAINT ck_expense_source CHECK(source_kind IN (
    'CLOUD_BILL','RENTAL_CONTRACT','ACCOUNTING_ADJUSTMENT'
  )),
  CONSTRAINT ck_expense_status CHECK(status IN('DRAFT','APPROVED','VOIDED')),
  CONSTRAINT ck_expense_currency CHECK(currency REGEXP '^[A-Z]{3}$'),
  CONSTRAINT ck_expense_amount CHECK(amount>=0 AND revision>0),
  CONSTRAINT ck_expense_creation_key CHECK(CHAR_LENGTH(creation_operation_key) BETWEEN 8 AND 128),
  CONSTRAINT ck_expense_full_month CHECK(DAYOFMONTH(service_period_month)=1),
  CONSTRAINT ck_expense_two_person CHECK(approved_by IS NULL OR approved_by<>created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE self_hosted_expense_revision (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  expense_id BIGINT UNSIGNED NOT NULL,
  revision INT NOT NULL,
  operation_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  operation VARCHAR(16) NOT NULL,
  amount_before DECIMAL(20,8) NOT NULL,
  amount_after DECIMAL(20,8) NOT NULL,
  delta_amount DECIMAL(20,8) NOT NULL,
  actor_employee_id BIGINT UNSIGNED NOT NULL,
  reason_code VARCHAR(64) NOT NULL,
  committed_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_expense_revision(expense_id,revision),
  UNIQUE KEY uk_expense_operation_key(operation_key),
  CONSTRAINT fk_expense_revision_expense FOREIGN KEY(expense_id) REFERENCES self_hosted_operating_expense(id) ON DELETE RESTRICT,
  CONSTRAINT ck_expense_revision_pos CHECK(revision>0),
  CONSTRAINT ck_expense_revision_operation CHECK(operation IN ('APPROVE','CORRECT','VOID')),
  CONSTRAINT ck_expense_revision_delta CHECK(delta_amount=amount_after-amount_before)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finops_management_audit (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  actor_employee_id BIGINT UNSIGNED NOT NULL,
  operation VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  authorized_at DATETIME(6) NOT NULL,
  committed_at DATETIME(6) NOT NULL,
  request_id VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
  expense_id BIGINT UNSIGNED NULL,
  build_id BIGINT UNSIGNED NULL,
  operation_key VARCHAR(128) COLLATE utf8mb4_bin NULL,
  operation_request_hmac BINARY(32) NULL,
  operation_hmac_key_id VARCHAR(32) COLLATE utf8mb4_bin NULL,
  safe_result_code VARCHAR(64) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_finops_audit_op(actor_employee_id,operation,operation_key),
  KEY idx_finops_audit_actor(actor_employee_id,committed_at),
  KEY idx_finops_audit_expense(expense_id,committed_at),
  CONSTRAINT fk_finops_audit_expense FOREIGN KEY(expense_id) REFERENCES self_hosted_operating_expense(id) ON DELETE RESTRICT,
  CONSTRAINT ck_finops_audit_receipt_pair CHECK(
    (operation_key IS NULL AND operation_request_hmac IS NULL AND operation_hmac_key_id IS NULL)
    OR (operation_key IS NOT NULL AND operation_request_hmac IS NOT NULL AND operation_hmac_key_id IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO finops_read_model_state
(id,active_build_id,building_build_id,last_processed_at,
last_verified_manifest_at,last_verified_manifest_digest,
lag_status,schema_revision,updated_at)
VALUES (1,1,NULL,NULL,NULL,NULL,'UNKNOWN',1,UTC_TIMESTAMP(6));
```

## 3. 执行义务

- 消费：同一buildId+eventId重复收据至多一事实；摘要不匹配必须隔离。Core payload 64位hex要正确转为BINARY(32)。
- 运营费用与FinOps重建的幂等字段/Unique及BuildId已纳入上述规范，不能只在内存去重。
- SourceManifest无法核实完整时DELAYED/UNKNOWN，绝不伪装0；FinOps运营成本必须另一受权管理员审批。
- 当前仅DESIGN_REVIEWED，全部真实MQ/数据库/锁定测试NOT RUN。

详见 [FinOps事件协议](../protocols/finops-events.md)。
