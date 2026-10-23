# Core V05 · V5__team_grant.sql

> 实施时点：P5；本页为**设计SQL基准**，尚未经过MySQL8.4/Flyway实测。实际迁移文件由本地AI创建。已运行版本禁止原地编辑。

## 1. 目的与实施范围

TeamPool/Grant/发行幂等与审计理由；Grant到期、撤销不退已发Pool。

## 2. 前置条件

必须先通过Core V4；旧表及已提交历史不得重建或删改。

## 3. 规范 SQL（3 张新增表）

```sql
CREATE TABLE team_pool (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  team_id BIGINT UNSIGNED NOT NULL,
  period_start DATETIME(6) NOT NULL,
  period_end DATETIME(6) NOT NULL,
  limit_points DECIMAL(20,6) NOT NULL,
  issued_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  reserved_issue_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  version BIGINT NOT NULL DEFAULT 0,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_team_pool_period(team_id,period_start),
  KEY idx_team_pool_team_status(team_id,status,period_end),
  CONSTRAINT fk_team_pool_team FOREIGN KEY(team_id) REFERENCES team(id) ON DELETE RESTRICT,
  CONSTRAINT fk_team_pool_creator FOREIGN KEY(created_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_team_pool_period CHECK(period_end>period_start),
  CONSTRAINT ck_team_pool_state CHECK(status IN ('ACTIVE','FROZEN')),
  CONSTRAINT ck_team_pool_points CHECK(
    limit_points>=0 AND issued_points>=0 AND reserved_issue_points>=0
    AND issued_points+reserved_issue_points<=limit_points
  ),
  CONSTRAINT ck_team_pool_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_grant (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  issuer_team_id BIGINT UNSIGNED NOT NULL,
  recipient_employee_id BIGINT UNSIGNED NOT NULL,
  team_pool_id BIGINT UNSIGNED NOT NULL,
  grant_operation_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  issued_points DECIMAL(20,6) NOT NULL,
  used_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  reserved_points DECIMAL(20,6) NOT NULL DEFAULT 0,
  expires_at DATETIME(6) NOT NULL,
  issued_at DATETIME(6) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  issued_by BIGINT UNSIGNED NOT NULL,
  issued_reason_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  revoked_at DATETIME(6) NULL,
  revoked_by BIGINT UNSIGNED NULL,
  revoke_reason_code VARCHAR(64) COLLATE utf8mb4_bin NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_grant_operation(grant_operation_key),
  KEY idx_grant_recipient_fefo(recipient_employee_id,status,expires_at,id),
  KEY idx_grant_issuer(issuer_team_id,issued_at,id),
  CONSTRAINT fk_grant_issuer_team FOREIGN KEY(issuer_team_id) REFERENCES team(id) ON DELETE RESTRICT,
  CONSTRAINT fk_grant_recipient FOREIGN KEY(recipient_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_grant_pool FOREIGN KEY(team_pool_id) REFERENCES team_pool(id) ON DELETE RESTRICT,
  CONSTRAINT fk_grant_issuer FOREIGN KEY(issued_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_grant_revoker FOREIGN KEY(revoked_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_grant_time CHECK(expires_at>issued_at),
  CONSTRAINT ck_grant_state CHECK(status IN ('ACTIVE','EXPIRED','REVOKED_FOR_NEW_USE')),
  CONSTRAINT ck_grant_amount CHECK(
    issued_points>0 AND used_points>=0 AND reserved_points>=0
    AND used_points+reserved_points<=issued_points
  ),
  CONSTRAINT ck_grant_revoke_pair CHECK(
    (status='REVOKED_FOR_NEW_USE' AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revoke_reason_code IS NOT NULL)
    OR (status<>'REVOKED_FOR_NEW_USE' AND revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason_code IS NULL)
  ),
  CONSTRAINT ck_grant_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_grant_issue_receipt (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  issue_operation_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  team_pool_id BIGINT UNSIGNED NOT NULL,
  team_grant_id BIGINT UNSIGNED NOT NULL,
  actor_employee_id BIGINT UNSIGNED NOT NULL,
  recipient_employee_id BIGINT UNSIGNED NOT NULL,
  amount_points DECIMAL(20,6) NOT NULL,
  operation_digest BINARY(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_issue_receipt_key(issue_operation_key),
  UNIQUE KEY uk_issue_receipt_grant(team_grant_id),
  KEY idx_issue_actor(actor_employee_id,created_at),
  CONSTRAINT fk_issue_pool FOREIGN KEY(team_pool_id) REFERENCES team_pool(id) ON DELETE RESTRICT,
  CONSTRAINT fk_issue_grant FOREIGN KEY(team_grant_id) REFERENCES team_grant(id) ON DELETE RESTRICT,
  CONSTRAINT fk_issue_actor FOREIGN KEY(actor_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_issue_recipient FOREIGN KEY(recipient_employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_issue_positive CHECK(amount_points>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

## 4. 业务不变量与测试门禁

- 新建表数、外键、唯一索引及CHECK必须匹配本基准；不得为避免迁移报错删约束。
- 先执行真实MySQL8.4/Flyway新库迁移，再执行上一版本真实历史升级，记录`schema_history`与checksum。
- 使用应用账户验证不能执行DDL；检查业务数据并发和外键拒绝情形。
- 仅本Phase上线后才可以访问对应管理API与受信内部RPC。
- 本规范只证明设计选择已明确，**真实测试状态NOT RUN**。

参照 [数据库目录](../05-database-design.md)、[所属模块](../phases/README.md)、[验收](../09-test-plan.md)。
