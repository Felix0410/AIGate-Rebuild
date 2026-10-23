# Core V06 · V6__governance.sql

> 实施时点：P6；本页为**设计SQL基准**，尚未经过MySQL8.4/Flyway实测。实际迁移文件由本地AI创建。已运行版本禁止原地编辑。

## 1. 目的与实施范围

扩V1角色CHECK并创建治理版本、Rule、审批与异常等7表。

## 2. 前置条件

必须先通过Core V5；旧表及已提交历史不得重建或删改。

## 3. 规范 SQL（7 张新增表，含所需旧表ALTER）

```sql
ALTER TABLE role_assignment
  DROP CHECK ck_role_kind,
  DROP CHECK ck_role_scope;

ALTER TABLE role_assignment
  ADD CONSTRAINT ck_role_kind CHECK(role_code IN (
    'PLATFORM_ADMIN','TEAM_MANAGER','FINOPS_READER',
    'POLICY_EDITOR','SECURITY_APPROVER'
  )),
  ADD CONSTRAINT ck_role_scope CHECK(
    (scope_type='GLOBAL' AND scope_id=0 AND role_code IN (
      'PLATFORM_ADMIN','FINOPS_READER','POLICY_EDITOR','SECURITY_APPROVER'))
    OR (scope_type='TEAM' AND scope_id>0 AND role_code IN (
      'TEAM_MANAGER','FINOPS_READER'))
  );

CREATE TABLE governance_policy (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  company_scope_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
  active_revision_id BIGINT UNSIGNED NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_gov_company(company_scope_id),
  CONSTRAINT ck_gov_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE governance_policy_revision (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  policy_id BIGINT UNSIGNED NOT NULL,
  revision_no INT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  version BIGINT NOT NULL DEFAULT 0,
  base_revision_id BIGINT UNSIGNED NULL,
  config_json JSON NOT NULL,
  content_digest BINARY(32) NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  approved_by BIGINT UNSIGNED NULL,
  published_by BIGINT UNSIGNED NULL,
  created_at DATETIME(6) NOT NULL,
  approved_at DATETIME(6) NULL,
  published_at DATETIME(6) NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_gov_revision(policy_id,revision_no),
  KEY idx_gov_rev_state(policy_id,status,id),
  CONSTRAINT fk_gov_revision_policy FOREIGN KEY(policy_id) REFERENCES governance_policy(id) ON DELETE RESTRICT,
  CONSTRAINT fk_gov_revision_base FOREIGN KEY(base_revision_id) REFERENCES governance_policy_revision(id) ON DELETE RESTRICT,
  CONSTRAINT fk_gov_revision_creator FOREIGN KEY(created_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_gov_revision_approver FOREIGN KEY(approved_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_gov_revision_publisher FOREIGN KEY(published_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_gov_revision_no CHECK(revision_no>0 AND version>=0),
  CONSTRAINT ck_gov_revision_state CHECK(status IN (
    'DRAFT','UNDER_REVIEW','APPROVED','PUBLISHED','SUPERSEDED','REJECTED'
  )),
  CONSTRAINT ck_gov_two_person CHECK(approved_by IS NULL OR approved_by<>created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE governance_rule (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  revision_id BIGINT UNSIGNED NOT NULL,
  rule_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  rule_kind VARCHAR(32) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  detected_class VARCHAR(16) NOT NULL,
  exemptible BOOLEAN NOT NULL DEFAULT FALSE,
  safe_parameter_json JSON NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  rule_order INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_rule_revision_code(revision_id,rule_code),
  KEY idx_rule_order(revision_id,rule_order,id),
  CONSTRAINT fk_rule_revision FOREIGN KEY(revision_id) REFERENCES governance_policy_revision(id) ON DELETE RESTRICT,
  CONSTRAINT ck_rule_kind CHECK(rule_kind IN ('CLASSIFY','SECRET_HARD_DENY','EGRESS_DENY','OUTPUT_REVIEW')),
  CONSTRAINT ck_rule_class CHECK(detected_class IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
  CONSTRAINT ck_rule_severity CHECK(severity IN ('INFO','WARN','DENY')),
  CONSTRAINT ck_rule_order CHECK(rule_order>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_egress_approval (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  agent_id BIGINT UNSIGNED NOT NULL,
  provider_id BIGINT UNSIGNED NOT NULL,
  region_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  revision INT NOT NULL,
  data_class_max VARCHAR(16) NOT NULL,
  valid_from DATETIME(6) NOT NULL,
  valid_until DATETIME(6) NOT NULL,
  status VARCHAR(16) NOT NULL,
  approved_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_egress_revision(agent_id,provider_id,region_code,revision),
  KEY idx_egress_authorized(agent_id,provider_id,region_code,status,valid_until),
  CONSTRAINT fk_egress_agent FOREIGN KEY(agent_id) REFERENCES agent(id) ON DELETE RESTRICT,
  CONSTRAINT fk_egress_provider FOREIGN KEY(provider_id) REFERENCES provider(id) ON DELETE RESTRICT,
  CONSTRAINT fk_egress_approver FOREIGN KEY(approved_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_egress_class CHECK(data_class_max IN ('PUBLIC','INTERNAL','CONFIDENTIAL')),
  CONSTRAINT ck_egress_state CHECK(status IN ('ACTIVE','REVOKED','EXPIRED')),
  CONSTRAINT ck_egress_valid CHECK(valid_until>valid_from AND revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE deployment_data_boundary (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  deployment_id BIGINT UNSIGNED NOT NULL,
  approved_class_max VARCHAR(16) NOT NULL,
  trusted_region VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  hosting_mode VARCHAR(24) NOT NULL,
  security_approval_status VARCHAR(16) NOT NULL,
  approval_valid_until DATETIME(6) NOT NULL,
  data_processing_approved BOOLEAN NOT NULL DEFAULT FALSE,
  approved_by BIGINT UNSIGNED NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_boundary_deployment(deployment_id),
  CONSTRAINT fk_boundary_deployment FOREIGN KEY(deployment_id) REFERENCES model_deployment(id) ON DELETE RESTRICT,
  CONSTRAINT fk_boundary_approver FOREIGN KEY(approved_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_boundary_class CHECK(approved_class_max IN ('PUBLIC','INTERNAL','CONFIDENTIAL')),
  CONSTRAINT ck_boundary_host CHECK(hosting_mode IN ('THIRD_PARTY_API','SELF_HOSTED')),
  CONSTRAINT ck_boundary_state CHECK(security_approval_status IN ('PENDING','APPROVED','REVOKED','EXPIRED')),
  CONSTRAINT ck_boundary_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE governance_exception_request (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  requester_employee_id BIGINT UNSIGNED NOT NULL,
  employee_id BIGINT UNSIGNED NOT NULL,
  agent_id BIGINT UNSIGNED NOT NULL,
  logical_model_id BIGINT UNSIGNED NOT NULL,
  rule_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  reason_code VARCHAR(64) NOT NULL,
  safe_reason_description VARCHAR(512) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  version BIGINT NOT NULL DEFAULT 0,
  reviewer_id BIGINT UNSIGNED NULL,
  policy_revision_id BIGINT UNSIGNED NOT NULL,
  valid_until DATETIME(6) NULL,
  idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  created_at DATETIME(6) NOT NULL,
  reviewed_at DATETIME(6) NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_exception_request(requester_employee_id,idempotency_key),
  KEY idx_exception_employee(employee_id,status,valid_until),
  CONSTRAINT fk_exception_requester FOREIGN KEY(requester_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_exception_employee FOREIGN KEY(employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_exception_agent FOREIGN KEY(agent_id) REFERENCES agent(id) ON DELETE RESTRICT,
  CONSTRAINT fk_exception_model FOREIGN KEY(logical_model_id) REFERENCES logical_model(id) ON DELETE RESTRICT,
  CONSTRAINT fk_exception_reviewer FOREIGN KEY(reviewer_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_exception_policy FOREIGN KEY(policy_revision_id) REFERENCES governance_policy_revision(id) ON DELETE RESTRICT,
  CONSTRAINT ck_exception_state CHECK(status IN ('PENDING','APPROVED','REJECTED','REVOKED','EXPIRED')),
  CONSTRAINT ck_exception_version CHECK(version>=0),
  CONSTRAINT ck_exception_two_person CHECK(reviewer_id IS NULL OR reviewer_id<>requester_employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE governance_decision_audit (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  trace_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  decision_phase VARCHAR(24) NOT NULL,
  decision_ordinal INT NOT NULL DEFAULT 1,
  policy_revision_id BIGINT UNSIGNED NOT NULL,
  invocation_id BIGINT UNSIGNED NULL,
  employee_id BIGINT UNSIGNED NOT NULL,
  agent_id BIGINT UNSIGNED NOT NULL,
  logical_model_id BIGINT UNSIGNED NOT NULL,
  deployment_id BIGINT UNSIGNED NULL,
  data_class VARCHAR(16) NOT NULL,
  decision VARCHAR(16) NOT NULL,
  matched_rule_codes_json JSON NOT NULL,
  input_hmac_digest BINARY(32) NOT NULL,
  scanner_version VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_gov_decision_phase(trace_id,decision_phase,policy_revision_id,decision_ordinal),
  KEY idx_gov_decision_actor(employee_id,agent_id,created_at),
  CONSTRAINT fk_decision_policy FOREIGN KEY(policy_revision_id) REFERENCES governance_policy_revision(id) ON DELETE RESTRICT,
  CONSTRAINT fk_decision_employee FOREIGN KEY(employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_decision_agent FOREIGN KEY(agent_id) REFERENCES agent(id) ON DELETE RESTRICT,
  CONSTRAINT fk_decision_model FOREIGN KEY(logical_model_id) REFERENCES logical_model(id) ON DELETE RESTRICT,
  CONSTRAINT fk_decision_deployment FOREIGN KEY(deployment_id) REFERENCES model_deployment(id) ON DELETE RESTRICT,
  CONSTRAINT ck_decision_phase CHECK(decision_phase IN ('PREQUEUE','PREDISPATCH','OUTPUT_REVIEW') AND decision_ordinal>0),
  CONSTRAINT ck_decision_class CHECK(data_class IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
  CONSTRAINT ck_decision_result CHECK(decision IN ('ALLOW','DENY','REVIEW_REQUIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE governance_policy
  ADD CONSTRAINT fk_governance_active_revision
    FOREIGN KEY(active_revision_id) REFERENCES governance_policy_revision(id)
    ON DELETE RESTRICT;
```

## 4. 业务不变量与测试门禁

- 新建表数、外键、唯一索引及CHECK必须匹配本基准；不得为避免迁移报错删约束。
- 先执行真实MySQL8.4/Flyway新库迁移，再执行上一版本真实历史升级，记录`schema_history`与checksum。
- 使用应用账户验证不能执行DDL；检查业务数据并发和外键拒绝情形。
- 仅本Phase上线后才可以访问对应管理API与受信内部RPC。
- 本规范只证明设计选择已明确，**真实测试状态NOT RUN**。

参照 [数据库目录](../05-database-design.md)、[所属模块](../phases/README.md)、[验收](../09-test-plan.md)。
