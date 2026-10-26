# 仅由官方 MySQL 入口在空数据目录初始化时加载，用于隔离测试数据库。
# 1、将密码编码为十六进制，避免密码中的特殊字符改变 SQL 语法。
app_hex=$(printf '%s' "$AIGATE_DB_PASSWORD" | od -An -tx1 | tr -d ' \n')
migrator_hex=$(printf '%s' "$AIGATE_FLYWAY_PASSWORD" | od -An -tx1 | tr -d ' \n')
if [ -z "$app_hex" ] || [ -z "$migrator_hex" ]; then
  echo 'Database account passwords are required' >&2
  exit 1
fi

# 2、通过入口脚本的连接创建账号，使用 QUOTE 处理密码。
docker_process_sql <<EOSQL
SET @app_password=CONVERT(0x${app_hex} USING utf8mb4);
SET @migrator_password=CONVERT(0x${migrator_hex} USING utf8mb4);
SET @create_app=CONCAT('CREATE USER ''aigate_app''@''%'' IDENTIFIED BY ',QUOTE(@app_password));
PREPARE create_app FROM @create_app; EXECUTE create_app; DEALLOCATE PREPARE create_app;
SET @create_migrator=CONCAT('CREATE USER ''aigate_migrator''@''%'' IDENTIFIED BY ',QUOTE(@migrator_password));
PREPARE create_migrator FROM @create_migrator; EXECUTE create_migrator; DEALLOCATE PREPARE create_migrator;
-- 3、应用账号只授予数据读写权限，迁移账号另外授予 DDL 权限。
GRANT SELECT,INSERT,UPDATE,DELETE ON core_db.* TO 'aigate_app'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,REFERENCES,INDEX,ALTER ON core_db.* TO 'aigate_migrator'@'%';
EOSQL

# 4、删除父 shell 中的临时密码变量。
unset app_hex migrator_hex
