package com.aigate.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * P2 真实 MySQL、Core 与 Gateway 回归，按规范案例验证双运行身份和显式授权。
 *
 * @author AIGate
 */
class RuntimeIdentityIT extends AbstractIdentityIntegrationTest {

    private record Fixture(Session admin, long agent, long employee, JsonNode agentKey, JsonNode employeeKey) {
    }

    private long createAgent(Session admin, String code, long teamId) throws Exception {
        return expect(call("POST", "/api/v1/agents", admin.key(),
            body(Map.of("applicationCode", code, "displayName", code, "ownerTeamId", teamId))), 201).get("id").asLong();
    }

    private String keys(boolean agentKey, long id) {
        return agentKey ? "/api/v1/agents/" + id + "/keys" : "/api/v1/employees/" + id + "/runtime-credentials";
    }

    private JsonNode issueRuntime(Session admin, boolean agentKey, long ownerId, Long parentId, String operationKey,
        String expiresAt) throws Exception {
        String path = keys(agentKey, ownerId) + (parentId == null ? "" : "/" + parentId + "/rotate");
        Reply response = call("POST", path, admin.key(), body(Map.of("expiresAt", expiresAt)), operationKey);
        assertTrue(response.headers().get("cache-control").contains("no-store"));
        JsonNode key = expect(response, 201);
        secretValues.add(key.get("key").asString());
        return key;
    }

    private Fixture fixture(boolean grant) throws Exception {
        Session admin = initialize();
        long agent = createAgent(admin, "APP_FINANCE", admin.teamId());
        long employee = employee(admin, admin.teamId(), "ALICE");
        JsonNode agentKey = issueRuntime(admin, true, agent, null, "initial-agent-key", expiry());
        JsonNode employeeKey = issueRuntime(admin, false, employee, null, "initial-employee-key", expiry());
        if (grant) {
            expect(call("PUT", permission(agent, employee), admin.key(), null), 201);
        }
        return new Fixture(admin, agent, employee, agentKey, employeeKey);
    }

    private String permission(long agent, long employee) {
        return "/api/v1/agents/" + agent + "/employees/" + employee;
    }

    private Reply verify(String agentKey, String employeeKey) throws Exception {
        return request(corePort, "POST", "/internal/v1/runtime-identities/verify", agentKey, "{}",
            Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken, "X-Employee-Key", employeeKey));
    }

    private Reply verify(Fixture fixture) throws Exception {
        return verify(fixture.agentKey().get("key").asString(), fixture.employeeKey().get("key").asString());
    }

    /**
     * P2-01：真实 V1 数据升级到 V2，旧管理凭证及组织数据保持有效。
     */
    @Test
    @DisplayName("P2-01：真实 V1 数据升级到 V2，旧管理凭证及组织数据保持有效")
    // upgrade_db 仅在临时容器内存在，IDE 无法静态关联该迁移历史表。
    @SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
    void upgradesExistingV1WithoutChangingIdentityData() throws Exception {
        // 1、在同一隔离容器创建旧版本库，迁移账号和应用账号仍分别限制权限。
        sql("CREATE DATABASE upgrade_db");
        sql("GRANT SELECT,INSERT,UPDATE,DELETE ON upgrade_db.* TO 'aigate_app'@'%'");
        sql("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,REFERENCES,INDEX,ALTER "
            + "ON upgrade_db.* TO 'aigate_migrator'@'%'");
        String url = mysql.getJdbcUrl().replace("/core_db", "/upgrade_db");
        stop(core);
        try {
            startCore("INIT", Map.of("AIGATE_DB_URL", url, "SPRING_FLYWAY_TARGET", "1"));
            Session legacyAdmin = initialize();
            long team = team(legacyAdmin, "Existing V1");

            // 2、升级到 V2，再用旧 mgt_ 读取原来的员工和团队。
            stop(core);
            startCore("OFF", Map.of("AIGATE_DB_URL", url));
            expect(call("GET", "/api/v1/me/profile", legacyAdmin.key(), null), 200);
            assertEquals("Existing V1", expect(call("GET", "/api/v1/teams/" + team, legacyAdmin.key(), null), 200)
                .get("name").asString());
            try (var connection = DriverManager.getConnection(url, "root", rootPassword);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getLong(1));
            }
        } finally {
            stop(core);
            startCore("INIT", Map.of());
        }
    }

    /**
     * P2-02、03：V2 唯一索引、外键、时间及状态 CHECK 在真实 MySQL 生效。
     */
    @Test
    @DisplayName("P2-02、03：V2 唯一索引、外键、时间及状态 CHECK 在真实 MySQL 生效")
    @SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
    void enforcesAllV2DatabaseConstraints() throws Exception {
        Fixture f = fixture(true);
        assertEquals(4, scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='core_db' "
            + "AND table_name IN ('agent','agent_api_key','employee_credential','employee_agent_permission')"));
        assertThrows(SQLException.class, () -> sql("INSERT INTO agent(application_code,display_name,owner_team_id,"
            + "created_by_employee_id,created_at,updated_at) SELECT application_code,display_name,owner_team_id,"
            + "created_by_employee_id,created_at,updated_at FROM agent LIMIT 1"));
        assertThrows(SQLException.class, () -> sql("UPDATE agent SET owner_team_id=999999 WHERE id=" + f.agent()));
        assertThrows(SQLException.class, () -> sql("UPDATE agent SET status='INVALID' WHERE id=" + f.agent()));
        assertThrows(SQLException.class, () -> sql("INSERT INTO employee_agent_permission(employee_id,agent_id,"
            + "granted_by_employee_id,granted_at,created_at,updated_at) SELECT employee_id,agent_id,"
            + "granted_by_employee_id,granted_at,created_at,updated_at FROM employee_agent_permission LIMIT 1"));
        for (String table : List.of("agent_api_key", "employee_credential")) {
            assertThrows(SQLException.class, () -> sql("UPDATE " + table + " SET expires_at=valid_from"));
            assertThrows(SQLException.class, () -> sql("UPDATE " + table + " SET superseded_at=valid_from"));
            assertThrows(SQLException.class, () -> sql("UPDATE " + table
                + " SET superseded_at=valid_from,grace_expires_at=expires_at+INTERVAL 1 SECOND"));
            String owner = table.equals("agent_api_key") ? "agent_id" : "employee_id";
            assertThrows(SQLException.class, () -> sql("INSERT INTO " + table + "(" + owner
                + ",key_prefix,secret_digest,pepper_key_id,valid_from,expires_at,issued_by_employee_id,"
                + "issue_idempotency_key,created_at) SELECT " + owner + ",key_prefix,secret_digest,pepper_key_id,"
                + "valid_from,expires_at,issued_by_employee_id,'another-operation',created_at FROM " + table
                + " LIMIT 1"));
        }
        assertThrows(SQLException.class, () -> sql("UPDATE employee_agent_permission SET status='REVOKED'"));
        expect(verify(f), 200);
    }

    /**
     * P2-04～08、39：Agent 严格 CRUD、不可变编码、父团队、CAS 和管理员权限。
     */
    @Test
    @DisplayName("P2-04～08、39：Agent 严格 CRUD、不可变编码、父团队、CAS 和管理员权限")
    void agentCrudIsStrictVersionedAndAdministratorOnly() throws Exception {
        Session admin = initialize();
        long id = createAgent(admin, "APP_FINANCE", admin.teamId());
        JsonNode agent = expect(call("GET", "/api/v1/agents/" + id, admin.key(), null), 200);
        assertEquals(0, agent.get("version").asLong());
        assertEquals(admin.teamId(), agent.get("ownerTeamId").asLong());
        for (String code : List.of("lower", " APP_FINANCE", "APP.FINANCE")) {
            expect(call("POST", "/api/v1/agents", admin.key(), body(Map.of("applicationCode", code,
                "displayName", "x", "ownerTeamId", admin.teamId()))), 400);
        }
        assertEquals("DUPLICATE_APPLICATION_CODE", expect(call("POST", "/api/v1/agents", admin.key(),
            body(Map.of("applicationCode", "APP_FINANCE", "displayName", "x", "ownerTeamId", admin.teamId()))), 409)
            .get("error").get("code").asString());
        expect(call("POST", "/api/v1/agents", admin.key(), body(Map.of("applicationCode", "NO_TEAM",
            "displayName", "x", "ownerTeamId", 99999))), 404);
        long inactive = team(admin, "Inactive");
        expect(call("PATCH", "/api/v1/teams/" + inactive, admin.key(),
            "{\"expectedVersion\":0,\"status\":\"DISABLED\"}"), 200);
        expect(call("POST", "/api/v1/agents", admin.key(), body(Map.of("applicationCode", "BAD_TEAM",
            "displayName", "x", "ownerTeamId", inactive))), 409);
        for (String invalid : List.of("{\"expectedVersion\":0}",
            "{\"expectedVersion\":0,\"applicationCode\":\"NEW_CODE\"}",
            "{\"expectedVersion\":0,\"status\":null}", "{\"expectedVersion\":\"0\",\"displayName\":\"x\"}")) {
            expect(call("PATCH", "/api/v1/agents/" + id, admin.key(), invalid), 400);
        }

        // 同一版本并发更新，只允许一个提交。
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> call("PATCH", "/api/v1/agents/" + id, admin.key(),
                "{\"expectedVersion\":0,\"displayName\":\"A\"}"));
            var b = pool.submit(() -> call("PATCH", "/api/v1/agents/" + id, admin.key(),
                "{\"expectedVersion\":0,\"displayName\":\"B\"}"));
            List<Integer> status = List.of(a.get().status(), b.get().status());
            assertTrue(status.contains(200));
            assertTrue(status.contains(409));
        }
        JsonNode page = expect(call("GET", "/api/v1/agents?ownerTeamId=" + admin.teamId() + "&status=ACTIVE&size=1",
            admin.key(), null), 200);
        assertEquals(1, page.get("total").asLong());
        long manager = employee(admin, admin.teamId(), "MANAGER");
        expect(call("PUT", role(manager, "TEAM_MANAGER", "TEAM", admin.teamId()), admin.key(), null), 201);
        String managerKey = issue(admin, manager, "manager-agent-test", expiry()).get("key").asString();
        for (String path : List.of("/api/v1/agents", "/api/v1/agents/" + id, keys(true, id),
            "/api/v1/agents/" + id + "/employees", keys(false, manager))) {
            expect(call("GET", path, managerKey, null), 403);
        }
        expect(call("PUT", permission(id, manager), managerKey, null), 403);
        expect(call("POST", keys(true, id), managerKey, body(Map.of("expiresAt", expiry())), "forbidden-key"), 403);

        // 管理角色和 Agent ACL 都不授予管理运行密钥的权限，普通员工也遵循相同规则。
        long ordinary = employee(admin, admin.teamId(), "ORDINARY");
        String ordinaryKey = issue(admin, ordinary, "ordinary-manager-check", expiry()).get("key").asString();
        for (String caller : List.of(managerKey, ordinaryKey)) {
            expect(call("POST", "/api/v1/agents", caller, body(Map.of("applicationCode", "FORBIDDEN_AGENT",
                "displayName", "x", "ownerTeamId", admin.teamId()))), 403);
            expect(call("PATCH", "/api/v1/agents/" + id, caller,
                "{\"expectedVersion\":1,\"displayName\":\"x\"}"), 403);
            expect(call("POST", keys(false, ordinary), caller, body(Map.of("expiresAt", expiry())),
                "forbidden-employee-key"), 403);
            expect(call("POST", keys(true, id) + "/1/rotate", caller, body(Map.of("expiresAt", expiry())),
                "forbidden-agent-rotate"), 403);
            expect(call("DELETE", keys(false, ordinary) + "/1", caller, null), 403);
            expect(call("DELETE", permission(id, ordinary), caller, null), 403);
            expect(call("GET", keys(false, admin.employeeId()), caller, null), 403);
        }
    }

    /**
     * P2-09～14、21：两类密钥只展示一次、跨 Owner 幂等冲突及并发最多两把。
     */
    @Test
    @DisplayName("P2-09～14、21：两类密钥只展示一次、跨 Owner 幂等冲突及并发最多两把")
    void keyIssuanceIsDurableOnceOnlyAndBounded() throws Exception {
        Fixture f = fixture(true);
        assertTrue(f.agentKey().get("key").asString().matches("agk_[A-Za-z0-9_-]{43}"));
        assertTrue(f.employeeKey().get("key").asString().matches("empk_[A-Za-z0-9_-]{43}"));
        for (boolean agentKey : List.of(true, false)) {
            long owner = agentKey ? f.agent() : f.employee();
            JsonNode first = agentKey ? f.agentKey() : f.employeeKey();
            String initialIdem = agentKey ? "initial-agent-key" : "initial-employee-key";
            String expires = first.get("expiresAt").asString();
            var replay = call("POST", keys(agentKey, owner), f.admin().key(), body(Map.of("expiresAt", expires)),
                initialIdem);
            assertEquals("KEY_ALREADY_ISSUED_NOT_REVEALABLE", expect(replay, 409).get("error").get("code").asString());
            assertFalse(replay.body().contains(first.get("key").asString()));
            long other = agentKey
                ? createAgent(f.admin(), "OTHER_AGENT", f.admin().teamId())
                : employee(f.admin(), f.admin().teamId(), "OTHER_EMPLOYEE");
            assertEquals("IDEMPOTENCY_CONFLICT", expect(call("POST", keys(agentKey, other), f.admin().key(),
                body(Map.of("expiresAt", expires)), initialIdem), 409).get("error").get("code").asString());

            // 并发使用同一幂等键，第二把凭证也只允许创建一次。
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var a = pool.submit(() -> call("POST", keys(agentKey, owner), f.admin().key(),
                    body(Map.of("expiresAt", expires)), "same-concurrent-key"));
                var b = pool.submit(() -> call("POST", keys(agentKey, owner), f.admin().key(),
                    body(Map.of("expiresAt", expires)), "same-concurrent-key"));
                List<Reply> replies = List.of(a.get(), b.get());
                assertEquals(1, replies.stream().filter(r -> r.status() == 201).count());
                assertEquals(1, replies.stream().filter(r -> r.status() == 409).count());
                for (Reply r : replies) {
                    if (r.status() == 201) {
                        secretValues.add(json.readTree(r.body()).get("key").asString());
                    }
                }
            }
            JsonNode metadata = expect(call("GET", keys(agentKey, owner), f.admin().key(), null), 200);
            assertEquals(2, metadata.get("total").asLong());
            assertFalse(metadata.toString().contains("secretDigest"));
            assertFalse(metadata.toString().contains(first.get("key").asString()));
            assertEquals("KEY_LIMIT_EXCEEDED", expect(call("POST", keys(agentKey, owner), f.admin().key(),
                body(Map.of("expiresAt", expires)), "third-key-limit"), 409).get("error").get("code").asString());
            for (String expiry : List.of(Instant.now().plusSeconds(3540).truncatedTo(ChronoUnit.MICROS).toString(),
                Instant.now().plusSeconds(91L * 86400).truncatedTo(ChronoUnit.MICROS).toString())) {
                expect(call("POST", keys(agentKey, other), f.admin().key(),
                    body(Map.of("expiresAt", expiry)), "expiry-invalid-key"), 400);
            }
            expect(call("POST", keys(agentKey, other), f.admin().key(),
                body(Map.of("expiresAt", expires, "validFrom", expires)), "unknown-field-key"), 400);
        }
        assertEquals(32, scalar("SELECT OCTET_LENGTH(secret_digest) FROM agent_api_key LIMIT 1"));
        assertEquals(32, scalar("SELECT OCTET_LENGTH(secret_digest) FROM employee_credential LIMIT 1"));
    }

    /**
     * P2-18～23：双用途轮换、父键、宽限期、重放与路径归属。
     */
    @Test
    @DisplayName("P2-18～23：双用途轮换、父键、宽限期、重放与路径归属")
    void rotationPreservesParentAndNeverRevealsAgain() throws Exception {
        Fixture f = fixture(true);
        for (boolean agentKey : List.of(true, false)) {
            JsonNode first = agentKey ? f.agentKey() : f.employeeKey();
            long owner = agentKey ? f.agent() : f.employee();
            long parent = first.get("credentialId").asLong();
            String expiry = expiry();
            JsonNode next = issueRuntime(f.admin(), agentKey, owner, parent, "rotate-once-key", expiry);
            String table = agentKey ? "agent_api_key" : "employee_credential";
            String parentColumn = agentKey ? "parent_key_id" : "parent_credential_id";
            assertEquals(parent, scalar("SELECT " + parentColumn + " FROM " + table
                + " WHERE id=" + next.get("credentialId").asLong()));
            Instant grace = Instant.parse(next.get("oldCredentialGraceExpiresAt").asString());
            assertFalse(grace.isAfter(Instant.parse(first.get("expiresAt").asString())));
            assertFalse(grace.isAfter(Instant.parse(next.get("validFrom").asString()).plusSeconds(86400)));
            expect(verify(f), 200);
            var replay = call("POST", keys(agentKey, owner) + "/" + parent + "/rotate", f.admin().key(),
                body(Map.of("expiresAt", expiry)), "rotate-once-key");
            assertEquals("KEY_ALREADY_ISSUED_NOT_REVEALABLE", expect(replay, 409).get("error").get("code").asString());
            assertFalse(replay.body().contains(next.get("key").asString()));
            long foreign = agentKey
                ? createAgent(f.admin(), "FOREIGN_AGENT", f.admin().teamId())
                : employee(f.admin(), f.admin().teamId(), "FOREIGN_EMPLOYEE");
            expect(call("POST", keys(agentKey, foreign) + "/" + parent + "/rotate", f.admin().key(),
                body(Map.of("expiresAt", expiry)), "wrong-owner-rotate"), 404);
            expect(call("POST", keys(agentKey, owner) + "/" + parent + "/rotate", f.admin().key(),
                body(Map.of("expiresAt", expiry)), "rotate-over-limit"), 409);
            expect(call("DELETE", keys(agentKey, owner) + "/" + parent, f.admin().key(), null), 204);
            expect(verify(f), 401);
            assertEquals("INVALID_STATE", expect(call("POST", keys(agentKey, owner) + "/" + parent + "/rotate",
                f.admin().key(), body(Map.of("expiresAt", expiry)), "rotate-revoked-parent"), 409)
                .get("error").get("code").asString());
            String agent = agentKey ? next.get("key").asString() : f.agentKey().get("key").asString();
            String employee = agentKey ? f.employeeKey().get("key").asString() : next.get("key").asString();
            if (agentKey) {
                expect(verify(agent, employee), 200);
                // 后续员工轮换用当前有效 Agent Key，避免使用已撤销的旧 Agent Key。
                f = new Fixture(f.admin(), f.agent(), f.employee(), next, f.employeeKey());
            } else {
                expect(verify(agent, employee), 200);
            }
        }
    }

    /**
     * P2-21：不同幂等键同时签发和轮换，同一 Owner 最多两把可认证 Key。
     */
    @Test
    @DisplayName("P2-21：不同幂等键同时签发和轮换，同一 Owner 最多两把可认证 Key")
    void concurrentIssueAndRotationNeverExceedOwnerLimit() throws Exception {
        Fixture f = fixture(true);
        for (boolean agentKey : List.of(true, false)) {
            long owner = agentKey ? f.agent() : f.employee();
            long parent = (agentKey ? f.agentKey() : f.employeeKey()).get("credentialId").asLong();
            String expires = expiry();

            // 1、已有一把凭证时，同时争抢剩余名额，分别使用三个不同的幂等键。
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var issue = pool.submit(() -> call("POST", keys(agentKey, owner), f.admin().key(),
                    body(Map.of("expiresAt", expires)), "concurrent-issue-first"));
                var rotate = pool.submit(() -> call("POST", keys(agentKey, owner) + "/" + parent + "/rotate",
                    f.admin().key(), body(Map.of("expiresAt", expires)), "concurrent-rotate-second"));
                var other = pool.submit(() -> call("POST", keys(agentKey, owner), f.admin().key(),
                    body(Map.of("expiresAt", expires)), "concurrent-issue-third"));
                List<Reply> replies = List.of(issue.get(), rotate.get(), other.get());
                assertEquals(1, replies.stream().filter(r -> r.status() == 201).count());
                for (Reply reply : replies) {
                    if (reply.status() == 201) {
                        secretValues.add(json.readTree(reply.body()).get("key").asString());
                    } else {
                        assertEquals("KEY_LIMIT_EXCEEDED", expect(reply, 409).get("error").get("code").asString());
                    }
                }
            }

            // 2、直接查询真实 MySQL，验证上限对应当前可认证条件，而不只是响应数量。
            String table = agentKey ? "agent_api_key" : "employee_credential";
            assertEquals(2, scalar("SELECT COUNT(*) FROM " + table
                + " WHERE valid_from<=UTC_TIMESTAMP(6) AND expires_at>UTC_TIMESTAMP(6) AND revoked_at IS NULL"
                + " AND (grace_expires_at IS NULL OR grace_expires_at>UTC_TIMESTAMP(6))"));
        }
        expect(verify(f), 200);
    }

    /**
     * P2-23、38：轮换已提交但成功响应丢失，Gateway 不重试，重放不再显示明文。
     */
    @Test
    @DisplayName("P2-23、38：轮换已提交但成功响应丢失，Gateway 不重试，重放不再显示明文")
    void committedRotationWithLostResponseCannotRevealOrIssueAgain() throws Exception {
        Fixture f = fixture(true);
        AtomicInteger committed = new AtomicInteger();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            proxy.setExecutor(executor);
            proxy.createContext("/", exchange -> {
                try {
                    if (exchange.getRequestMethod().equals("GET")) {
                        exchange.sendResponseHeaders(401, -1);
                        return;
                    }
                    var builder = HttpRequest.newBuilder(URI.create(coreUrl + exchange.getRequestURI()))
                        .timeout(Duration.ofSeconds(5));
                    for (String name : List.of("Authorization", "Content-Type", "Idempotency-Key",
                        "X-AIGate-Service-Authorization", "X-AIGate-Client-Address", "X-Request-Id")) {
                        String value = exchange.getRequestHeaders().getFirst(name);
                        if (value != null) {
                            builder.header(name, value);
                        }
                    }
                    var response = client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(
                        exchange.getRequestBody().readAllBytes())).build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 201) {
                        secretValues.add(json.readTree(response.body()).get("key").asString());
                        committed.incrementAndGet();
                    }

                    // Core 已成功提交，代理故意丢弃 201 正文，以 504 模拟客户端收不到新密钥。
                    exchange.sendResponseHeaders(504, -1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // 客户端取消时关闭连接，最终数据库断言仍决定测试是否成功。
                } finally {
                    exchange.close();
                }
            });
            proxy.start();
            try {
                for (boolean agentKey : List.of(true, false)) {
                    long owner = agentKey ? f.agent() : f.employee();
                    long parent = (agentKey ? f.agentKey() : f.employeeKey()).get("credentialId").asLong();
                    String path = keys(agentKey, owner) + "/" + parent + "/rotate";
                    String payload = body(Map.of("expiresAt", expiry()));

                    // 1、真实 Gateway 发起一次轮换，客户端只收到代理的失败响应。
                    stop(gateway);
                    startGateway("http://127.0.0.1:" + proxy.getAddress().getPort());
                    expect(call("POST", path, f.admin().key(), payload, "lost-rotation-response"), 504);

                    // 2、恢复正常代理后重放，请求只返回永久收据事实，不返回任何密钥。
                    stop(gateway);
                    startGateway(coreUrl);
                    Reply replay = call("POST", path, f.admin().key(), payload, "lost-rotation-response");
                    assertEquals("KEY_ALREADY_ISSUED_NOT_REVEALABLE",
                        expect(replay, 409).get("error").get("code").asString());
                    assertFalse(replay.body().contains("\"key\""));
                    assertEquals(2, expect(call("GET", keys(agentKey, owner), f.admin().key(), null), 200)
                        .get("total").asLong());
                }
                assertEquals(2, committed.get());
            } finally {
                proxy.stop(0);
            }
        }
    }

    /**
     * P2-17：真实 Core 进程缺失运行 Pepper 或复用管理材料时拒绝启动。
     */
    @Test
    @DisplayName("P2-17：真实 Core 进程缺失运行 Pepper 或复用管理材料时拒绝启动")
    void invalidRuntimePepperStopsRealCoreStartup() throws Exception {
        stop(core);
        for (Map<String, String> invalid : List.of(Map.of("AIGATE_AGENT_PEPPER", ""),
            Map.of("AIGATE_EMPLOYEE_PEPPER", pepper))) {
            Process rejected = process("aigate-core", coreEnvironment("OFF", invalid));
            try {
                assertTrue(rejected.waitFor(30, TimeUnit.SECONDS), "Invalid configuration must fail startup");
                assertTrue(rejected.exitValue() != 0);
            } finally {
                stop(rejected);
            }
        }
        startCore("INIT", Map.of());
    }

    /**
     * P2-15、16、25、26、29～33：私有双身份、错误前缀、缺失凭证及跨员工 ACL。
     */
    @Test
    @DisplayName("P2-15、16、25、26、29～33：私有双身份、错误前缀、缺失凭证及跨员工 ACL")
    void privateVerificationRequiresBothTypedKeysAndExplicitAcl() throws Exception {
        Fixture f = fixture(false);
        expect(verify(f), 403);
        String agentKey = f.agentKey().get("key").asString();
        String employeeKey = f.employeeKey().get("key").asString();
        expect(request(corePort, "POST", "/internal/v1/runtime-identities/verify", agentKey, "{}",
            Map.of("X-Employee-Key", employeeKey)), 401);
        expect(verify(employeeKey, agentKey), 401);
        for (String wrong : List.of(f.admin().key(), bootstrapToken, serviceToken)) {
            expect(verify(wrong, employeeKey), 401);
            expect(verify(agentKey, wrong), 401);
        }
        expect(request(corePort, "POST", "/internal/v1/runtime-identities/verify", agentKey, "{}",
            Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken)), 401);
        expect(request(corePort, "POST", "/internal/v1/runtime-identities/verify", agentKey,
            body(Map.of("employeeId", f.employee())), Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken,
                "X-Employee-Key", employeeKey)),
            400);
        expect(call("PUT", permission(f.agent(), f.employee()), f.admin().key(), null), 201);
        JsonNode result = expect(verify(f), 200);
        assertEquals(7, result.size());
        assertEquals(f.agent(), result.get("agentId").asLong());
        assertEquals(f.employee(), result.get("employeeId").asLong());
        assertEquals("APP_FINANCE", result.get("applicationCode").asString());
        long bob = employee(f.admin(), f.admin().teamId(), "BOB");
        JsonNode bobKey = issueRuntime(f.admin(), false, bob, null, "bob-runtime-key", expiry());
        expect(verify(agentKey, bobKey.get("key").asString()), 403);
        for (String path : List.of("/internal/v1/runtime-identities/verify", "/v1/auth/verify",
            "/v1/chat/completions")) {
            expect(call("POST", path, agentKey, "{}"), 404);
        }
    }

    /**
     * P2-24、27、28、36：ACL 并发幂等、撤销立即生效及原行恢复。
     */
    @Test
    @DisplayName("P2-24、27、28、36：ACL 并发幂等、撤销立即生效及原行恢复")
    void permissionsAreIdempotentVersionedAndImmediate() throws Exception {
        Fixture f = fixture(false);
        String path = permission(f.agent(), f.employee());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> call("PUT", path, f.admin().key(), null));
            var b = pool.submit(() -> call("PUT", path, f.admin().key(), null));
            List<Integer> statuses = List.of(a.get().status(), b.get().status());
            assertTrue(statuses.contains(201));
            assertTrue(statuses.contains(204));
        }
        expect(verify(f), 200);
        long audits = scalar("SELECT COUNT(*) FROM admin_audit_event");
        expect(call("PUT", path, f.admin().key(), null), 204);
        assertEquals(audits, scalar("SELECT COUNT(*) FROM admin_audit_event"));
        expect(call("DELETE", path, f.admin().key(), null), 204);
        expect(verify(f), 403);
        audits = scalar("SELECT COUNT(*) FROM admin_audit_event");
        expect(call("DELETE", path, f.admin().key(), null), 204);
        assertEquals(audits, scalar("SELECT COUNT(*) FROM admin_audit_event"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM employee_agent_permission"));
        expect(call("PUT", path, f.admin().key(), null), 201);
        assertEquals(2, expect(verify(f), 200).get("permissionVersion").asLong());
        JsonNode list = expect(call("GET", "/api/v1/agents/" + f.agent() + "/employees?status=GRANTED",
            f.admin().key(), null), 200);
        assertEquals(1, list.get("total").asLong());
        assertEquals(0, expect(call("GET", "/api/v1/agents/" + f.agent() + "/employees?status=REVOKED",
            f.admin().key(), null), 200).get("total").asLong());
    }

    /**
     * P2-14、20、34、35：主体及归属团队停用、恢复和运行凭证状态。
     */
    @Test
    @DisplayName("P2-14、20、34、35：主体及归属团队停用、恢复和运行凭证状态")
    void disabledOwnersAndTeamsFailClosedWithoutChangingAcl() throws Exception {
        Fixture f = fixture(true);
        String patchAgent = "/api/v1/agents/" + f.agent();
        expect(call("PATCH", patchAgent, f.admin().key(), "{\"expectedVersion\":0,\"status\":\"DISABLED\"}"), 200);
        expect(verify(f), 401);
        expect(
            call("POST", keys(true, f.agent()), f.admin().key(), body(Map.of("expiresAt", expiry())), "disabled-agent"),
            409);
        expect(call("PATCH", patchAgent, f.admin().key(), "{\"expectedVersion\":1,\"status\":\"ACTIVE\"}"), 200);
        expect(verify(f), 200);
        long team = team(f.admin(), "Agent Owner");
        expect(call("PATCH", patchAgent, f.admin().key(), body(Map.of("expectedVersion", 2, "ownerTeamId", team))),
            200);
        expect(verify(f), 200);
        expect(
            call("PATCH", "/api/v1/teams/" + team, f.admin().key(), "{\"expectedVersion\":0,\"status\":\"DISABLED\"}"),
            200);
        expect(verify(f), 401);
        assertEquals(1, scalar("SELECT COUNT(*) FROM employee_agent_permission WHERE status='GRANTED'"));
        expect(call("PATCH", "/api/v1/teams/" + team, f.admin().key(), "{\"expectedVersion\":1,\"status\":\"ACTIVE\"}"),
            200);
        // 员工转到 ACTIVE 主团队后保留原授权，新主团队被禁用时认证立即失效。
        long employeeTeam = team(f.admin(), "Employee Owner");
        expect(call("PATCH", "/api/v1/employees/" + f.employee(), f.admin().key(),
            body(Map.of("expectedVersion", 0, "primaryTeamId", employeeTeam))), 200);
        expect(verify(f), 200);
        assertEquals(1, scalar("SELECT COUNT(*) FROM employee_agent_permission WHERE status='GRANTED'"));
        sql("UPDATE team SET status='DISABLED' WHERE id=" + employeeTeam);
        expect(verify(f), 401);
        sql("UPDATE team SET status='ACTIVE' WHERE id=" + employeeTeam);
        expect(call("PATCH", "/api/v1/employees/" + f.employee(), f.admin().key(),
            "{\"expectedVersion\":1,\"status\":\"DISABLED\"}"), 200);
        expect(verify(f), 401);
        expect(call("POST", keys(false, f.employee()), f.admin().key(), body(Map.of("expiresAt", expiry())),
            "disabled-employee"), 409);
    }

    /**
     * P2-19、20、22、30：过期密钥不能验证或轮换，重新启用 Owner 不会复活已撤销密钥。
     */
    @Test
    @DisplayName("P2-19、20、22、30：过期密钥不能验证或轮换，重新启用 Owner 不会复活已撤销密钥")
    void expiredOrRevokedKeysStayInvalidAfterOwnerEnable() throws Exception {
        Fixture f = fixture(true);
        long agentKeyId = f.agentKey().get("credentialId").asLong();
        expect(call("DELETE", keys(true, f.agent()) + "/" + agentKeyId, f.admin().key(), null), 204);
        expect(call("PATCH", "/api/v1/agents/" + f.agent(), f.admin().key(),
            "{\"expectedVersion\":0,\"status\":\"DISABLED\"}"), 200);
        expect(call("PATCH", "/api/v1/agents/" + f.agent(), f.admin().key(),
            "{\"expectedVersion\":1,\"status\":\"ACTIVE\"}"), 200);
        expect(verify(f), 401);

        JsonNode replacement = issueRuntime(f.admin(), true, f.agent(), null, "replacement-agent-key", expiry());
        expect(verify(replacement.get("key").asString(), f.employeeKey().get("key").asString()), 200);
        for (boolean agentKey : List.of(true, false)) {
            String table = agentKey ? "agent_api_key" : "employee_credential";
            long id = (agentKey ? replacement : f.employeeKey()).get("credentialId").asLong();
            long owner = agentKey ? f.agent() : f.employee();
            // 修改隔离测试数据模拟时间已过去，HTTP 认证仍使用真实 Core 时钟及 MySQL 查询。
            sql("UPDATE " + table + " SET valid_from=UTC_TIMESTAMP(6)-INTERVAL 2 HOUR,"
                + "expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=" + id);
            expect(verify(replacement.get("key").asString(), f.employeeKey().get("key").asString()), 401);
            assertEquals("INVALID_STATE", expect(call("POST", keys(agentKey, owner) + "/" + id + "/rotate",
                f.admin().key(), body(Map.of("expiresAt", expiry())), "expired-parent-rotate"), 409)
                .get("error").get("code").asString());
            // 恢复该测试 Key，下一轮仅让另一种用途过期。
            sql("UPDATE " + table + " SET expires_at=UTC_TIMESTAMP(6)+INTERVAL 2 DAY WHERE id=" + id);
        }
    }

    /**
     * P2-37：审计失败回滚运行凭证吊销及 ACL 撤销。
     */
    @Test
    @DisplayName("P2-37：审计失败回滚运行凭证吊销及 ACL 撤销")
    void auditFailureRollsBackCredentialAndPermissionChanges() throws Exception {
        Fixture f = fixture(true);
        sql("CREATE TRIGGER reject_runtime_audit BEFORE INSERT ON admin_audit_event "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try {
            expect(call("DELETE", keys(true, f.agent()) + "/" + f.agentKey().get("credentialId").asLong(),
                f.admin().key(), null), 503);
            expect(verify(f), 200);
            expect(call("DELETE", permission(f.agent(), f.employee()), f.admin().key(), null), 503);
            expect(verify(f), 200);
        } finally {
            sql("DROP TRIGGER reject_runtime_audit");
        }
        expect(call("DELETE", keys(false, f.employee()) + "/" + f.employeeKey().get("credentialId").asLong(),
            f.admin().key(), null), 204);
        expect(verify(f), 401);
    }

    /**
     * P2-17：Agent 和员工 Pepper 各自轮换，过期旧版本不能继续认证。
     */
    @Test
    @DisplayName("P2-17：Agent 和员工 Pepper 各自轮换，过期旧版本不能继续认证")
    void runtimePeppersRotateIndependently() throws Exception {
        Fixture f = fixture(true);
        String nextAgent = Base64.getEncoder().encodeToString(randomBytes());
        String nextEmployee = Base64.getEncoder().encodeToString(randomBytes());
        secretValues.addAll(List.of(nextAgent, nextEmployee));
        Map<String, String> overrides = new HashMap<>(Map.of(
            "AIGATE_AGENT_PEPPER_KEY_ID", "agent-v2", "AIGATE_AGENT_PEPPER", nextAgent,
            "AIGATE_AGENT_PREVIOUS_PEPPER_KEY_ID", "agent-v1", "AIGATE_AGENT_PREVIOUS_PEPPER", agentPepper,
            "AIGATE_AGENT_PREVIOUS_VALID_UNTIL", Instant.now().plusSeconds(3600).toString(),
            "AIGATE_EMPLOYEE_PEPPER_KEY_ID", "employee-v2", "AIGATE_EMPLOYEE_PEPPER", nextEmployee,
            "AIGATE_EMPLOYEE_PREVIOUS_PEPPER_KEY_ID", "employee-v1", "AIGATE_EMPLOYEE_PREVIOUS_PEPPER", employeePepper,
            "AIGATE_EMPLOYEE_PREVIOUS_VALID_UNTIL", Instant.now().plusSeconds(3600).toString()));
        stop(core);
        startCore("OFF", overrides);
        expect(verify(f), 200);
        JsonNode newAgent = issueRuntime(f.admin(), true, f.agent(), null, "new-pepper-agent", expiry());
        JsonNode newEmployee = issueRuntime(f.admin(), false, f.employee(), null, "new-pepper-employee", expiry());
        overrides.put("AIGATE_AGENT_PREVIOUS_VALID_UNTIL", Instant.now().minusSeconds(1).toString());
        overrides.put("AIGATE_EMPLOYEE_PREVIOUS_VALID_UNTIL", Instant.now().minusSeconds(1).toString());
        stop(core);
        startCore("OFF", overrides);
        expect(verify(f), 401);
        expect(verify(newAgent.get("key").asString(), newEmployee.get("key").asString()), 200);
    }

    /**
     * P2-38：P2 管理接口真实经过 Gateway，Core 停止后有界失败。
     */
    @Test
    @DisplayName("P2-38：P2 管理接口真实经过 Gateway，Core 停止后有界失败")
    void gatewayFailsSafelyWhenCoreStops() throws Exception {
        Fixture f = fixture(true);
        stop(core);
        for (String path : List.of("/api/v1/agents", keys(true, f.agent()), keys(false, f.employee()),
            "/api/v1/agents/" + f.agent() + "/employees")) {
            int status = call("GET", path, f.admin().key(), null).status();
            assertTrue(status == 503 || status == 504);
        }
        int status = call("POST", keys(true, f.agent()), f.admin().key(), body(Map.of("expiresAt", expiry())),
            "outage-runtime-key").status();
        assertTrue(status == 503 || status == 504);
        startCore("INIT", Map.of());
        assertEquals(1, scalar("SELECT COUNT(*) FROM agent_api_key"));
        expect(verify(f), 200);
    }
}
