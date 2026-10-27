package com.aigate.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** P5真实双JVM与MySQL：当前Team范围、发行收据、转Team与网络响应丢失。 */
class GrantApiIT extends AbstractIdentityIntegrationTest {
    private record Fixture(Session admin, long issuer, long recipientTeam, long manager, String managerKey,
        long recipient, String recipientKey, String periodStart, String periodEnd, String expires) {
    }

    private Fixture fixture() throws Exception {
        Session admin = initialize();
        long issuer = team(admin, "Issuing Team");
        long recipientTeam = team(admin, "Recipient Team");
        long manager = employee(admin, issuer, "GRANT_MANAGER");
        long recipient = employee(admin, recipientTeam, "GRANT_RECIPIENT");
        expect(call("PUT", role(manager, "TEAM_MANAGER", "TEAM", issuer), admin.key(), null), 201);
        String managerKey = issue(admin, manager, "grant-manager-key", expiry()).get("key").asString();
        String recipientKey = issue(admin, recipient, "grant-recipient-key", expiry()).get("key").asString();
        var month = YearMonth.now(ZoneId.of("Asia/Shanghai"));
        return new Fixture(admin, issuer, recipientTeam, manager, managerKey, recipient, recipientKey,
            month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString(), expiry());
    }

    private String poolBody(Fixture fixture, String amount, Long version) {
        return "{\"limitPoints\":\"" + amount + "\",\"periodEnd\":\"" + fixture.periodEnd()
            + "\",\"expectedVersion\":" + version + "}";
    }

    private String issueBody(Fixture fixture, String amount) {
        return body(Map.of("recipientEmployeeId", fixture.recipient(), "points", amount,
            "expiresAt", fixture.expires(), "reasonCode", "TEAM_ALLOCATION"));
    }

    private String teamBase(long team) {
        return "/api/v1/teams/" + team;
    }

    private void pool(Fixture fixture, String amount) throws Exception {
        expect(call("PUT", teamBase(fixture.issuer()) + "/pool/" + fixture.periodStart(), fixture.admin().key(),
            poolBody(fixture, amount, null)), 201);
    }

    private JsonNode grant(Fixture fixture, String key, String amount) throws Exception {
        return expect(call("POST", teamBase(fixture.issuer()) + "/grants", fixture.managerKey(),
            issueBody(fixture, amount), key), 201);
    }

    @Test
    @DisplayName("BIZP5-02/03/12：六个公开方法、跨Team发放、当前Manager范围及接收者本人分页")
    void crossTeamIssueAndEveryReadStayWithinCurrentIdentityScope() throws Exception {
        Fixture f = fixture();
        pool(f, "1000.000000");
        String pool = teamBase(f.issuer()) + "/pool?periodStart=" + f.periodStart();
        assertEquals("1000.000000", expect(call("GET", pool, f.managerKey(), null), 200)
            .get("availablePoints").asString());
        expect(call("PUT", teamBase(f.issuer()) + "/pool/" + f.periodStart(), f.managerKey(),
            poolBody(f, "1000.000000", 0L)), 403);
        var grant = grant(f, "cross-team-grant-key", "100.000000");
        assertEquals(f.issuer(), grant.get("issuerTeamId").asLong());
        assertEquals(f.recipient(), grant.get("recipientEmployeeId").asLong());
        var mine = expect(call("GET", "/api/v1/employees/me/grants?page=1&size=1", f.recipientKey(), null), 200);
        assertEquals(1, mine.get("total").asLong());
        assertEquals(grant, mine.get("items").get(0));
        assertEquals(0, expect(call("GET", "/api/v1/employees/me/grants", f.managerKey(), null), 200)
            .get("total").asLong());
        expect(call("GET", teamBase(f.recipientTeam()) + "/grants", f.managerKey(), null), 403);
        expect(call("GET", teamBase(f.issuer()) + "/grants", f.recipientKey(), null), 403);
        expect(call("POST", teamBase(f.issuer()) + "/grants", f.recipientKey(),
            issueBody(f, "1.000000"), "employee-self-grant"), 403);
        expect(call("POST", teamBase(f.recipientTeam()) + "/grants", f.managerKey(),
            issueBody(f, "1.000000"), "wrong-team-grant"), 403);
        var replay = expect(call("POST", teamBase(f.issuer()) + "/grants", f.managerKey(),
            issueBody(f, "100.000000"), "cross-team-grant-key"), 409);
        assertEquals(4, replay.get("error").size());
        assertTrue(replay.get("error").get("message").asString().contains("grantId=" + grant.get("grantId").asLong()));
        var conflict = expect(call("POST", teamBase(f.issuer()) + "/grants", f.managerKey(),
            issueBody(f, "101.000000"), "cross-team-grant-key"), 409);
        assertFalse(conflict.get("error").get("message").asString().contains("grantId="));
        expect(call("POST", teamBase(f.recipientTeam()) + "/grants/" + grant.get("grantId").asLong() + "/revoke",
            f.admin().key(), "{\"reasonCode\":\"INVALID_ISSUER\"}"), 404);
        String revoke = teamBase(f.issuer()) + "/grants/" + grant.get("grantId").asLong() + "/revoke";
        expect(call("POST", revoke, f.managerKey(), "{\"reasonCode\":\"ACCESS_REMOVED\"}"), 204);
        long audits = scalar("SELECT COUNT(*) FROM admin_audit_event");
        expect(call("POST", revoke, f.managerKey(), "{\"reasonCode\":\"SECOND_REASON\"}"), 204);
        assertEquals(audits, scalar("SELECT COUNT(*) FROM admin_audit_event"));
        var after = expect(call("GET", "/api/v1/employees/me/grants?status=REVOKED_FOR_NEW_USE",
            f.recipientKey(), null), 200);
        assertEquals(1, after.get("total").asLong());
        assertEquals("0.000000", after.get("items").get(0).get("availablePoints").asString());
        assertEquals("900.000000", expect(call("GET", pool, f.managerKey(), null), 200)
            .get("availablePoints").asString());
        expect(call("GET", "/api/v1/governance/policies/active", f.admin().key(), null), 404);
        expect(call("POST", "/v1/chat/completions", f.admin().key(), "{}"), 404);
    }

    @Test
    @DisplayName("BIZP5-05/D50-05：两个真实Manager竞争Pool1000，700+600不能同时发行")
    void twoManagersCannotOverissueAndOtherActorsCannotReadReceiptIdentity() throws Exception {
        Fixture f = fixture();
        pool(f, "1000.000000");
        long second = employee(f.admin(), f.issuer(), "SECOND_MANAGER");
        expect(call("PUT", role(second, "TEAM_MANAGER", "TEAM", f.issuer()), f.admin().key(), null), 201);
        String secondKey = issue(f.admin(), second, "second-manager-credential", expiry()).get("key").asString();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return call("POST", teamBase(f.issuer()) + "/grants", f.managerKey(),
                    issueBody(f, "700.000000"), "manager-one-issue").status();
            });
            var secondResult = pool.submit(() -> {
                start.await();
                return call("POST", teamBase(f.issuer()) + "/grants", secondKey,
                    issueBody(f, "600.000000"), "manager-two-issue").status();
            });
            start.countDown();
            assertEquals(List.of(201, 409),
                java.util.stream.Stream.of(first.get(), secondResult.get()).sorted().toList());
        }
        assertEquals(1, scalar("SELECT COUNT(*) FROM team_grant"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM team_grant_issue_receipt"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM team_pool WHERE issued_points>limit_points"));
        grant(f, "original-actor-receipt", "1.000000");
        var collision = expect(call("POST", teamBase(f.issuer()) + "/grants", secondKey,
            issueBody(f, "1.000000"), "original-actor-receipt"), 409);
        assertEquals("IDEMPOTENCY_CONFLICT", collision.get("error").get("code").asString());
        assertFalse(collision.get("error").get("message").asString().contains("grantId="));
    }

    @Test
    @DisplayName("BIZP5-12：收件人转Team不改发行快照，撤销Manager角色立即收回管理范围")
    void employeeTransfersAndManagerRevocationCannotRewriteHistoryOrLeakTotals() throws Exception {
        Fixture f = fixture();
        pool(f, "1000.000000");
        grant(f, "transfer-grant-key", "100.000000");
        expect(call("PATCH", "/api/v1/employees/" + f.recipient(), f.admin().key(),
            body(Map.of("expectedVersion", 0, "primaryTeamId", f.admin().teamId()))), 200);
        assertEquals(1, expect(call("GET", teamBase(f.issuer()) + "/grants?page=1&size=1", f.managerKey(), null), 200)
            .get("total").asLong());
        assertEquals(f.issuer(), expect(call("GET", "/api/v1/employees/me/grants", f.recipientKey(), null), 200)
            .get("items").get(0).get("issuerTeamId").asLong());
        expect(call("GET", "/api/v1/employees/me/grants?employeeId=" + f.recipient(), f.managerKey(), null), 400);
        expect(call("GET", teamBase(f.recipientTeam()) + "/grants?size=1&page=100", f.managerKey(), null), 403);
        expect(call("DELETE", role(f.manager(), "TEAM_MANAGER", "TEAM", f.issuer()), f.admin().key(), null), 204);
        expect(call("GET", teamBase(f.issuer()) + "/grants", f.managerKey(), null), 403);
        expect(call("POST", teamBase(f.issuer()) + "/grants", f.managerKey(),
            issueBody(f, "1.000000"), "revoked-manager-issue"), 403);
        assertEquals(1, scalar("SELECT COUNT(*) FROM team_grant"));
    }

    @Test
    @DisplayName("P5严格输入：定点字符串、null、未知字段、重复Header/参数及期限错误不写账")
    void strictInputsAndCallerTypesCannotMutateAccounts() throws Exception {
        Fixture f = fixture();
        pool(f, "1000.000000");
        long audits = scalar("SELECT COUNT(*) FROM admin_audit_event");
        String valid = issueBody(f, "100.000000");
        for (String bad : List.of(valid.replace("\"100.000000\"", "100"),
            valid.replace("100.000000", "1e2"), valid.replace("100.000000", "0.000000"),
            valid.replace("100.000000", "-100.000000"), valid.replace("100.000000", "100.0000000"),
            valid.replace("TEAM_ALLOCATION", "raw personal reason"),
            valid.replace("\"recipientEmployeeId\"", "\"employeeId\""),
            valid.replace("\"100.000000\"", "null"), valid.replace(f.expires(), "2026-01-01T00:00:00Z"))) {
            expect(call("POST", teamBase(f.issuer()) + "/grants", f.managerKey(), bad, "bad-grant-request"), 400);
        }
        for (String token : List.of(serviceToken, bootstrapToken)) {
            expect(call("POST", teamBase(f.issuer()) + "/grants", token, valid, "wrong-key-type"), 401);
        }
        String poolUpdate = poolBody(f, "1000.000000", 0L);
        for (String bad : List.of(poolUpdate.replace("\"expectedVersion\":0", "\"expectedVersion\":\"0\""),
            poolUpdate.replace("\"expectedVersion\":0", "\"expectedVersion\":-1"),
            poolUpdate.replace("\"expectedVersion\":0", "\"expectedVersion\":0.0"),
            poolUpdate.replace("\"1000.000000\"", "1000"))) {
            expect(call("PUT", teamBase(f.issuer()) + "/pool/" + f.periodStart(), f.admin().key(), bad), 400);
        }
        expect(call("GET", teamBase(f.issuer()) + "/pool", f.managerKey(), null), 400);
        expect(call("GET", teamBase(f.issuer()) + "/pool?periodStart=2026-02-30", f.managerKey(), null), 400);
        expect(call("GET", teamBase(f.issuer()) + "/grants?page=1&page=2", f.managerKey(), null), 400);
        expect(call("GET", "/api/v1/employees/me/grants?size=101", f.recipientKey(), null), 400);
        var duplicate = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort
            + teamBase(f.issuer()) + "/grants")).header("Authorization", "Bearer " + f.managerKey())
            .header("Content-Type", "application/json").header("Idempotency-Key", "duplicate-one")
            .header("Idempotency-Key", "duplicate-two").POST(HttpRequest.BodyPublishers.ofString(valid)).build();
        assertEquals(400, client.send(duplicate, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(audits, scalar("SELECT COUNT(*) FROM admin_audit_event"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM team_grant"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM outbox_event"));
    }

    @Test
    @DisplayName("P5真实审计失败：发行和撤销均回滚资金、Receipt、Ledger与Outbox")
    void auditFailureRollsBackIssueAndRevoke() throws Exception {
        Fixture f = fixture();
        pool(f, "1000.000000");
        sql("CREATE TRIGGER reject_grant_audit BEFORE INSERT ON admin_audit_event FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try {
            expect(call("POST", teamBase(f.issuer()) + "/grants", f.managerKey(),
                issueBody(f, "100.000000"), "audit-failed-grant"), 503);
            assertEquals(0, scalar("SELECT COUNT(*) FROM team_grant_issue_receipt"));
            assertEquals(0, scalar("SELECT COUNT(*) FROM outbox_event"));
            assertEquals(0, scalar("SELECT COUNT(*) FROM ledger_entry"));
            assertEquals(0, scalar("SELECT issued_points FROM team_pool"));
        } finally {
            sql("DROP TRIGGER reject_grant_audit");
        }
        var grant = grant(f, "audit-failed-grant", "100.000000");
        String revoke = teamBase(f.issuer()) + "/grants/" + grant.get("grantId").asLong() + "/revoke";
        sql("CREATE TRIGGER reject_revoke_audit BEFORE INSERT ON admin_audit_event FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try {
            expect(call("POST", revoke, f.managerKey(), "{\"reasonCode\":\"REVOKE_TEST\"}"), 503);
            assertEquals(1, scalar("SELECT COUNT(*) FROM team_grant WHERE status='ACTIVE' AND revoked_at IS NULL"));
            assertEquals(100, scalar("SELECT issued_points FROM team_pool"));
        } finally {
            sql("DROP TRIGGER reject_revoke_audit");
        }
        expect(call("POST", revoke, f.managerKey(), "{\"reasonCode\":\"REVOKE_TEST\"}"), 204);
    }

    @Test
    @DisplayName("BIZP5-04：Core已提交但HTTP成功响应丢失，Gateway不重试，重启重放409且不重复发行")
    void lostHttpSuccessAndRestartCannotIssueGrantTwice() throws Exception {
        Fixture f = fixture();
        pool(f, "1000.000000");
        String path = teamBase(f.issuer()) + "/grants";
        AtomicInteger forwarded = new AtomicInteger();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            proxy.setExecutor(workers);
            proxy.createContext("/", exchange -> {
                try {
                    if ("GET".equals(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(401, -1);
                        return;
                    }
                    forwarded.incrementAndGet();
                    var request = HttpRequest.newBuilder(URI.create(coreUrl + exchange.getRequestURI()));
                    for (String header : List.of("Authorization", "Content-Type", "Idempotency-Key",
                        "X-AIGate-Service-Authorization", "X-AIGate-Client-Address", "X-Request-Id")) {
                        String value = exchange.getRequestHeaders().getFirst(header);
                        if (value != null) {
                            request.header(header, value);
                        }
                    }
                    var reply = client.send(request.POST(HttpRequest.BodyPublishers
                        .ofByteArray(exchange.getRequestBody().readAllBytes())).build(),
                        HttpResponse.BodyHandlers.ofString());
                    exchange.sendResponseHeaders(reply.statusCode() == 201 ? 504 : 502, -1);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // 以真实数据库而非客户端状态判断是否提交。
                } finally {
                    exchange.close();
                }
            });
            proxy.start();
            try {
                stop(gateway);
                startGateway("http://127.0.0.1:" + proxy.getAddress().getPort());
                expect(call("POST", path, f.managerKey(), issueBody(f, "100.000000"), "lost-grant-response"), 504);
                assertEquals(1, forwarded.get());
                assertEquals(1, scalar("SELECT COUNT(*) FROM team_grant"));
                stop(gateway);
                startGateway(coreUrl);
                stop(core);
                startCore("INIT", Map.of());
                var replay = expect(call("POST", path, f.managerKey(), issueBody(f, "100.000000"),
                    "lost-grant-response"), 409);
                assertEquals("GRANT_ALREADY_ISSUED", replay.get("error").get("code").asString());
                assertEquals(1, scalar("SELECT COUNT(*) FROM team_grant_issue_receipt"));
                assertEquals(1, scalar("SELECT COUNT(*) FROM ledger_entry"));
                assertEquals(1, scalar("SELECT COUNT(*) FROM outbox_event"));
                assertEquals(100, scalar("SELECT issued_points FROM team_pool"));
            } finally {
                proxy.stop(0);
            }
        }
    }
}
