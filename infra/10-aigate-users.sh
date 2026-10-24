# Sourced by the official MySQL entrypoint on a fresh, isolated database only.
# Encode passwords before constructing SQL; do not interpolate password characters into SQL syntax.
app_hex=$(printf '%s' "$AIGATE_DB_PASSWORD" | od -An -tx1 | tr -d ' \n')
migrator_hex=$(printf '%s' "$AIGATE_FLYWAY_PASSWORD" | od -An -tx1 | tr -d ' \n')
if [ -z "$app_hex" ] || [ -z "$migrator_hex" ]; then
  echo 'Database account passwords are required' >&2
  exit 1
fi
docker_process_sql <<EOSQL
SET @app_password=CONVERT(0x${app_hex} USING utf8mb4);
SET @migrator_password=CONVERT(0x${migrator_hex} USING utf8mb4);
SET @create_app=CONCAT('CREATE USER ''aigate_app''@''%'' IDENTIFIED BY ',QUOTE(@app_password));
PREPARE create_app FROM @create_app; EXECUTE create_app; DEALLOCATE PREPARE create_app;
SET @create_migrator=CONCAT('CREATE USER ''aigate_migrator''@''%'' IDENTIFIED BY ',QUOTE(@migrator_password));
PREPARE create_migrator FROM @create_migrator; EXECUTE create_migrator; DEALLOCATE PREPARE create_migrator;
GRANT SELECT,INSERT,UPDATE,DELETE ON core_db.* TO 'aigate_app'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,REFERENCES,INDEX,ALTER ON core_db.* TO 'aigate_migrator'@'%';
EOSQL
unset app_hex migrator_hex
