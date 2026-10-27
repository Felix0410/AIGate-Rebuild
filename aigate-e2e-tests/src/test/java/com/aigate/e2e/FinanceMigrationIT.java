package com.aigate.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P4 V3到V4的历史升级；保留真实组织、运行Key、ACL与既有价格版本。
 *
 * @author AIGate
 */
class FinanceMigrationIT extends AbstractIdentityIntegrationTest {

    /**
     * 读取指定临时库的迁移校验和，升级后复核 V1～V3 历史没有被改写。
     *
     * @param url 测试容器内的独立 JDBC 地址
     * @return 已成功执行的版本及校验和
     */
    // 临时库在运行时创建，IDE 无法静态关联其 Flyway 历史表。
    @SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
    private Map<String, Integer> migrationChecksums(String url) throws SQLException {
        Map<String, Integer> checksums = new HashMap<>();
        try (var connection = DriverManager.getConnection(url, "root", rootPassword);
            var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT version,checksum FROM flyway_schema_history WHERE success=1")) {
            while (rows.next()) {
                checksums.put(rows.getString(1), rows.getInt(2));
            }
        }
        return checksums;
    }

    /**
     * 先运行真实 V3 并创建双身份，再升级到 V4，复核数据、凭证及迁移幂等性。
     */
    @Test
    @DisplayName("BIZP4-01：V3 历史库升级 V4，组织、Agent、双运行 Key 与 ACL 保持有效")
    // 只在当前测试容器内创建独立数据库，不修改开发者的 MySQL。
    @SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
    void upgradesRealV3DataWithoutChangingIdentityOrMigrationHistory() throws Exception {
        // 1、隔离旧版本库及权限，启动真实 V3 应用并建立可认证的员工、Agent 和授权。
        sql("CREATE DATABASE finance_upgrade_db");
        sql("GRANT SELECT,INSERT,UPDATE,DELETE ON finance_upgrade_db.* TO 'aigate_app'@'%'");
        sql("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,REFERENCES,INDEX,ALTER "
            + "ON finance_upgrade_db.* TO 'aigate_migrator'@'%'");
        String url = mysql.getJdbcUrl().replace("/core_db", "/finance_upgrade_db");
        stop(core);
        try {
            startCore("INIT", Map.of("AIGATE_DB_URL", url, "SPRING_FLYWAY_TARGET", "3"));
            Session admin = initialize();
            long employee = employee(admin, admin.teamId(), "FINANCE_UPGRADE");
            var agent = expect(call("POST", "/api/v1/agents", admin.key(), body(Map.of("applicationCode",
                "FINANCE_UPGRADE", "displayName", "Catalog Upgrade", "ownerTeamId", admin.teamId()))), 201);
            long agentId = agent.get("id").asLong();
            String expires = expiry();
            var agentCredential = expect(call("POST", "/api/v1/agents/" + agentId + "/keys", admin.key(),
                body(Map.of("expiresAt", expires)), "finance-upgrade-agent"), 201);
            var employeeCredential = expect(call("POST", "/api/v1/employees/" + employee + "/runtime-credentials",
                admin.key(), body(Map.of("expiresAt", expires)), "finance-upgrade-employee"), 201);
            String agentKey = agentCredential.get("key").asString();
            String employeeKey = employeeCredential.get("key").asString();
            secretValues.addAll(List.of(agentKey, employeeKey));
            expect(call("PUT", "/api/v1/agents/" + agentId + "/employees/" + employee, admin.key(), null), 201);
            try (var connection = DriverManager.getConnection(url, "root", rootPassword);
                var statement = connection.createStatement()) {
                statement.execute("INSERT INTO provider(code,name,hosting_mode,base_url_host_policy,created_at,"
                    + "updated_at) VALUES('HISTORY','History','THIRD_PARTY_API','{}',"
                    + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            }
            Map<String, Integer> previous = migrationChecksums(url);
            assertEquals(3, previous.size());

            // 2、去除 Flyway target 限制后升级到 V4，原管理身份、资源及双运行身份仍然有效。
            stop(core);
            startCore("OFF", Map.of("AIGATE_DB_URL", url, "SPRING_FLYWAY_TARGET", "4"));
            expect(call("GET", "/api/v1/me/profile", admin.key(), null), 200);
            assertEquals(agent, expect(call("GET", "/api/v1/agents/" + agentId, admin.key(), null), 200));
            var identity = expect(request(corePort, "POST", "/internal/v1/runtime-identities/verify", agentKey,
                "{}", Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken,
                    "X-Employee-Key", employeeKey)),
                200);
            assertEquals(employee, identity.get("employeeId").asLong());
            assertEquals(agentId, identity.get("agentId").asLong());
            assertEquals("HISTORY", expect(call("GET", "/api/v1/providers/1", admin.key(), null), 200)
                .get("code").asString());
            Map<String, Integer> upgraded = migrationChecksums(url);
            assertEquals(4, upgraded.size());
            previous.forEach((version, checksum) -> assertEquals(checksum, upgraded.get(version)));

            // 3、再次启动不重复执行迁移，既有成功版本的校验和保持完全一致。
            stop(core);
            startCore("OFF", Map.of("AIGATE_DB_URL", url, "SPRING_FLYWAY_TARGET", "4"));
            assertEquals(upgraded, migrationChecksums(url));
        } finally {
            stop(core);
            startCore("INIT", Map.of());
        }
    }

}
