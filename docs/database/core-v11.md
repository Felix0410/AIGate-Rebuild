# Core V11 · V11__runtime_admission_control.sql

> 实施时点：P12；本页为**设计SQL基准**，尚未经过MySQL8.4/Flyway实测。实际迁移文件由本地AI创建。已运行版本禁止原地编辑。

## 1. 目的与实施范围

P12全局持久化准入单例，状态初始BLOCKED；兼容ALTER旧V1管理审计操作收据。

## 2. 前置条件

必须先通过Core V10；旧表及已提交历史不得重建或删改。

## 3. 规范 SQL（1 张新增表，含所需旧表ALTER）

```sql
-- Core V11__runtime_admission_control.sql DESIGN BASELINE
CREATE TABLE runtime_admission_control (
  id TINYINT UNSIGNED NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'ADMISSION_BLOCKED',
  epoch BIGINT UNSIGNED NOT NULL DEFAULT 0,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  changed_at DATETIME(6) NOT NULL,
  changed_by_employee_id BIGINT UNSIGNED NULL,
  reason_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  recovery_reference_id VARCHAR(96) COLLATE utf8mb4_bin NULL,
  last_readiness_digest BINARY(32) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  CONSTRAINT fk_admission_actor FOREIGN KEY(changed_by_employee_id)
    REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_admission_singleton CHECK(id=1),
  CONSTRAINT ck_admission_state CHECK(state IN
    ('ADMISSION_BLOCKED','RECOVERING','SERVING')),
  CONSTRAINT ck_admission_actor CHECK(
    state<>'SERVING' OR changed_by_employee_id IS NOT NULL
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO runtime_admission_control
(id,state,epoch,version,changed_at,changed_by_employee_id,
 reason_code,recovery_reference_id,last_readiness_digest,created_at,updated_at)
VALUES
(1,'ADMISSION_BLOCKED',0,0,UTC_TIMESTAMP(6),NULL,
 'INITIAL_P12_MIGRATION',NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

-- Compatible V11 alteration; existing V1 audit history remains valid
ALTER TABLE admin_audit_event
  ADD COLUMN operation_idempotency_key VARCHAR(128)
    COLLATE utf8mb4_bin NULL,
  ADD COLUMN operation_request_hmac BINARY(32) NULL,
  ADD COLUMN operation_hmac_key_id VARCHAR(32)
    COLLATE utf8mb4_bin NULL,
  ADD UNIQUE KEY uk_admin_operation_receipt
    (actor_type,actor_id,action,operation_idempotency_key),
  ADD CONSTRAINT ck_admin_operation_receipt_fields CHECK (
    (operation_idempotency_key IS NULL AND
     operation_request_hmac IS NULL AND operation_hmac_key_id IS NULL)
    OR
    (operation_idempotency_key IS NOT NULL AND
     operation_request_hmac IS NOT NULL AND operation_hmac_key_id IS NOT NULL)
  );
```

## 4. 业务不变量与测试门禁

- 新建表数、外键、唯一索引及CHECK必须匹配本基准；不得为避免迁移报错删约束。
- 先执行真实MySQL8.4/Flyway新库迁移，再执行上一版本真实历史升级，记录`schema_history`与checksum。
- 使用应用账户验证不能执行DDL；检查业务数据并发和外键拒绝情形。
- 仅本Phase上线后才可以访问对应管理API与受信内部RPC。
- 本规范只证明设计选择已明确，**真实测试状态NOT RUN**。

参照 [数据库目录](../05-database-design.md)、[所属模块](../phases/README.md)、[验收](../09-test-plan.md)。
