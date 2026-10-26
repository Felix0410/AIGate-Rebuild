package com.aigate.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aigate.contracts.P1Routes;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
        // 1、使用不同团队名、员工编码和幂等键，并发发起两次初始化。
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstInitialization = pool.submit(() -> call("POST", "/api/v1/bootstrap/initialize", bootstrapToken,
                bootstrapBody("Platform-A", "ADM_A001"), "bootstrap-race-a"));
            var secondInitialization = pool.submit(() -> call("POST", "/api/v1/bootstrap/initialize", bootstrapToken,
                bootstrapBody("Platform-B", "ADM_B001"), "bootstrap-race-b"));

            // 2、检查一个请求返回 201，另一个返回 409，并登记成功响应的密钥。
            var replies = List.of(firstInitialization.get(), secondInitialization.get());
            assertEquals(1, replies.stream().filter(r -> r.status() == 201).count());
            assertEquals(1, replies.stream().filter(r -> r.status() == 409).count());
            for (Reply reply : replies) {
                if (reply.status() == 201) {
                    secretValues.add(json.readTree(reply.body()).get("managementCredential").get("key").asString());
                }
            }
        }

        // 3、检查团队、员工、角色、凭证和审计都只创建一条。
        for (String table : List.of("team", "employee", "role_assignment", "management_credential",
            "admin_audit_event")) {
            assertEquals(1, scalar("SELECT COUNT(*) FROM " + table));
        }

        assertEquals(1, scalar(
            "SELECT COUNT(*) FROM bootstrap_control WHERE "
                + "initialized_by_employee_id=1 AND initialized_at IS NOT NULL"));

        expect(call("POST", "/api/v1/bootstrap/initialize", bootstrapToken, bootstrapBody("Other", "ADM_OTHER"),
            "bootstrap-new-key"), 409);

        // 4、重启 Core，再次初始化仍返回 409，迁移历史不增加。
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
        // 1、创建审计失败触发器，模拟事务最后阶段写库失败。
        sql("CREATE TRIGGER reject_p1_audit BEFORE INSERT ON admin_audit_event "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try {
            // 2、发起初始化，要求返回 503，且不返回密钥。
            var failure = call("POST", "/api/v1/bootstrap/initialize", bootstrapToken,
                bootstrapBody("Rollback", "ADM_ROLLBACK"), "bootstrap-failure");
            expect(failure, 503);
            assertFalse(failure.body().contains("mgt_"));

            // 3、检查所有业务表为空，初始化标记仍未完成。
            for (String table : List.of("team", "employee", "role_assignment", "management_credential",
                "admin_audit_event")) {
                assertEquals(0, scalar("SELECT COUNT(*) FROM " + table));
            }

            assertEquals(1, scalar("SELECT COUNT(*) FROM bootstrap_control WHERE initialized_at IS NULL"));
        } finally {
            // 4、删除触发器，再检查正常初始化可以成功。
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
        // 1、创建管理员和团队，检查名称唯一约束。
        Session admin = initialize();
        long id = team(admin, "Engineering");
        expect(call("POST", "/api/v1/teams", admin.key(), "{\"name\":\"engineering\"}"), 409);

        // 2、逐个提交非法正文，要求返回 400。
        for (String invalid : List.of("{}", "[]", "{\"name\":null}", "{\"name\":123}", "{\"name\":\" \"}",
            "{\"name\":\"x\",\"extra\":true}", "{\"name\":\"x\",\"name\":\"y\"}", "{\"name\":\"x\"} {}")) {
            expect(call("POST", "/api/v1/teams", admin.key(), invalid), 400);
        }

        expect(call("POST", "/api/v1/teams", admin.key(), body(Map.of("name", "x".repeat(81)))), 400);

        // 3、用同一版本并发更新团队，要求一个成功、另一个版本冲突。
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

        // 4、创建员工，检查员工编码、重复记录、团队停用和输入类型限制。
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

        // 5、检查路径 ID、重复查询参数、分页上限及角色参数。
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
        // 1、准备管理员和超过 64 KiB 的正文，检查已知长度请求被拒绝。
        Session admin = initialize();
        String payload = body(Map.of("name", "x".repeat(70000)));
        expect(call("POST", "/api/v1/teams", admin.key(), payload), 400);

        // 2、改用输入流发送分块请求，检查同一超限正文仍被拒绝。
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/api/v1/teams"))
            .header("Authorization", "Bearer " + admin.key()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers
                .ofInputStream(() -> new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8))))
            .build();
        assertEquals(400, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 3、检查没有因此创建额外团队。
        assertEquals(1, scalar("SELECT COUNT(*) FROM team"));
    }

    /**
     * 验证 Manager 的列表与 COUNT 都先过滤团队，且不能写入或伪造管理员身份。
     */
    @Test
    @DisplayName("验证 Manager 的列表与 COUNT 都先过滤团队，且不能写入或伪造管理员身份。")
    void managerScopeIsFilteredBeforeCountAndCannotWrite() throws Exception {
        // 1、准备两个团队、各自员工以及只管理第一个团队的 Manager。
        Session admin = initialize();
        long teamAId = team(admin, "Team A");
        long teamBId = team(admin, "Team B");
        long managerId = employee(admin, teamAId, "MGR_001");
        long aliceId = employee(admin, teamAId, "ALICE_001");
        long bobId = employee(admin, teamBId, "BOB_001");
        expect(call("PUT", role(managerId, "TEAM_MANAGER", "TEAM", teamAId), admin.key(), null), 201);
        String key = issue(admin, managerId, "manager-key-0001", expiry()).get("key").asString();

        // 2、检查可读取本团队员工，不能读取另一团队员工。
        expect(call("GET", "/api/v1/employees/" + aliceId, key, null), 200);
        expect(call("GET", "/api/v1/employees/" + bobId, key, null), 403);

        // 3、检查分页列表和 total 都只包含授权范围内的员工。
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
        // 1、创建两个员工，为第一个员工首次签发凭证。
        Session admin = initialize();
        long firstEmployeeId = employee(admin, admin.teamId(), "USER_A");
        long secondEmployeeId = employee(admin, admin.teamId(), "USER_B");
        String expires = expiry();
        JsonNode issued = issue(admin, firstEmployeeId, "issue-same-owner", expires);
        String key = issued.get("key").asString();
        long credential = issued.get("credentialId").asLong();

        // 2、使用相同参数和幂等键重放，要求返回已签发错误且不再返回密钥。
        var replay = call("POST", credentials(firstEmployeeId), admin.key(), body(Map.of("expiresAt", expires)),
            "issue-same-owner");
        assertEquals("KEY_ALREADY_ISSUED_NOT_REVEALABLE", expect(replay, 409).get("error").get("code").asString());
        assertFalse(replay.body().contains(key));

        // 3、将同一幂等键用于另一个员工，要求返回幂等冲突。
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

        // 4、重复吊销同一凭证，检查审计不增加且密钥立即失效。
        long audits = scalar("SELECT COUNT(*) FROM admin_audit_event");
        expect(call("DELETE", credentials(firstEmployeeId) + "/" + credential, admin.key(), null), 204);
        assertEquals(audits, scalar("SELECT COUNT(*) FROM admin_audit_event"));
        expect(call("GET", "/api/v1/me/profile", key, null), 401);

        // 5、修改临时库时间模拟凭证过期，检查认证立即失败。
        JsonNode expired = issue(admin, firstEmployeeId, "issue-expired-test", expiry());

        sql("UPDATE management_credential SET created_at=UTC_TIMESTAMP(6)-INTERVAL "
            + "2 HOUR,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 HOUR WHERE id="
            + expired.get("credentialId").asLong());
        expect(call("GET", "/api/v1/me/profile", expired.get("key").asString(), null), 401);

        // 6、停用员工，检查其密钥不再获得访问权限。
        JsonNode disabled = issue(admin, firstEmployeeId, "issue-disabled-test", expiry());
        expect(
            call("PATCH", "/api/v1/employees/" + firstEmployeeId, admin.key(),
                "{\"expectedVersion\":0,\"status\":\"DISABLED\"}"),
            200);
        expect(call("GET", "/api/v1/me/profile", disabled.get("key").asString(), null), 403);

        // 7、检查审计不含完整密钥，数据库摘要长度为 32 字节。
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
        // 1、创建唯一管理员，检查不能撤销其角色或停用其员工。
        Session admin = initialize();
        expect(call("DELETE", role(admin.employeeId(), "PLATFORM_ADMIN", "GLOBAL", 0), admin.key(), null), 409);
        expect(call("PATCH", "/api/v1/employees/" + admin.employeeId(), admin.key(),
            "{\"expectedVersion\":0,\"status\":\"DISABLED\"}"), 409);

        // 2、创建第二名管理员，检查重复授权不会增加审计。
        long second = employee(admin, admin.teamId(), "ADMIN_TWO");
        String path = role(second, "PLATFORM_ADMIN", "GLOBAL", 0);
        expect(call("PUT", path, admin.key(), null), 201);

        long count = scalar("SELECT COUNT(*) FROM admin_audit_event");
        expect(call("PUT", path, admin.key(), null), 204);
        assertEquals(count, scalar("SELECT COUNT(*) FROM admin_audit_event"));

        // 3、使用第二名管理员撤销第一名，检查旧身份失去写权限。
        String secondKey = issue(admin, second, "second-admin-key", expiry()).get("key").asString();
        expect(call("DELETE", role(admin.employeeId(), "PLATFORM_ADMIN", "GLOBAL", 0), secondKey, null), 204);
        expect(call("POST", "/api/v1/teams", admin.key(), "{\"name\":\"Revoked admin\"}"), 403);

        // 4、尝试撤销最后一名管理员，要求拒绝并保留一个有效角色。
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
        // 1、创建两名管理员及各自密钥。
        Session first = initialize();

        long second = employee(first, first.teamId(), "SECOND_ADMIN");
        expect(call("PUT", role(second, "PLATFORM_ADMIN", "GLOBAL", 0), first.key(), null), 201);
        String key = issue(first, second, "concurrent-admin-key", expiry()).get("key").asString();

        // 2、各自并发撤销自身角色，要求一人成功、另一人被最后管理员保护阻止。
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstRevocation = pool.submit(
                () -> call("DELETE", role(first.employeeId(), "PLATFORM_ADMIN", "GLOBAL", 0), first.key(), null));
            var secondRevocation = pool
                .submit(() -> call("DELETE", role(second, "PLATFORM_ADMIN", "GLOBAL", 0), key, null));
            var statuses = List.of(firstRevocation.get().status(), secondRevocation.get().status());
            assertTrue(statuses.contains(204));
            assertTrue(statuses.contains(409));
        }

        // 3、检查数据库最终只保留一名有效管理员。
        assertEquals(1,
            scalar("SELECT COUNT(*) FROM role_assignment WHERE role_code='PLATFORM_ADMIN' AND revoked_at IS NULL"));
    }

    /**
     * 验证 OFF、来源网段及 RECOVERY 边界，恢复凭证不得增加角色。
     */
    @Test
    @DisplayName("验证 OFF、来源网段及 RECOVERY 边界，恢复凭证不得增加角色。")
    void bootstrapModesSourceBoundaryAndRecoveryDoNotCreateRoles() throws Exception {
        // 1、创建管理员和普通员工，切换到 OFF 检查运维接口已关闭。
        Session admin = initialize();

        employee(admin, admin.teamId(), "ORDINARY_USER");

        stop(core);
        startCore("OFF", Map.of());
        expect(call("POST", "/api/v1/bootstrap/initialize", bootstrapToken, bootstrapBody("Off", "ADM_OFF"),
            "bootstrap-off-key"), 403);

        stop(core);

        // 2、限定来源网段，检查伪造来源头不能越过 Gateway。
        startCore("INIT", Map.of("AIGATE_BOOTSTRAP_ALLOWED_CIDRS", "192.0.2.0/24"));
        expect(request(gatewayPort, "POST", "/api/v1/bootstrap/initialize", bootstrapToken,
            bootstrapBody("No", "ADM_NO"), Map.of("Idempotency-Key", "bootstrap-source-key", "X-AIGate-Client-Address",
                "192.0.2.1", "Forwarded", "for=192.0.2.1")),
            403);

        stop(core);

        // 3、切换 RECOVERY，普通员工不能通过恢复接口获得管理员身份。
        startCore("RECOVERY", Map.of());

        String target = body(Map.of("targetEmployeeCode", "ORDINARY_USER", "reasonCode", "ADMIN_KEY_LOST",
            "managementKeyExpiresAt", expiry()));
        expect(call("POST", "/api/v1/bootstrap/recover-management-key", bootstrapToken, target, "recovery-ordinary"),
            403);

        // 4、为已有管理员恢复凭证，检查角色数量不变。
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

        // 5、撤销管理员角色，再次恢复必须被拒绝。
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
        // 1、把管理员密钥放入请求 ID，检查响应和审计不会原样保存。
        Session admin = initialize();
        var protectedTrace = request(gatewayPort, "POST", "/api/v1/teams", admin.key(), "{\"name\":\"Safe trace\"}",
            Map.of("X-Request-Id", admin.key()));
        expect(protectedTrace, 201);
        assertFalse(protectedTrace.headers().get("x-request-id").contains(admin.key()));
        assertEquals(0, scalar("SELECT COUNT(*) FROM admin_audit_event WHERE trace_id LIKE 'mgt_%'"));
        assertEquals(17, P1Routes.ALL.stream().mapToInt(route -> route.methods().size()).sum());

        // 2、检查内部接口和尚未开放的接口不能通过 Gateway 访问。
        for (String path : List.of("/internal/v1/ping", "/actuator/env", "/v1/chat/completions",
            "/api/v1/finops/summary", "/api/v1/agents")) {
            expect(call("GET", path, admin.key(), null), 404);
        }

        // 3、检查编码路径、路径跳转及分号不能绕过路径检查。
        for (String path : List.of("/internal%2fv1%2fping", "/api/v1/teams/../internal", "/api/v1/teams;anything")) {
            int status = call("GET", path, admin.key(), null).status();
            assertTrue(status == 400 || status == 404);
        }

        // 4、检查非法方法和错误凭证类型都被拒绝。
        expect(call("DELETE", "/api/v1/teams", admin.key(), null), 405);
        expect(call("GET", "/api/v1/me/profile", null, null), 401);
        expect(call("GET", "/api/v1/me/profile", bootstrapToken, null), 401);
        expect(call("GET", "/api/v1/me/profile", "agk_" + random(), null), 401);

        // 5、直接访问 Core，检查员工凭证不能替代服务凭证。
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
        // 1、停止 Core，检查 Gateway 返回 503，失败的创建请求没有落库。
        Session admin = initialize();

        stop(core);
        expect(call("GET", "/api/v1/me/profile", admin.key(), null), 503);
        expect(call("POST", "/api/v1/teams", admin.key(), "{\"name\":\"Not committed\"}"), 503);
        startCore("INIT", Map.of());
        expect(call("GET", "/api/v1/me/profile", admin.key(), null), 200);
        assertEquals(0, scalar("SELECT COUNT(*) FROM team WHERE name='Not committed'"));

        long employee = employee(admin, admin.teamId(), "ACK_LOST_USER");

        // 2、启动临时代理，转发到 Core 后故意延迟成功响应。
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

                // 3、延迟超过 Gateway 超时，模拟 Core 已提交但客户端收不到成功响应。
                Thread.sleep(12000);
                exchange.sendResponseHeaders(504, -1);
            } catch (InterruptedException interrupted) {
                // 收到中断时恢复中断标记，允许测试线程退出。
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 测试客户端已主动断开，此处允许写响应失败。
            } finally {
                exchange.close();
            }
        });
        proxy.start();
        String expires = expiry();
        try {
            stop(gateway);
            startGateway("http://127.0.0.1:" + proxy.getAddress().getPort());

            // 4、通过临时代理签发密钥，检查返回 504 且数据库只增加一条凭证。
            var failed = call("POST", credentials(employee), admin.key(), body(Map.of("expiresAt", expires)),
                "ack-lost-key-0001");

            assertTrue(committed.await(1, TimeUnit.SECONDS));
            expect(failed, 504);
            assertFalse(failed.body().contains("mgt_"));
            assertEquals(1, scalar("SELECT COUNT(*) FROM management_credential WHERE employee_id=" + employee));
            stop(gateway);

            // 5、恢复直连 Core，重放同一请求仍不能再次返回密钥。
            startGateway(coreUrl);
            expect(call("POST", credentials(employee), admin.key(), body(Map.of("expiresAt", expires)),
                "ack-lost-key-0001"), 409);
            assertEquals(1, scalar("SELECT COUNT(*) FROM management_credential WHERE employee_id=" + employee));
        } finally {
            // 6、关闭临时代理和执行线程。
            proxy.stop(0);
            executor.close();
        }
    }

    /**
     * 验证应用账号无 DDL 或跨库权限，V1 重复启动不产生重复迁移。
     */
    @Test
    @DisplayName("验证应用账号无 DDL 或跨库权限，V1 重复启动不产生重复迁移。")
    // 库表由临时容器创建，且本方法故意提交无权限或违反约束的 SQL；仅忽略 IDE 的数据源及名称解析提示。
    @SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
    void applicationAccountCannotMigrateOrReadFinopsAndV1IsStable() throws Exception {
        // 1、使用真实应用账号，检查 DDL 和跨库查询都被数据库拒绝。
        try (var app = DriverManager.getConnection(mysql.getJdbcUrl(), "aigate_app", appPassword);
            var statement = app.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("CREATE TABLE forbidden(id BIGINT)"));
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM finops_db.private_fact"));
        }

        // 2、检查表数量和 V1 成功迁移记录不变。
        assertEquals(7, scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='core_db'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1"));

        // 3、直接提交非法状态和不存在的外键，检查数据库约束仍生效。
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
        // 1、保留旧管理员密钥，准备新的服务凭证与 Pepper 配置。
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

        // 2、在有效轮换窗口内启动，检查旧服务凭证和旧管理密钥仍可用。
        startCore("OFF", settings);
        expect(request(corePort, "GET", "/internal/v1/ping", null, null,
            Map.of("X-AIGate-Service-Authorization", "Bearer " + previousService)), 200);
        expect(call("GET", "/api/v1/me/profile", admin.key(), null), 200);

        // 3、使用当前 Pepper 签发新密钥。
        String nextKey = issue(admin, admin.employeeId(), "pepper-rotation-key", expiry()).get("key").asString();

        // 4、把旧凭证和旧 Pepper 截止时间改为过去，重启后检查旧凭证失效、新密钥可用。
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
        // 1、使用弱 Bootstrap 凭证启动独立 Core 进程。
        stop(core);

        Process invalid = process("aigate-core", coreEnvironment("INIT", Map.of("AIGATE_BOOTSTRAP_TOKEN", "bst_weak")));
        try {
            // 2、要求进程在限定时间内以非零状态退出。
            assertTrue(invalid.waitFor(25, TimeUnit.SECONDS));
            assertNotEquals(0, invalid.exitValue());
        } finally {
            stop(invalid);
        }

        // 3、恢复正常 Core，供后续场景使用。
        startCore("INIT", Map.of());
    }
}
