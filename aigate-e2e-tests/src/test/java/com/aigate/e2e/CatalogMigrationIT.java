package com.aigate.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P3 Catalog 数据库迁移测试，仅验证 V3 结构和历史升级，不代表 Catalog 接口已经交付。
 *
 * @author AIGate
 */
class CatalogMigrationIT extends AbstractIdentityIntegrationTest {

    /**
     * 读取指定临时库的迁移校验和，升级后复核 V1、V2 历史没有被改写。
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
     * 先运行真实 V2 并创建双身份，再升级到 V3，复核数据、凭证及迁移幂等性。
     */
    @Test
    @DisplayName("P3-01：V2 历史库升级 V3，组织、Agent、双运行 Key 与 ACL 保持有效")
    // 只在当前测试容器内创建独立数据库，不修改开发者的 MySQL。
    @SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
    void upgradesRealV2DataWithoutChangingIdentityOrMigrationHistory() throws Exception {
        // 1、隔离旧版本库及权限，启动真实 V2 应用并建立可认证的员工、Agent 和授权。
        sql("CREATE DATABASE catalog_upgrade_db");
        sql("GRANT SELECT,INSERT,UPDATE,DELETE ON catalog_upgrade_db.* TO 'aigate_app'@'%'");
        sql("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,REFERENCES,INDEX,ALTER "
            + "ON catalog_upgrade_db.* TO 'aigate_migrator'@'%'");
        String url = mysql.getJdbcUrl().replace("/core_db", "/catalog_upgrade_db");
        stop(core);
        try {
            startCore("INIT", Map.of("AIGATE_DB_URL", url, "SPRING_FLYWAY_TARGET", "2"));
            Session admin = initialize();
            long employee = employee(admin, admin.teamId(), "CATALOG_UPGRADE");
            var agent = expect(call("POST", "/api/v1/agents", admin.key(), body(Map.of("applicationCode",
                "CATALOG_UPGRADE", "displayName", "Catalog Upgrade", "ownerTeamId", admin.teamId()))), 201);
            long agentId = agent.get("id").asLong();
            String expires = expiry();
            var agentCredential = expect(call("POST", "/api/v1/agents/" + agentId + "/keys", admin.key(),
                body(Map.of("expiresAt", expires)), "catalog-upgrade-agent"), 201);
            var employeeCredential = expect(call("POST", "/api/v1/employees/" + employee + "/runtime-credentials",
                admin.key(), body(Map.of("expiresAt", expires)), "catalog-upgrade-employee"), 201);
            String agentKey = agentCredential.get("key").asString();
            String employeeKey = employeeCredential.get("key").asString();
            secretValues.addAll(List.of(agentKey, employeeKey));
            expect(call("PUT", "/api/v1/agents/" + agentId + "/employees/" + employee, admin.key(), null), 201);
            Map<String, Integer> previous = migrationChecksums(url);
            assertEquals(2, previous.size());

            // 2、去除 Flyway target 限制后升级到 V3，原管理身份、资源及双运行身份仍然有效。
            stop(core);
            startCore("OFF", Map.of("AIGATE_DB_URL", url));
            expect(call("GET", "/api/v1/me/profile", admin.key(), null), 200);
            assertEquals(agent, expect(call("GET", "/api/v1/agents/" + agentId, admin.key(), null), 200));
            var identity = expect(request(corePort, "POST", "/internal/v1/runtime-identities/verify", agentKey,
                "{}", Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken,
                    "X-Employee-Key", employeeKey)),
                200);
            assertEquals(employee, identity.get("employeeId").asLong());
            assertEquals(agentId, identity.get("agentId").asLong());
            Map<String, Integer> upgraded = migrationChecksums(url);
            assertEquals(3, upgraded.size());
            previous.forEach((version, checksum) -> assertEquals(checksum, upgraded.get(version)));

            // 3、再次启动不重复执行迁移，既有成功版本的校验和保持完全一致。
            stop(core);
            startCore("OFF", Map.of("AIGATE_DB_URL", url));
            assertEquals(upgraded, migrationChecksums(url));
        } finally {
            stop(core);
            startCore("INIT", Map.of());
        }
    }

    /**
     * 在真实 MySQL 检查七表、索引、外键和 CHECK，并验证应用账号仍没有 DDL 权限。
     */
    @Test
    @DisplayName("P3-01～03：V3 规范 DDL、唯一键、外键、CHECK 及账号隔离真实生效")
    // 下列 SQL 故意使用缺失外键或非法状态，只用于隔离数据库的结构验证。
    @SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
    void enforcesCatalogSchemaConstraintsAndLeastPrivilege() throws Exception {
        // 1、实际迁移必须逐字来自规范 SQL，不放宽表、外键或 CHECK。
        String design = Files.readString(projectRoot.resolve("docs/database/core-v03.md"));
        String ddl = design.substring(design.indexOf("```sql\n") + 7);
        ddl = ddl.substring(0, ddl.indexOf("```")).strip();
        assertEquals(ddl, Files.readString(projectRoot.resolve(
            "aigate-core/src/main/resources/db/migration/V3__catalog.sql")).strip());
        for (String table : List.of("provider", "logical_model", "model_deployment", "model_points_price_version",
            "deployment_purchase_price_version", "provider_credential", "agent_model_permission")) {
            assertEquals(1, scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='core_db'"
                + " AND table_name='" + table + "'"));
        }

        // 2、仅为约束测试准备父记录；这些直接 SQL 数据不能被当作已审批的 Catalog 配置。
        Session admin = initialize();
        sql("INSERT INTO provider(code,name,hosting_mode,base_url_host_policy,created_at,updated_at) "
            + "VALUES('SCHEMA_PROVIDER','Schema','THIRD_PARTY_API','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        sql("INSERT INTO logical_model(model_code,display_name,model_family,immutable_model_revision,"
            + "capability_profile_json,created_at,updated_at) VALUES('SCHEMA_MODEL','Schema','test','v1',"
            + "'{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        long provider = scalar("SELECT id FROM provider");
        long model = scalar("SELECT id FROM logical_model");
        sql("INSERT INTO model_deployment(logical_model_id,provider_id,deployment_code,endpoint_uri,"
            + "remote_model_name,hosting_mode,region_code,capability_revision,adapter_compatibility_json,"
            + "adapter_evidence_ref,cost_model,max_concurrent,created_at,updated_at) VALUES("
            + model + "," + provider + ",'SCHEMA_DEPLOYMENT','https://catalog.invalid/v1/chat/completions',"
            + "'test','THIRD_PARTY_API','TEST','v1','{}','schema-test','PER_TOKEN_API',1,"
            + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        long deployment = scalar("SELECT id FROM model_deployment");

        // 3、数据库裁决重复标识、非法外键及状态，失败语句不得污染父记录。
        assertThrows(SQLException.class, () -> sql("INSERT INTO provider(code,name,hosting_mode,"
            + "base_url_host_policy,created_at,updated_at) SELECT code,name,hosting_mode,base_url_host_policy,"
            + "created_at,updated_at FROM provider"));
        assertThrows(SQLException.class, () -> sql("UPDATE model_deployment SET provider_id=999999"));
        assertThrows(SQLException.class, () -> sql("UPDATE model_deployment SET logical_model_id=999999"));
        assertThrows(SQLException.class, () -> sql("UPDATE provider SET hosting_mode='INVALID'"));
        assertThrows(SQLException.class, () -> sql("UPDATE logical_model SET status='DRAFT'"));
        assertThrows(SQLException.class, () -> sql("UPDATE model_deployment SET cost_model='INTERNAL_RESOURCE'"));
        assertThrows(SQLException.class, () -> sql("UPDATE model_deployment SET routing_weight=0"));
        assertThrows(SQLException.class, () -> sql("UPDATE model_deployment SET active_credential_id=999999"));
        assertThrows(SQLException.class, () -> sql("INSERT INTO model_points_price_version(logical_model_id,"
            + "revision,point_per_1k_input,point_per_1k_output,created_by,created_at) VALUES("
            + model + ",1,-1,1," + admin.employeeId() + ",UTC_TIMESTAMP(6))"));
        assertThrows(SQLException.class, () -> sql("INSERT INTO deployment_purchase_price_version(deployment_id,"
            + "revision,currency,amount_per_1k_input,amount_per_1k_output,status,created_by,created_at) VALUES("
            + deployment + ",1,'USD',1,1,'PUBLISHED'," + admin.employeeId() + ",UTC_TIMESTAMP(6))"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM provider"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM logical_model"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM model_deployment"));

        // 4、应用账号只能执行业务 DML，不能凭 V3 上线获得 DDL 权限。
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "aigate_app", appPassword);
            var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("CREATE TABLE forbidden_catalog_ddl(id INT)"));
        }
        assertFalse(migrationChecksums(mysql.getJdbcUrl()).isEmpty());
        assertTrue(migrationChecksums(mysql.getJdbcUrl()).containsKey("3"));
    }
}
