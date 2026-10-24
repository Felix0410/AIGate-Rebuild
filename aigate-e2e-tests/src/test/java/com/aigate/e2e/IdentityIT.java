package com.aigate.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.aigate.contracts.P1Routes;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * P1 业务端到端回归场景，重点断言权限、并发、事务回滚和密钥不可重放，不在测试中模拟核心授权结论。
 *
 * @author AIGate
 */
class IdentityIT extends AbstractIdentityIntegrationTest {

    /**
     * 两个不同请求并发初始化，必须只有一个成功，重启后仍不能再次初始化。
     */
    @Test
    @DisplayName("两个不同请求并发初始化，必须只有一个成功，重启后仍不能再次初始化。")
    void concurrentBootstrapAndDurableOnceOnly() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstInitialization = pool.submit(() -> call("POST", "/api/v1/bootstrap/initialize", bootstrapToken,
                bootstrapBody("Platform-A", "ADM_A001"), "bootstrap-race-a"));
            var secondInitialization = pool.submit(() -> call("POST", "/api/v1/bootstrap/initialize", bootstrapToken,
                bootstrapBody("Platform-B", "ADM_B001"), "bootstrap-race-b"));
            var replies = List.of(firstInitialization.get(), secondInitialization.get());
            assertEquals(1, replies.stream().filter(r -> r.status() == 201).count());
            assertEquals(1, replies.stream().filter(r -> r.status() == 409).count());
            for (Reply reply : replies) {
                if (reply.status() == 201) {
                    secretValues.add(json.readTree(reply.body()).get("managementCredential").get("key").asString());
                }
            }
        }
        for (String table : List.of("team", "employee", "role_assignment", "management_credential",
            "admin_audit_event")) {
            assertEquals(1, scalar("SELECT COUNT(*) FROM " + table));
        }
        assertEquals(1, scalar(
            "SELECT COUNT(*) FROM bootstrap_control WHERE "
                + "initialized_by_employee_id=1 AND initialized_at IS NOT NULL"));
        expect(call("POST", "/api/v1/bootstrap/initialize", bootstrapToken, bootstrapBody("Other", "ADM_OTHER"),
            "bootstrap-new-key"), 409);
        stop(core);
        startCore("INIT", Map.of());
        expect(call("POST", "/api/v1/bootstrap/initialize", bootstrapToken, bootstrapBody("Other", "ADM_OTHER"),
            "bootstrap-restart"), 409);
        assertEquals(1, scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1"));
    }

    /**
     * 在审计写入前注入数据库故障，验证团队、员工、角色、凭证与单例整体回滚。
     */
    @Test
    @DisplayName("在审计写入前注入数据库故障，验证团队、员工、角色、凭证与单例整体回滚。")
    void lateSqlFailureRollsBackTheEntireBootstrap() throws Exception {
        sql("CREATE TRIGGER reject_p1_audit BEFORE INSERT ON admin_audit_event "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try {
            var failure = call("POST", "/api/v1/bootstrap/initialize", bootstrapToken,
                bootstrapBody("Rollback", "ADM_ROLLBACK"), "bootstrap-failure");
            expect(failure, 503);
            assertFalse(failure.body().contains("mgt_"));
            for (String table : List.of("team", "employee", "role_assignment", "management_credential",
                "admin_audit_event")) {
                assertEquals(0, scalar("SELECT COUNT(*) FROM " + table));
            }
            assertEquals(1, scalar("SELECT COUNT(*) FROM bootstrap_control WHERE initialized_at IS NULL"));
        } finally {
            sql("DROP TRIGGER reject_p1_audit");
        }
        initialize();
    }

    /**
     * 通过真实 HTTP 验证严格输入、唯一约束、员工状态及并发版本冲突。
     */
    @Test
    @DisplayName("通过真实 HTTP 验证严格输入、唯一约束、员工状态及并发版本冲突。")
    void strictCrudCasAndDatabaseConstraints() throws Exception {
        Session admin = initialize();
        long id = team(admin, "Engineering");
        expect(call("POST", "/api/v1/teams", admin.key(), "{\"name\":\"engineering\"}"), 409);
        for (String invalid : List.of("{}", "[]", "{\"name\":null}", "{\"name\":123}", "{\"name\":\" \"}",
            "{\"name\":\"x\",\"extra\":true}", "{\"name\":\"x\",\"name\":\"y\"}", "{\"name\":\"x\"} {}")) {
            expect(call("POST", "/api/v1/teams", admin.key(), invalid), 400);
        }
        expect(call("POST", "/api/v1/teams", admin.key(), body(Map.of("name", "x".repeat(81)))), 400);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstUpdate = pool.submit(
                () -> call("PATCH", "/api/v1/teams/" + id, admin.key(), "{\"expectedVersion\":0,\"name\":\"A\"}"));
            var secondUpdate = pool.submit(
                () -> call("PATCH", "/api/v1/teams/" + id, admin.key(), "{\"expectedVersion\":0,\"name\":\"B\"}"));
            var statuses = List.of(firstUpdate.get().status(), secondUpdate.get().status());
            assertTrue(statuses.contains(200));
            assertTrue(statuses.contains(409));
        }
        assertEquals(1, expect(call("GET", "/api/v1/teams/" + id, admin.key(), null), 200).get("version").asLong());
        long employee = employee(admin, id, "ENG_001");
        expect(call("POST", "/api/v1/employees", admin.key(),
            body(Map.of("employeeCode", "eng_001", "displayName", "x", "primaryTeamId", id))), 400);
        expect(call("POST", "/api/v1/employees", admin.key(),
            body(Map.of("employeeCode", "ENG_001", "displayName", "x", "primaryTeamId", id))), 409);
        expect(call("PATCH", "/api/v1/employees/" + employee, admin.key(), "{\"expectedVersion\":0,\"status\":null}"),
            400);
        expect(call("PATCH", "/api/v1/employees/" + employee, admin.key(),
            "{\"expectedVersion\":0,\"primaryTeamId\":1e0}"), 400);
        expect(call("PATCH", "/api/v1/teams/" + id, admin.key(), "{\"expectedVersion\":1,\"status\":\"DISABLED\"}"),
            409);
        expect(call("GET", "/api/v1/employees/1e3", admin.key(), null), 400);
        expect(call("GET", "/api/v1/teams?page=1&page=2", admin.key(), null), 400);
        expect(call("GET", "/api/v1/teams?size=101", admin.key(), null), 400);
        expect(call("GET", "/api/v1/teams?unknown=x", admin.key(), null), 400);
        expect(call("PUT", role(employee, "TEAM_MANAGER", "GLOBAL", 0), admin.key(), null), 400);
        expect(call("PUT", role(employee, "TEAM_MANAGER", "TEAM", id), admin.key(), "{}"), 400);
        expect(call("PATCH", "/api/v1/employees/" + employee, admin.key(),
            "{\"expectedVersion\":0,\"displayName\":\"Updated\"}"), 200);
        expect(call("PATCH", "/api/v1/employees/" + employee, admin.key(),
            "{\"expectedVersion\":0,\"displayName\":\"Stale\"}"), 409);
    }

    /**
     * 验证正文长度已知和分块传输两种路径均不能绕过请求体大小限制。
     */
    @Test
    @DisplayName("验证正文长度已知和分块传输两种路径均不能绕过请求体大小限制。")
    void requestSizeLimitsAlsoCoverChunkedInput() throws Exception {
        Session admin = initialize();
        String payload = body(Map.of("name", "x".repeat(70000)));
        expect(call("POST", "/api/v1/teams", admin.key(), payload), 400);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/api/v1/teams"))
            .header("Authorization", "Bearer " + admin.key()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers
                .ofInputStream(() -> new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8))))
            .build();
        assertEquals(400, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(1, scalar("SELECT COUNT(*) FROM team"));
    }

    /**
     * 验证 Manager 的列表与 COUNT 都先过滤团队，且不能写入或伪造管理员身份。
     */
    @Test
    @DisplayName("验证 Manager 的列表与 COUNT 都先过滤团队，且不能写入或伪造管理员身份。")
    void managerScopeIsFilteredBeforeCountAndCannotWrite() throws Exception {
        Session admin = initialize();
        long teamAId = team(admin, "Team A");
        long teamBId = team(admin, "Team B");
        long managerId = employee(admin, teamAId, "MGR_001");
        long aliceId = employee(admin, teamAId, "ALICE_001");
        long bobId = employee(admin, teamBId, "BOB_001");
        expect(call("PUT", role(managerId, "TEAM_MANAGER", "TEAM", teamAId), admin.key(), null), 201);
        String key = issue(admin, managerId, "manager-key-0001", expiry()).get("key").asString();
        expect(call("GET", "/api/v1/employees/" + aliceId, key, null), 200);
        expect(call("GET", "/api/v1/employees/" + bobId, key, null), 403);
        var page = expect(call("GET", "/api/v1/employees?size=1", key, null), 200);
        assertEquals(2, page.get("total").asLong());
        assertEquals(1, page.get("items").size());
        var teams = expect(call("GET", "/api/v1/teams", key, null), 200);
        assertEquals(1, teams.get("total").asLong());
        assertEquals(teamAId, teams.get("items").get(0).get("id").asLong());
        expect(call("GET", "/api/v1/teams/" + teamBId, key, null), 403);
        expect(call("POST", "/api/v1/teams", key, "{\"name\":\"No\"}"), 403);
        expect(call("GET", credentials(managerId), key, null), 403);
        expect(call("GET", "/api/v1/admin/audit-events", key, null), 403);
        expect(request(gatewayPort, "POST", "/api/v1/teams", key, "{\"name\":\"Forged\"}",
            Map.of("X-Authenticated-Role", "PLATFORM_ADMIN", "X-Employee-Id", Long.toString(admin.employeeId()),
                "X-AIGate-Service-Authorization", "Bearer svc_forged", "Forwarded", "for=192.0.2.1")),
            403);
        expect(call("DELETE", role(managerId, "TEAM_MANAGER", "TEAM", teamAId), admin.key(), null), 204);
        expect(call("GET", "/api/v1/teams", key, null), 403);
    }

    /**
     * 验证密钥只展示一次、跨员工幂等冲突、吊销及过期立即生效。
     */
    @Test
    @DisplayName("验证密钥只展示一次、跨员工幂等冲突、吊销及过期立即生效。")
    void credentialsNeverReplaySecretsAndAreRevokedImmediately() throws Exception {
        Session admin = initialize();
        long firstEmployeeId = employee(admin, admin.teamId(), "USER_A");
        long secondEmployeeId = employee(admin, admin.teamId(), "USER_B");
        String expires = expiry();
        JsonNode issued = issue(admin, firstEmployeeId, "issue-same-owner", expires);
        String key = issued.get("key").asString();
        long credential = issued.get("credentialId").asLong();
        var replay = call("POST", credentials(firstEmployeeId), admin.key(), body(Map.of("expiresAt", expires)),
            "issue-same-owner");
        assertEquals("KEY_ALREADY_ISSUED_NOT_REVEALABLE", expect(replay, 409).get("error").get("code").asString());
        assertFalse(replay.body().contains(key));
        var cross = call("POST", credentials(secondEmployeeId), admin.key(), body(Map.of("expiresAt", expires)),
            "issue-same-owner");
        assertEquals("IDEMPOTENCY_CONFLICT", expect(cross, 409).get("error").get("code").asString());
        expect(call("POST", credentials(firstEmployeeId), admin.key(),
            body(Map.of("expiresAt", Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MICROS).toString())),
            "issue-short-expiry"), 400);
        var metadata = call("GET", credentials(firstEmployeeId), admin.key(), null);
        expect(metadata, 200);
        assertFalse(metadata.body().contains("secretDigest"));
        assertFalse(metadata.body().contains(key));
        assertTrue(metadata.body().contains("\"revokedAt\":null"));
        expect(call("GET", "/api/v1/me/profile", key, null), 200);
        expect(call("DELETE", credentials(secondEmployeeId) + "/" + credential, admin.key(), null), 404);
        expect(call("DELETE", credentials(firstEmployeeId) + "/" + credential, admin.key(), null), 204);
        long audits = scalar("SELECT COUNT(*) FROM admin_audit_event");
        expect(call("DELETE", credentials(firstEmployeeId) + "/" + credential, admin.key(), null), 204);
        assertEquals(audits, scalar("SELECT COUNT(*) FROM admin_audit_event"));
        expect(call("GET", "/api/v1/me/profile", key, null), 401);
        JsonNode expired = issue(admin, firstEmployeeId, "issue-expired-test", expiry());
        sql("UPDATE management_credential SET created_at=UTC_TIMESTAMP(6)-INTERVAL "
            + "2 HOUR,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 HOUR WHERE id="
            + expired.get("credentialId").asLong());
        expect(call("GET", "/api/v1/me/profile", expired.get("key").asString(), null), 401);
        JsonNode disabled = issue(admin, firstEmployeeId, "issue-disabled-test", expiry());
        expect(
            call("PATCH", "/api/v1/employees/" + firstEmployeeId, admin.key(),
                "{\"expectedVersion\":0,\"status\":\"DISABLED\"}"),
            200);
        expect(call("GET", "/api/v1/me/profile", disabled.get("key").asString(), null), 403);
        var audit = call("GET", "/api/v1/admin/audit-events", admin.key(), null);
        JsonNode events = expect(audit, 200);
        assertFalse(audit.body().contains(key));
        assertTrue(events.get("items").get(0).get("changedFields").isArray());
        assertEquals(32, scalar("SELECT OCTET_LENGTH(secret_digest) FROM management_credential LIMIT 1"));
    }

    /**
     * 保护最后有效管理员，并验证重复角色操作不追加重复成功审计。
     */
    @Test
    @DisplayName("保护最后有效管理员，并验证重复角色操作不追加重复成功审计。")
    void lastAdminCannotBeRemovedAndRoleMutationsAreIdempotent() throws Exception {
        Session admin = initialize();
        expect(call("DELETE", role(admin.employeeId(), "PLATFORM_ADMIN", "GLOBAL", 0), admin.key(), null), 409);
        expect(call("PATCH", "/api/v1/employees/" + admin.employeeId(), admin.key(),
            "{\"expectedVersion\":0,\"status\":\"DISABLED\"}"), 409);
        long second = employee(admin, admin.teamId(), "ADMIN_TWO");
        String path = role(second, "PLATFORM_ADMIN", "GLOBAL", 0);
        expect(call("PUT", path, admin.key(), null), 201);
        long count = scalar("SELECT COUNT(*) FROM admin_audit_event");
        expect(call("PUT", path, admin.key(), null), 204);
        assertEquals(count, scalar("SELECT COUNT(*) FROM admin_audit_event"));
        String secondKey = issue(admin, second, "second-admin-key", expiry()).get("key").asString();
        expect(call("DELETE", role(admin.employeeId(), "PLATFORM_ADMIN", "GLOBAL", 0), secondKey, null), 204);
        expect(call("POST", "/api/v1/teams", admin.key(), "{\"name\":\"Revoked admin\"}"), 403);
        expect(call("DELETE", path, secondKey, null), 409);
        assertEquals(1,
            scalar("SELECT COUNT(*) FROM role_assignment WHERE role_code='PLATFORM_ADMIN' AND revoked_at IS NULL"));
    }

    /**
     * 两个管理员并发撤销自身角色后，必须至少保留一个有效全局管理员。
     */
    @Test
    @DisplayName("两个管理员并发撤销自身角色后，必须至少保留一个有效全局管理员。")
    void concurrentSelfRevocationCannotRemoveBothAdministrators() throws Exception {
        Session first = initialize();
        long second = employee(first, first.teamId(), "SECOND_ADMIN");
        expect(call("PUT", role(second, "PLATFORM_ADMIN", "GLOBAL", 0), first.key(), null), 201);
        String key = issue(first, second, "concurrent-admin-key", expiry()).get("key").asString();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstRevocation = pool.submit(
                () -> call("DELETE", role(first.employeeId(), "PLATFORM_ADMIN", "GLOBAL", 0), first.key(), null));
            var secondRevocation = pool
                .submit(() -> call("DELETE", role(second, "PLATFORM_ADMIN", "GLOBAL", 0), key, null));
            var statuses = List.of(firstRevocation.get().status(), secondRevocation.get().status());
            assertTrue(statuses.contains(204));
            assertTrue(statuses.contains(409));
        }
        assertEquals(1,
            scalar("SELECT COUNT(*) FROM role_assignment WHERE role_code='PLATFORM_ADMIN' AND revoked_at IS NULL"));
    }

    /**
     * 验证 OFF、来源网段及 RECOVERY 边界，恢复凭证不得增加角色。
     */
    @Test
    @DisplayName("验证 OFF、来源网段及 RECOVERY 边界，恢复凭证不得增加角色。")
    void bootstrapModesSourceBoundaryAndRecoveryDoNotCreateRoles() throws Exception {
        Session admin = initialize();
        long ordinary = employee(admin, admin.teamId(), "ORDINARY_USER");
        stop(core);
        startCore("OFF", Map.of());
        expect(call("POST", "/api/v1/bootstrap/initialize", bootstrapToken, bootstrapBody("Off", "ADM_OFF"),
            "bootstrap-off-key"), 403);
        stop(core);
        startCore("INIT", Map.of("AIGATE_BOOTSTRAP_ALLOWED_CIDRS", "192.0.2.0/24"));
        expect(request(gatewayPort, "POST", "/api/v1/bootstrap/initialize", bootstrapToken,
            bootstrapBody("No", "ADM_NO"), Map.of("Idempotency-Key", "bootstrap-source-key", "X-AIGate-Client-Address",
                "192.0.2.1", "Forwarded", "for=192.0.2.1")),
            403);
        stop(core);
        startCore("RECOVERY", Map.of());
        String target = body(Map.of("targetEmployeeCode", "ORDINARY_USER", "reasonCode", "ADMIN_KEY_LOST",
            "managementKeyExpiresAt", expiry()));
        expect(call("POST", "/api/v1/bootstrap/recover-management-key", bootstrapToken, target, "recovery-ordinary"),
            403);
        String recovery = body(Map.of("targetEmployeeCode", "ADM_001", "reasonCode", "ADMIN_KEY_LOST",
            "managementKeyExpiresAt", expiry()));
        long roles = scalar("SELECT COUNT(*) FROM role_assignment");
        var recovered = expect(
            call("POST", "/api/v1/bootstrap/recover-management-key", bootstrapToken, recovery, "recovery-admin-key"),
            201);
        String key = recovered.get("managementCredential").get("key").asString();
        secretValues.add(key);
        assertEquals(admin.employeeId(), recovered.get("employeeId").asLong());
        assertEquals(roles, scalar("SELECT COUNT(*) FROM role_assignment"));
        expect(call("GET", "/api/v1/me/profile", key, null), 200);
        expect(call("POST", "/api/v1/bootstrap/recover-management-key", bootstrapToken, recovery, "recovery-admin-key"),
            409);
        expect(call("POST", "/api/v1/bootstrap/recover-management-key", admin.key(), recovery, "recovery-wrong-key"),
            401);
        sql("UPDATE role_assignment SET revoked_at=UTC_TIMESTAMP(6) WHERE role_code='PLATFORM_ADMIN'");
        expect(call("POST", "/api/v1/bootstrap/recover-management-key", bootstrapToken, recovery, "recovery-no-admin"),
            403);
    }

    /**
     * 验证内部和未来接口不可公开访问，服务凭证与用户凭证不可互换。
     */
    @Test
    @DisplayName("验证内部和未来接口不可公开访问，服务凭证与用户凭证不可互换。")
    void publicAllowlistServiceAuthenticationAndTypedInput() throws Exception {
        Session admin = initialize();
        var protectedTrace = request(gatewayPort, "POST", "/api/v1/teams", admin.key(), "{\"name\":\"Safe trace\"}",
            Map.of("X-Request-Id", admin.key()));
        expect(protectedTrace, 201);
        assertFalse(protectedTrace.headers().get("x-request-id").contains(admin.key()));
        assertEquals(0, scalar("SELECT COUNT(*) FROM admin_audit_event WHERE trace_id LIKE 'mgt_%'"));
        assertEquals(17, P1Routes.ALL.stream().mapToInt(route -> route.methods().size()).sum());
        for (String path : List.of("/internal/v1/ping", "/actuator/env", "/v1/chat/completions",
            "/api/v1/finops/summary", "/api/v1/agents")) {
            expect(call("GET", path, admin.key(), null), 404);
        }
        for (String path : List.of("/internal%2fv1%2fping", "/api/v1/teams/../internal", "/api/v1/teams;anything")) {
            int status = call("GET", path, admin.key(), null).status();
            assertTrue(status == 400 || status == 404);
        }
        expect(call("DELETE", "/api/v1/teams", admin.key(), null), 405);
        expect(call("GET", "/api/v1/me/profile", null, null), 401);
        expect(call("GET", "/api/v1/me/profile", bootstrapToken, null), 401);
        expect(call("GET", "/api/v1/me/profile", "agk_" + random(), null), 401);
        expect(request(corePort, "GET", "/internal/v1/ping", admin.key(), null, Map.of()), 401);
        expect(request(corePort, "GET", "/internal/v1/ping", null, null,
            Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken)), 200);
        expect(request(corePort, "GET", "/internal/v1/ping", null, null,
            Map.of("X-AIGate-Service-Authorization", "Bearer svc_wrong")), 401);
    }

    /**
     * 模拟停机和提交后响应丢失，验证安全失败且重放不会再次签发密钥。
     */
    @Test
    @DisplayName("模拟停机和提交后响应丢失，验证安全失败且重放不会再次签发密钥。")
    void coreOutageTimeoutAndLostCommitResponseNeverReissue() throws Exception {
        Session admin = initialize();
        stop(core);
        expect(call("GET", "/api/v1/me/profile", admin.key(), null), 503);
        expect(call("POST", "/api/v1/teams", admin.key(), "{\"name\":\"Not committed\"}"), 503);
        startCore("INIT", Map.of());
        expect(call("GET", "/api/v1/me/profile", admin.key(), null), 200);
        assertEquals(0, scalar("SELECT COUNT(*) FROM team WHERE name='Not committed'"));
        long employee = employee(admin, admin.teamId(), "ACK_LOST_USER");
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        proxy.setExecutor(executor);
        CountDownLatch committed = new CountDownLatch(1);
        proxy.createContext("/", exchange -> {
            try {
                byte[] payload = exchange.getRequestBody().readAllBytes();
                if (exchange.getRequestMethod().equals("GET")) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                var builder = HttpRequest.newBuilder(URI.create(coreUrl + exchange.getRequestURI()))
                    .timeout(Duration.ofSeconds(5));
                for (String header : List.of("Authorization", "Content-Type", "Idempotency-Key",
                    "X-AIGate-Service-Authorization", "X-AIGate-Client-Address", "X-Request-Id")) {
                    String value = exchange.getRequestHeaders().getFirst(header);
                    if (value != null) {
                        builder.header(header, value);
                    }
                }
                var response = client.send(builder
                    .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
                    HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 201) {
                    committed.countDown();
                }
                Thread.sleep(12000);
                exchange.sendResponseHeaders(504, -1);
            } catch (InterruptedException interrupted) {
                // 测试退出时尊重中断请求，避免吞掉线程取消信号。
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 测试客户端主动断开后，服务端写响应可能失败；此分支仅用于模拟断连。
            } finally {
                exchange.close();
            }
        });
        proxy.start();
        String expires = expiry();
        try {
            stop(gateway);
            startGateway("http://127.0.0.1:" + proxy.getAddress().getPort());
            var failed = call("POST", credentials(employee), admin.key(), body(Map.of("expiresAt", expires)),
                "ack-lost-key-0001");
            assertTrue(committed.await(1, TimeUnit.SECONDS));
            expect(failed, 504);
            assertFalse(failed.body().contains("mgt_"));
            assertEquals(1, scalar("SELECT COUNT(*) FROM management_credential WHERE employee_id=" + employee));
            stop(gateway);
            startGateway(coreUrl);
            expect(call("POST", credentials(employee), admin.key(), body(Map.of("expiresAt", expires)),
                "ack-lost-key-0001"), 409);
            assertEquals(1, scalar("SELECT COUNT(*) FROM management_credential WHERE employee_id=" + employee));
        } finally {
            proxy.stop(0);
            executor.close();
        }
    }

    /**
     * 验证应用账号无 DDL 或跨库权限，V1 重复启动不产生重复迁移。
     */
    @Test
    @DisplayName("验证应用账号无 DDL 或跨库权限，V1 重复启动不产生重复迁移。")
    void applicationAccountCannotMigrateOrReadFinopsAndV1IsStable() throws Exception {
        try (var app = DriverManager.getConnection(mysql.getJdbcUrl(), "aigate_app", appPassword);
            var statement = app.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("CREATE TABLE forbidden(id BIGINT)"));
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM finops_db.private_fact"));
        }
        assertEquals(7, scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='core_db'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1"));
        try (Connection db = db();
            var statement = db.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute(
                "INSERT INTO team(name,status,version,created_at,updated_at) "
                    + "VALUES('bad','INVALID',0,UTC_TIMESTAMP(),UTC_TIMESTAMP())"));
            assertThrows(SQLException.class, () -> statement.execute(
                "INSERT INTO employee(employee_code,display_name,primary_team_id,created_at,updated_at) "
                    + "VALUES('BAD_FK','x',999,UTC_TIMESTAMP(),UTC_TIMESTAMP())"));
        }
    }

    /**
     * 验证新旧服务凭证和管理 Pepper 的明确轮换窗口及过期拒绝。
     */
    @Test
    @DisplayName("验证新旧服务凭证和管理 Pepper 的明确轮换窗口及过期拒绝。")
    void serviceAndManagementPepperRotationHaveBoundedWindows() throws Exception {
        Session admin = initialize();
        String previousService = "svc_" + random();
        String newPepper = Base64.getEncoder().encodeToString(randomBytes());
        secretValues.addAll(List.of(previousService, newPepper));
        var settings = new HashMap<>(Map.of("AIGATE_SERVICE_PREVIOUS_TOKEN", previousService,
            "AIGATE_SERVICE_PREVIOUS_VALID_UNTIL", Instant.now().plusSeconds(3600).toString(),
            "AIGATE_MANAGEMENT_PEPPER_KEY_ID", "mgmt-v2", "AIGATE_MANAGEMENT_PEPPER", newPepper,
            "AIGATE_MANAGEMENT_PREVIOUS_PEPPER_KEY_ID", "mgmt-v1", "AIGATE_MANAGEMENT_PREVIOUS_PEPPER", pepper,
            "AIGATE_MANAGEMENT_PREVIOUS_VALID_UNTIL", Instant.now().plusSeconds(3600).toString()));
        stop(core);
        startCore("OFF", settings);
        expect(request(corePort, "GET", "/internal/v1/ping", null, null,
            Map.of("X-AIGate-Service-Authorization", "Bearer " + previousService)), 200);
        expect(call("GET", "/api/v1/me/profile", admin.key(), null), 200);
        String nextKey = issue(admin, admin.employeeId(), "pepper-rotation-key", expiry()).get("key").asString();
        settings.put("AIGATE_SERVICE_PREVIOUS_VALID_UNTIL", Instant.now().minusSeconds(1).toString());
        settings.put("AIGATE_MANAGEMENT_PREVIOUS_VALID_UNTIL", Instant.now().minusSeconds(1).toString());
        stop(core);
        startCore("OFF", settings);
        expect(request(corePort, "GET", "/internal/v1/ping", null, null,
            Map.of("X-AIGate-Service-Authorization", "Bearer " + previousService)), 401);
        expect(call("GET", "/api/v1/me/profile", admin.key(), null), 401);
        expect(call("GET", "/api/v1/me/profile", nextKey, null), 200);
    }

    /**
     * 弱 Bootstrap 凭证必须导致启动失败，不能靠请求时才补救配置错误。
     */
    @Test
    @DisplayName("弱 Bootstrap 凭证必须导致启动失败，不能靠请求时才补救配置错误。")
    void weakBootstrapConfigurationFailsStartup() throws Exception {
        stop(core);
        Process invalid = process("aigate-core", coreEnvironment("INIT", Map.of("AIGATE_BOOTSTRAP_TOKEN", "bst_weak")));
        try {
            assertTrue(invalid.waitFor(25, TimeUnit.SECONDS));
            assertNotEquals(0, invalid.exitValue());
        } finally {
            stop(invalid);
        }
        startCore("INIT", Map.of());
    }
}
