# Core V02 · V2__runtime_identity.sql

> 实施时点：P2；本页为规范SQL基准。Core V1～V3已在2026-10-26通过真实MySQL8.4/Flyway干净迁移、历史升级和重启验证，详见[Progress](../10-progress.md)。已运行迁移禁止原地编辑。

## 1. 目的与实施范围

Agent/AgentAPIKey/EmployeeCredential/Employee-Agent ACL；复用V1 Employee和Team。

## 2. 前置条件

必须先通过Core V1；旧表及已提交历史不得重建或删改。

## 3. 规范 SQL（4 张新增表）

```sql
-- 本代码块为增量 DDL，跨版本表定义见前置版本。
-- noinspection SqlResolveForFile
CREATE TABLE agent (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  application_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  owner_team_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  version BIGINT NOT NULL DEFAULT 0,
  created_by_employee_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_application_code (application_code),
  KEY idx_agent_team_status_id (owner_team_id,status,id),
  CONSTRAINT fk_agent_team FOREIGN KEY (owner_team_id) REFERENCES team(id) ON DELETE RESTRICT,
  CONSTRAINT fk_agent_creator FOREIGN KEY (created_by_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_agent_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_api_key (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  agent_id BIGINT UNSIGNED NOT NULL,
  key_prefix VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
  secret_digest BINARY(32) NOT NULL,
  pepper_key_id VARCHAR(32) NOT NULL,
  valid_from DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  superseded_at DATETIME(6) NULL,
  grace_expires_at DATETIME(6) NULL,
  revoked_at DATETIME(6) NULL,
  issued_by_employee_id BIGINT UNSIGNED NOT NULL,
  issue_idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  parent_key_id BIGINT UNSIGNED NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_key_digest (secret_digest),
  UNIQUE KEY uk_agent_key_issue (issued_by_employee_id,issue_idempotency_key),
  KEY idx_agent_key_owner (agent_id,revoked_at,expires_at,id),
  CONSTRAINT fk_agent_key_agent FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE RESTRICT,
  CONSTRAINT fk_agent_key_issuer FOREIGN KEY (issued_by_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_agent_key_parent FOREIGN KEY (parent_key_id) REFERENCES agent_api_key(id) ON DELETE RESTRICT,
  CONSTRAINT ck_agent_key_times CHECK (expires_at > valid_from),
  CONSTRAINT ck_agent_key_grace_pair CHECK (
    (superseded_at IS NULL AND grace_expires_at IS NULL)
    OR (superseded_at IS NOT NULL AND grace_expires_at IS NOT NULL
        AND grace_expires_at >= superseded_at AND grace_expires_at <= expires_at)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employee_credential (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_id BIGINT UNSIGNED NOT NULL,
  key_prefix VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
  secret_digest BINARY(32) NOT NULL,
  pepper_key_id VARCHAR(32) NOT NULL,
  valid_from DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  superseded_at DATETIME(6) NULL,
  grace_expires_at DATETIME(6) NULL,
  revoked_at DATETIME(6) NULL,
  issued_by_employee_id BIGINT UNSIGNED NOT NULL,
  issue_idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  parent_credential_id BIGINT UNSIGNED NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_employee_runtime_digest (secret_digest),
  UNIQUE KEY uk_employee_runtime_issue (issued_by_employee_id,issue_idempotency_key),
  KEY idx_employee_runtime_owner (employee_id,revoked_at,expires_at,id),
  CONSTRAINT fk_runtime_employee FOREIGN KEY (employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_runtime_issuer FOREIGN KEY (issued_by_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_runtime_parent FOREIGN KEY (parent_credential_id) REFERENCES employee_credential(id) ON DELETE RESTRICT,
  CONSTRAINT ck_runtime_times CHECK (expires_at > valid_from),
  CONSTRAINT ck_runtime_grace_pair CHECK (
    (superseded_at IS NULL AND grace_expires_at IS NULL)
    OR (superseded_at IS NOT NULL AND grace_expires_at IS NOT NULL
        AND grace_expires_at >= superseded_at AND grace_expires_at <= expires_at)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employee_agent_permission (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_id BIGINT UNSIGNED NOT NULL,
  agent_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'GRANTED',
  version BIGINT NOT NULL DEFAULT 0,
  granted_by_employee_id BIGINT UNSIGNED NOT NULL,
  granted_at DATETIME(6) NOT NULL,
  revoked_by_employee_id BIGINT UNSIGNED NULL,
  revoked_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_employee_agent_pair (employee_id,agent_id),
  KEY idx_perm_agent_status_employee (agent_id,status,employee_id),
  KEY idx_perm_employee_status_agent (employee_id,status,agent_id),
  CONSTRAINT fk_perm_employee FOREIGN KEY (employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_perm_agent FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE RESTRICT,
  CONSTRAINT fk_perm_granter FOREIGN KEY (granted_by_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_perm_revoker FOREIGN KEY (revoked_by_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_perm_status CHECK (status IN ('GRANTED','REVOKED')),
  CONSTRAINT ck_perm_revoke_pair CHECK (
    (status='GRANTED' AND revoked_at IS NULL AND revoked_by_employee_id IS NULL)
    OR (status='REVOKED' AND revoked_at IS NOT NULL AND revoked_by_employee_id IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

## 4. 业务不变量与测试门禁

- 新建表数、外键、唯一索引及CHECK必须匹配本基准；不得为避免迁移报错删约束。
- 先执行真实MySQL8.4/Flyway新库迁移，再执行上一版本真实历史升级，记录`schema_history`与checksum。
- 使用应用账户验证不能执行DDL；检查业务数据并发和外键拒绝情形。
- 仅本Phase上线后才可以访问对应管理API与受信内部RPC。
- 本规范的SQL结构已有本地真实验证；具体命令、覆盖范围及限制见Progress，未来版本仍按阶段单独实测。

参照 [数据库目录](../05-database-design.md)、[所属模块](../phases/README.md)、[验收](../09-test-plan.md)。
