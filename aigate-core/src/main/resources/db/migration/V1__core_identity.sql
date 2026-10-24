CREATE TABLE team (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(80) COLLATE utf8mb4_0900_ai_ci NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_team_name (name),
  CONSTRAINT ck_team_status CHECK (status IN ('ACTIVE','DISABLED')),
  CONSTRAINT ck_team_version CHECK (version>=0),
  CONSTRAINT ck_team_name_nonempty CHECK (CHAR_LENGTH(TRIM(name))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employee (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  primary_team_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_employee_code (employee_code),
  KEY idx_employee_team_status_id (primary_team_id,status,id),
  CONSTRAINT fk_employee_team FOREIGN KEY (primary_team_id) REFERENCES team(id) ON DELETE RESTRICT,
  CONSTRAINT ck_employee_status CHECK (status IN ('ACTIVE','DISABLED')),
  CONSTRAINT ck_employee_version CHECK (version>=0),
  CONSTRAINT ck_employee_display_nonempty CHECK (CHAR_LENGTH(TRIM(display_name))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE role_assignment (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_id BIGINT UNSIGNED NOT NULL,
  role_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  scope_type VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
  scope_id BIGINT UNSIGNED NOT NULL,
  revoked_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_assignment (employee_id,role_code,scope_type,scope_id),
  KEY idx_role_scope_revoked (role_code,scope_type,scope_id,revoked_at),
  CONSTRAINT fk_role_employee FOREIGN KEY (employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_role_kind CHECK (role_code IN ('PLATFORM_ADMIN','TEAM_MANAGER','FINOPS_READER')),
  CONSTRAINT ck_role_scope CHECK (
    (scope_type='GLOBAL' AND scope_id=0 AND role_code IN ('PLATFORM_ADMIN','FINOPS_READER'))
    OR (scope_type='TEAM' AND scope_id>0 AND role_code IN ('TEAM_MANAGER','FINOPS_READER'))
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE management_credential (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_id BIGINT UNSIGNED NOT NULL,
  key_prefix VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
  secret_digest BINARY(32) NOT NULL,
  pepper_key_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6) NULL,
  issued_actor_type VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
  issued_actor_id BIGINT UNSIGNED NOT NULL,
  issue_idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_management_secret_digest (secret_digest),
  UNIQUE KEY uk_management_issue (issued_actor_type,issued_actor_id,issue_idempotency_key),
  KEY idx_management_employee_active (employee_id,revoked_at,expires_at),
  CONSTRAINT fk_management_employee FOREIGN KEY (employee_id) REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_management_actor CHECK (
    (issued_actor_type='BOOTSTRAP' AND issued_actor_id=0)
    OR (issued_actor_type='EMPLOYEE' AND issued_actor_id>0)
  ),
  CONSTRAINT ck_management_expiry CHECK (expires_at>created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_audit_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  actor_type VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
  actor_id BIGINT UNSIGNED NOT NULL,
  action VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  target_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  target_id BIGINT UNSIGNED NOT NULL,
  changed_fields_json JSON NOT NULL,
  trace_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_audit_time_id (occurred_at,id),
  KEY idx_audit_target (target_type,target_id,occurred_at),
  KEY idx_audit_actor (actor_type,actor_id,occurred_at),
  CONSTRAINT ck_audit_actor CHECK (
    (actor_type='BOOTSTRAP' AND actor_id=0)
    OR (actor_type='EMPLOYEE' AND actor_id>0)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bootstrap_control (
  id TINYINT UNSIGNED NOT NULL,
  initialized_at DATETIME(6) NULL,
  initialized_by_employee_id BIGINT UNSIGNED NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_bootstrap_employee FOREIGN KEY (initialized_by_employee_id)
    REFERENCES employee(id) ON DELETE RESTRICT,
  CONSTRAINT ck_bootstrap_id CHECK (id=1),
  CONSTRAINT ck_bootstrap_version CHECK (version>=0),
  CONSTRAINT ck_bootstrap_pair CHECK (
    (initialized_at IS NULL AND initialized_by_employee_id IS NULL)
    OR (initialized_at IS NOT NULL AND initialized_by_employee_id IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO bootstrap_control
(id,initialized_at,initialized_by_employee_id,version,created_at,updated_at)
VALUES (1,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
