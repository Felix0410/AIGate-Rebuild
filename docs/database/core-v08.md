# Core V08 · V8__routing_health.sql

> 实施时点：P8；本页为**设计SQL基准**，尚未经过MySQL8.4/Flyway实测。实际迁移文件由本地AI创建。已运行版本禁止原地编辑。

## 1. 目的与实施范围

RoutingPolicy和Health状态两表；补旧Deployment真实健康检查字段。

## 2. 前置条件

必须先通过Core V7；旧表及已提交历史不得重建或删改。

## 3. 规范 SQL（2 张新增表，含所需旧表ALTER）

```sql
-- 本代码块为增量 DDL，跨版本表定义见前置版本。
-- noinspection SqlResolveForFile
CREATE TABLE routing_policy_revision (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  logical_model_id BIGINT UNSIGNED NOT NULL,
  revision INT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  window_seconds INT NOT NULL DEFAULT 60,
  min_samples INT NOT NULL DEFAULT 20,
  degraded_rate DECIMAL(4,3) NOT NULL DEFAULT 0.200,
  open_rate DECIMAL(4,3) NOT NULL DEFAULT 0.500,
  consecutive_failure_threshold INT NOT NULL DEFAULT 3,
  cooldown_seconds INT NOT NULL DEFAULT 30,
  health_stale_seconds INT NOT NULL DEFAULT 120,
  probe_max_active INT NOT NULL DEFAULT 1,
  load_tie_tolerance DECIMAL(4,3) NOT NULL DEFAULT 0.100,
  max_pre_dispatch_reselections INT NOT NULL DEFAULT 1,
  config_digest BINARY(32) NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  published_by BIGINT UNSIGNED NULL,
  created_at DATETIME(6) NOT NULL,
  published_at DATETIME(6) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_routing_model_revision(logical_model_id,revision),
  KEY idx_routing_model_state(logical_model_id,status,revision),
  CONSTRAINT fk_route_policy_model FOREIGN KEY(logical_model_id) REFERENCES logical_model(id) ON DELETE RESTRICT,
  CONSTRAINT fk_route_policy_creator FOREIGN KEY(created_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT fk_route_policy_publisher FOREIGN KEY(published_by) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_route_state CHECK(status IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT ck_route_positive CHECK(revision>0 AND window_seconds>0 AND
    min_samples>0 AND consecutive_failure_threshold>0 AND
    cooldown_seconds>0 AND health_stale_seconds>0 AND probe_max_active>0
    AND max_pre_dispatch_reselections>=0),
  CONSTRAINT ck_route_rate CHECK(degraded_rate BETWEEN 0 AND 1 AND
    open_rate BETWEEN 0 AND 1 AND degraded_rate<open_rate
    AND load_tie_tolerance BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE deployment_health_observation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  deployment_id BIGINT UNSIGNED NOT NULL,
  observed_at DATETIME(6) NOT NULL,
  window_start DATETIME(6) NOT NULL,
  window_end DATETIME(6) NOT NULL,
  sample_count INT NOT NULL,
  failure_count INT NOT NULL,
  consecutive_failure_count INT NOT NULL,
  ttft_p95_ms BIGINT UNSIGNED NULL,
  observed_state VARCHAR(24) NOT NULL,
  source_kind VARCHAR(24) NOT NULL,
  observation_revision BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_health_observation_revision(deployment_id,observation_revision),
  KEY idx_health_observed(deployment_id,observed_at),
  CONSTRAINT fk_health_deployment FOREIGN KEY(deployment_id) REFERENCES model_deployment(id) ON DELETE RESTRICT,
  CONSTRAINT ck_health_window CHECK(window_end>=window_start),
  CONSTRAINT ck_health_samples CHECK(sample_count>=0 AND failure_count>=0 AND
    failure_count<=sample_count AND consecutive_failure_count>=0),
  CONSTRAINT ck_health_state CHECK(observed_state IN (
    'HEALTHY','DEGRADED','UNHEALTHY','UNKNOWN','THROTTLED','CONFIG_ERROR'
  )),
  CONSTRAINT ck_health_source CHECK(source_kind IN ('PASSIVE','NONBILLABLE_ACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE model_deployment
  ADD COLUMN health_check_mode VARCHAR(24) NOT NULL DEFAULT 'DISABLED',
  ADD COLUMN approved_health_endpoint VARCHAR(2048) NULL,
  ADD CONSTRAINT ck_deployment_health_mode CHECK(
    health_check_mode IN ('DISABLED','APPROVED_NONBILLABLE')
  );

ALTER TABLE logical_model
  ADD CONSTRAINT fk_model_active_routing_revision
  FOREIGN KEY(active_routing_revision_id) REFERENCES routing_policy_revision(id)
  ON DELETE RESTRICT;
```

## 4. 业务不变量与测试门禁

- 新建表数、外键、唯一索引及CHECK必须匹配本基准；不得为避免迁移报错删约束。
- 先执行真实MySQL8.4/Flyway新库迁移，再执行上一版本真实历史升级，记录`schema_history`与checksum。
- 使用应用账户验证不能执行DDL；检查业务数据并发和外键拒绝情形。
- 仅本Phase上线后才可以访问对应管理API与受信内部RPC。
- 本规范只证明设计选择已明确，**真实测试状态NOT RUN**。

参照 [数据库目录](../05-database-design.md)、[所属模块](../phases/README.md)、[验收](../09-test-plan.md)。
