# Core V07 · V7__invocation_runtime.sql

> 实施时点：P7；本页为**设计SQL基准**。2026-10-28已创建对应V7迁移，并在隔离MySQL8.4通过新库迁移、V6历史升级与约束测试，见[P7启动记录](../phases/p07-start.md)。Runtime完整业务尚未实现；已运行版本禁止原地编辑。

## 1. 目的与实施范围

Invocation/DispatchPermit/Report/风险案/CapacityPool五表，包含RequestHMACKeyId、SchemaVersion及Pool运行Epoch。

## 2. 前置条件

必须先通过Core V6；旧表及已提交历史不得重建或删改。

## 3. 规范 SQL（5 张新增表，含所需旧表ALTER）

```sql
-- 本代码块为增量 DDL，跨版本表定义见前置版本。
-- noinspection SqlResolveForFile
CREATE TABLE capacity_pool_policy (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  pool_code VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
  scope_kind VARCHAR(24) NOT NULL,
  resource_identity_code VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  capacity_evidence_ref VARCHAR(256) COLLATE utf8mb4_bin NOT NULL,
  max_active INT NOT NULL,
  max_waiting INT NOT NULL,
  max_queue_ms BIGINT NOT NULL,
  max_run_ms BIGINT NOT NULL,
  rpm_limit INT NOT NULL,
  admission_epoch BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'DISABLED',
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_capacity_pool_code(pool_code),
  UNIQUE KEY uk_capacity_resource_identity(scope_kind,resource_identity_code),
  CONSTRAINT ck_capacity_kind CHECK(scope_kind IN ('PROVIDER','SELF_HOSTED')),
  CONSTRAINT ck_capacity_status CHECK(status IN ('ACTIVE','DISABLED')),
  CONSTRAINT ck_capacity_limits CHECK(max_active>0 AND max_waiting>=0 AND
    max_queue_ms>0 AND max_run_ms>0 AND rpm_limit>0 AND admission_epoch>=0 AND version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE invocation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_invocation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  finance_operation_id BIGINT UNSIGNED NULL,
  agent_id BIGINT UNSIGNED NOT NULL,
  employee_id BIGINT UNSIGNED NOT NULL,
  employee_team_snapshot_id BIGINT UNSIGNED NOT NULL,
  idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  canonical_schema_version SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  payload_hmac_digest BINARY(32) NOT NULL,
  payload_hmac_key_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  requested_model_id BIGINT UNSIGNED NOT NULL,
  final_deployment_id BIGINT UNSIGNED NULL,
  service_class VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
  execution_state VARCHAR(32) NOT NULL,
  billing_state VARCHAR(32) NOT NULL DEFAULT 'NONE',
  queue_entered_at DATETIME(6) NULL,
  queue_deadline_at DATETIME(6) NULL,
  dispatched_at DATETIME(6) NULL,
  company_time_zone_snapshot VARCHAR(64) NOT NULL,
  policy_revision_id BIGINT UNSIGNED NULL,
  points_price_version_id BIGINT UNSIGNED NULL,
  purchase_price_version_id BIGINT UNSIGNED NULL,
  capacity_pool_id BIGINT UNSIGNED NULL,
  initial_deployment_id BIGINT UNSIGNED NULL,
  routing_revision BIGINT NOT NULL DEFAULT 0,
  routing_reselect_count INT NOT NULL DEFAULT 0,
  route_reason VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_invocation_public(public_invocation_id),
  UNIQUE KEY uk_invocation_finance_operation(finance_operation_id),
  UNIQUE KEY uk_invocation_idempotency(agent_id,employee_id,idempotency_key),
  KEY idx_invocation_employee_time(employee_id,created_at,id),
  KEY idx_invocation_billing(billing_state,created_at,id),
  CONSTRAINT fk_invocation_finance FOREIGN KEY(finance_operation_id) REFERENCES finance_operation(id) ON DELETE RESTRICT,
  CONSTRAINT fk_invocation_agent FOREIGN KEY(agent_id) REFERENCES agent(id) ON DELETE RESTRICT,
  CONSTRAINT fk_invocation_employee FOREIGN KEY(employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_invocation_team FOREIGN KEY(employee_team_snapshot_id) REFERENCES team(id) ON DELETE RESTRICT,
  CONSTRAINT fk_invocation_model FOREIGN KEY(requested_model_id) REFERENCES logical_model(id) ON DELETE RESTRICT,
  CONSTRAINT fk_invocation_deployment FOREIGN KEY(final_deployment_id) REFERENCES model_deployment(id) ON DELETE RESTRICT,
  CONSTRAINT fk_invocation_initial_deployment FOREIGN KEY(initial_deployment_id) REFERENCES model_deployment(id) ON DELETE RESTRICT,
  CONSTRAINT fk_invocation_policy FOREIGN KEY(policy_revision_id) REFERENCES governance_policy_revision(id) ON DELETE RESTRICT,
  CONSTRAINT fk_invocation_points_price FOREIGN KEY(points_price_version_id) REFERENCES model_points_price_version(id) ON DELETE RESTRICT,
  CONSTRAINT fk_invocation_purchase_price FOREIGN KEY(purchase_price_version_id) REFERENCES deployment_purchase_price_version(id) ON DELETE RESTRICT,
  CONSTRAINT fk_invocation_capacity FOREIGN KEY(capacity_pool_id) REFERENCES capacity_pool_policy(id) ON DELETE RESTRICT,
  CONSTRAINT ck_invocation_class CHECK(service_class IN ('NORMAL','LOW','EMERGENCY','LOCAL_FALLBACK')),
  CONSTRAINT ck_invocation_exec CHECK(execution_state IN (
    'RECEIVED','WAITING','ACTIVE','UPSTREAM_UNKNOWN','STREAMING',
    'OUTPUT_COMPLETED','FAILED','CANCELLED'
  )),
  CONSTRAINT ck_invocation_billing CHECK(billing_state IN (
    'NONE','RESERVED','SETTLED','RELEASED','PENDING_RECONCILIATION','OVERRUN'
  )),
  CONSTRAINT ck_invocation_reselect CHECK(routing_revision>=0 AND routing_reselect_count>=0),
  CONSTRAINT ck_invocation_canonical_schema CHECK(canonical_schema_version BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dispatch_permit (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  invocation_id BIGINT UNSIGNED NOT NULL,
  attempt_token_digest BINARY(32) NOT NULL,
  deployment_id BIGINT UNSIGNED NOT NULL,
  lease_token_digest BINARY(32) NOT NULL,
  authorized_at DATETIME(6) NOT NULL,
  permission_revision BIGINT UNSIGNED NOT NULL,
  policy_revision_id BIGINT UNSIGNED NOT NULL,
  points_price_version_id BIGINT UNSIGNED NOT NULL,
  purchase_price_version_id BIGINT UNSIGNED NULL,
  upstream_delivery_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  permit_state VARCHAR(32) NOT NULL DEFAULT 'COMMITTED',
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_permit_invocation(invocation_id),
  UNIQUE KEY uk_permit_token_digest(attempt_token_digest),
  KEY idx_permit_state(permit_state,created_at),
  CONSTRAINT fk_permit_invocation FOREIGN KEY(invocation_id) REFERENCES invocation(id) ON DELETE RESTRICT,
  CONSTRAINT fk_permit_deployment FOREIGN KEY(deployment_id) REFERENCES model_deployment(id) ON DELETE RESTRICT,
  CONSTRAINT fk_permit_policy FOREIGN KEY(policy_revision_id) REFERENCES governance_policy_revision(id) ON DELETE RESTRICT,
  CONSTRAINT fk_permit_points_price FOREIGN KEY(points_price_version_id) REFERENCES model_points_price_version(id) ON DELETE RESTRICT,
  CONSTRAINT fk_permit_purchase_price FOREIGN KEY(purchase_price_version_id) REFERENCES deployment_purchase_price_version(id) ON DELETE RESTRICT,
  CONSTRAINT ck_permit_delivery CHECK(upstream_delivery_status IN ('UNKNOWN','REPORTED')),
  CONSTRAINT ck_permit_state CHECK(permit_state IN (
    'COMMITTED','PENDING_RECONCILIATION','SETTLED','RELEASED'
  ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE result_report_receipt (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  invocation_id BIGINT UNSIGNED NOT NULL,
  attempt_token_digest BINARY(32) NOT NULL,
  report_operation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  evidence_digest BINARY(32) NOT NULL,
  evidence_type VARCHAR(32) NOT NULL,
  processed_at DATETIME(6) NOT NULL,
  status VARCHAR(32) NOT NULL,
  settlement_revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY(id),
  UNIQUE KEY uk_report_operation(invocation_id,report_operation_id),
  KEY idx_report_invocation(invocation_id,processed_at),
  CONSTRAINT fk_result_invocation FOREIGN KEY(invocation_id) REFERENCES invocation(id) ON DELETE RESTRICT,
  CONSTRAINT ck_result_evidence CHECK(evidence_type IN (
    'TRUSTED_PROVIDER_USAGE','TRUSTED_LOCAL_USAGE','PROVEN_NOT_SENT','UNKNOWN'
  )),
  CONSTRAINT ck_result_status CHECK(status IN (
    'SETTLED','RELEASED','PENDING_RECONCILIATION','OVERRUN'
  ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reconciliation_case (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  invocation_id BIGINT UNSIGNED NOT NULL,
  risk_reason VARCHAR(64) NOT NULL,
  points_reserved DECIMAL(20,6) NOT NULL,
  currency CHAR(3) COLLATE utf8mb4_bin NULL,
  provider_risk_reserved DECIMAL(20,8) NULL,
  provider_request_id VARCHAR(128) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  evidence_ref VARCHAR(256) NULL,
  opened_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  resolved_by BIGINT UNSIGNED NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_reconciliation_invocation(invocation_id),
  KEY idx_reconciliation_state(status,opened_at),
  CONSTRAINT fk_reconciliation_invocation FOREIGN KEY(invocation_id) REFERENCES invocation(id) ON DELETE RESTRICT,
  CONSTRAINT fk_reconciliation_resolver FOREIGN KEY(resolved_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_reconciliation_state CHECK(status IN ('OPEN','REVIEWING','RESOLVED','ESCALATED')),
  CONSTRAINT ck_reconciliation_amount CHECK(points_reserved>=0 AND
    (provider_risk_reserved IS NULL OR provider_risk_reserved>=0)),
  CONSTRAINT ck_reconciliation_currency CHECK(
    (currency IS NULL AND provider_risk_reserved IS NULL)
    OR (currency IS NOT NULL AND provider_risk_reserved IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE governance_decision_audit
  ADD CONSTRAINT fk_decision_invocation
  FOREIGN KEY(invocation_id) REFERENCES invocation(id) ON DELETE RESTRICT;

ALTER TABLE model_deployment
  ADD KEY idx_deployment_capacity_pool(capacity_pool_id),
  ADD CONSTRAINT fk_deployment_capacity_pool
  FOREIGN KEY(capacity_pool_id) REFERENCES capacity_pool_policy(id) ON DELETE RESTRICT;
```

## 4. 业务不变量与测试门禁

- 新建表数、外键、唯一索引及CHECK必须匹配本基准；不得为避免迁移报错删约束。
- 先执行真实MySQL8.4/Flyway新库迁移，再执行上一版本真实历史升级，记录`schema_history`与checksum。
- 使用应用账户验证不能执行DDL；检查业务数据并发和外键拒绝情形。
- 仅本Phase上线后才可以访问对应管理API与受信内部RPC。
- 本规范定义结构；2026-10-28迁移基础测试通过，Runtime/Permit/Provider完整阶段验证仍未完成。

参照 [数据库目录](../05-database-design.md)、[所属模块](../phases/README.md)、[验收](../09-test-plan.md)。
