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
