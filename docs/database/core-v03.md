# Core V03 · V3__catalog.sql

> 实施时点：P3；本页为**设计SQL基准**，尚未经过MySQL8.4/Flyway实测。实际迁移文件由本地AI创建。已运行版本禁止原地编辑。

## 1. 目的与实施范围

Provider/LogicalModel/Deployment、双价版本/供应商凭证及Agent-Model ACL；Adapter配置持久化V3 Deployment。

## 2. 前置条件

必须先通过Core V2；旧表及已提交历史不得重建或删改。

## 3. 规范 SQL（7 张新增表，含所需旧表ALTER）

```sql
CREATE TABLE provider (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  name VARCHAR(100) NOT NULL,
  hosting_mode VARCHAR(24) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  base_url_host_policy JSON NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_provider_code(code),
  CONSTRAINT ck_provider_mode CHECK(hosting_mode IN ('THIRD_PARTY_API','SELF_HOSTED')),
  CONSTRAINT ck_provider_status CHECK(status IN ('ACTIVE','DISABLED')),
  CONSTRAINT ck_provider_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE logical_model (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  model_code VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  model_family VARCHAR(100) NOT NULL,
  immutable_model_revision VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  capability_profile_json JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  active_points_price_version_id BIGINT UNSIGNED NULL,
  active_routing_revision_id BIGINT UNSIGNED NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_model_code(model_code),
  CONSTRAINT ck_model_status CHECK(status IN ('ACTIVE','DISABLED')),
  CONSTRAINT ck_model_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE model_deployment (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  logical_model_id BIGINT UNSIGNED NOT NULL,
  provider_id BIGINT UNSIGNED NOT NULL,
  deployment_code VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
  endpoint_uri VARCHAR(2048) NOT NULL,
  remote_model_name VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  hosting_mode VARCHAR(24) NOT NULL,
  region_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  capability_revision VARCHAR(96) NOT NULL,
  adapter_compatibility_json JSON NOT NULL,
  adapter_evidence_ref VARCHAR(256) COLLATE utf8mb4_bin NOT NULL,
  cost_model VARCHAR(24) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  routing_priority INT NOT NULL DEFAULT 100,
  routing_weight INT NOT NULL DEFAULT 1,
  max_concurrent INT NOT NULL,
  capacity_pool_id BIGINT UNSIGNED NULL,
  active_purchase_price_version_id BIGINT UNSIGNED NULL,
  active_credential_id BIGINT UNSIGNED NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_deployment_code(deployment_code),
  KEY idx_deployment_model_state (logical_model_id,status,id),
  KEY idx_deployment_provider (provider_id,status,id),
  CONSTRAINT fk_deployment_model FOREIGN KEY(logical_model_id) REFERENCES logical_model(id) ON DELETE RESTRICT,
  CONSTRAINT fk_deployment_provider FOREIGN KEY(provider_id) REFERENCES provider(id) ON DELETE RESTRICT,
  CONSTRAINT ck_deployment_host CHECK(hosting_mode IN ('THIRD_PARTY_API','SELF_HOSTED')),
  CONSTRAINT ck_deployment_cost CHECK(
    (hosting_mode='THIRD_PARTY_API' AND cost_model='PER_TOKEN_API')
    OR (hosting_mode='SELF_HOSTED' AND cost_model='INTERNAL_RESOURCE')
  ),
  CONSTRAINT ck_deployment_state CHECK(status IN ('DRAFT','ACTIVE','DISABLED','MAINTENANCE')),
  CONSTRAINT ck_deployment_routing CHECK(routing_priority>=0 AND routing_weight BETWEEN 1 AND 100 AND max_concurrent>0),
  CONSTRAINT ck_deployment_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE model_points_price_version (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  logical_model_id BIGINT UNSIGNED NOT NULL,
  revision INT NOT NULL,
  point_per_1k_input DECIMAL(20,6) NOT NULL,
  point_per_1k_output DECIMAL(20,6) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  effective_from DATETIME(6) NULL,
  published_at DATETIME(6) NULL,
  published_by BIGINT UNSIGNED NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_model_price_revision(logical_model_id,revision),
  KEY idx_model_price_status(logical_model_id,status,revision),
  CONSTRAINT fk_model_price_model FOREIGN KEY(logical_model_id) REFERENCES logical_model(id) ON DELETE RESTRICT,
  CONSTRAINT fk_model_price_creator FOREIGN KEY(created_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_model_price_publisher FOREIGN KEY(published_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_model_price_nonneg CHECK(point_per_1k_input>=0 AND point_per_1k_output>=0),
  CONSTRAINT ck_model_price_rev CHECK(revision>0),
  CONSTRAINT ck_model_price_status CHECK(status IN ('DRAFT','PUBLISHED')),
  CONSTRAINT ck_model_price_publish_pair CHECK(
    (status='DRAFT' AND effective_from IS NULL AND published_at IS NULL AND published_by IS NULL)
    OR (status='PUBLISHED' AND effective_from IS NOT NULL AND published_at IS NOT NULL AND published_by IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE deployment_purchase_price_version (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  deployment_id BIGINT UNSIGNED NOT NULL,
  revision INT NOT NULL,
  currency CHAR(3) COLLATE utf8mb4_bin NOT NULL,
  amount_per_1k_input DECIMAL(20,8) NOT NULL,
  amount_per_1k_output DECIMAL(20,8) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  effective_from DATETIME(6) NULL,
  published_at DATETIME(6) NULL,
  published_by BIGINT UNSIGNED NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_purchase_price_revision(deployment_id,revision),
  KEY idx_purchase_price_state(deployment_id,status,revision),
  CONSTRAINT fk_purchase_price_deployment FOREIGN KEY(deployment_id) REFERENCES model_deployment(id) ON DELETE RESTRICT,
  CONSTRAINT fk_purchase_price_creator FOREIGN KEY(created_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_purchase_price_publisher FOREIGN KEY(published_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_purchase_price_rev CHECK(revision>0),
  CONSTRAINT ck_purchase_price_nonneg CHECK(amount_per_1k_input>=0 AND amount_per_1k_output>=0),
  CONSTRAINT ck_purchase_currency CHECK(currency REGEXP '^[A-Z]{3}$'),
  CONSTRAINT ck_purchase_price_status CHECK(status IN ('DRAFT','PUBLISHED')),
  CONSTRAINT ck_purchase_price_publish_pair CHECK(
    (status='DRAFT' AND effective_from IS NULL AND published_at IS NULL AND published_by IS NULL)
    OR (status='PUBLISHED' AND effective_from IS NOT NULL AND published_at IS NOT NULL AND published_by IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE provider_credential (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  deployment_id BIGINT UNSIGNED NOT NULL,
  credential_kind VARCHAR(24) NOT NULL,
  encrypted_secret VARBINARY(8192) NULL,
  iv BINARY(12) NULL,
  encryption_key_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
  credential_revision INT NOT NULL,
  issued_by_employee_id BIGINT UNSIGNED NOT NULL,
  issue_idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  issue_request_hmac BINARY(32) NOT NULL,
  issue_hmac_key_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(6) NOT NULL,
  rotated_at DATETIME(6) NULL,
  revoked_at DATETIME(6) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_deployment_credential_revision(deployment_id,credential_revision),
  UNIQUE KEY uk_provider_credential_issue(issued_by_employee_id,issue_idempotency_key),
  KEY idx_credential_deployment_status(deployment_id,status,id),
  CONSTRAINT fk_provider_credential_deployment FOREIGN KEY(deployment_id) REFERENCES model_deployment(id) ON DELETE RESTRICT,
  CONSTRAINT fk_provider_credential_issuer FOREIGN KEY(issued_by_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_provider_credential_issue_key CHECK(CHAR_LENGTH(issue_idempotency_key) BETWEEN 8 AND 128),
  CONSTRAINT ck_provider_credential_kind CHECK(credential_kind IN ('BEARER','API_KEY_HEADER','NONE')),
  CONSTRAINT ck_provider_credential_status CHECK(status IN ('ACTIVE','REVOKED')),
  CONSTRAINT ck_provider_credential_revision CHECK(credential_revision>0),
  CONSTRAINT ck_provider_credential_fields CHECK(
    (credential_kind='NONE' AND encrypted_secret IS NULL AND iv IS NULL AND encryption_key_id IS NULL)
    OR (credential_kind<>'NONE' AND encrypted_secret IS NOT NULL AND iv IS NOT NULL AND encryption_key_id IS NOT NULL)
  ),
  CONSTRAINT ck_provider_credential_revocation CHECK(
    (status='ACTIVE' AND revoked_at IS NULL) OR (status='REVOKED' AND revoked_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_model_permission (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  agent_id BIGINT UNSIGNED NOT NULL,
  logical_model_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'GRANTED',
  version BIGINT NOT NULL DEFAULT 0,
  granted_by BIGINT UNSIGNED NOT NULL,
  revoked_by BIGINT UNSIGNED NULL,
  granted_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_agent_model_permission(agent_id,logical_model_id),
  KEY idx_agent_model_access(agent_id,status,logical_model_id),
  CONSTRAINT fk_agent_model_agent FOREIGN KEY(agent_id) REFERENCES agent(id) ON DELETE RESTRICT,
  CONSTRAINT fk_agent_model_model FOREIGN KEY(logical_model_id) REFERENCES logical_model(id) ON DELETE RESTRICT,
  CONSTRAINT fk_agent_model_granter FOREIGN KEY(granted_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_agent_model_revoker FOREIGN KEY(revoked_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_agent_model_status CHECK(status IN ('GRANTED','REVOKED')),
  CONSTRAINT ck_agent_model_revocation CHECK(
    (status='GRANTED' AND revoked_by IS NULL AND revoked_at IS NULL)
    OR (status='REVOKED' AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE logical_model
  ADD CONSTRAINT fk_model_active_points_price
    FOREIGN KEY(active_points_price_version_id)
    REFERENCES model_points_price_version(id) ON DELETE RESTRICT;
ALTER TABLE model_deployment
  ADD CONSTRAINT fk_deployment_active_purchase_price
    FOREIGN KEY(active_purchase_price_version_id)
    REFERENCES deployment_purchase_price_version(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_deployment_active_credential
    FOREIGN KEY(active_credential_id)
    REFERENCES provider_credential(id) ON DELETE RESTRICT;
```

## 4. 业务不变量与测试门禁

- 新建表数、外键、唯一索引及CHECK必须匹配本基准；不得为避免迁移报错删约束。
- 先执行真实MySQL8.4/Flyway新库迁移，再执行上一版本真实历史升级，记录`schema_history`与checksum。
- 使用应用账户验证不能执行DDL；检查业务数据并发和外键拒绝情形。
- 仅本Phase上线后才可以访问对应管理API与受信内部RPC。
- 本规范只证明设计选择已明确，**真实测试状态NOT RUN**。

参照 [数据库目录](../05-database-design.md)、[所属模块](../06-module-design.md)、[验收](../09-test-plan.md)。
