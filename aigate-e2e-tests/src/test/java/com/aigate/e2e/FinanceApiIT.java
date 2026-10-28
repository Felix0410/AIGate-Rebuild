package com.aigate.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P4真实Gateway/Core双JVM接口、授权、审计与精确路由回归。 */
class FinanceApiIT extends AbstractIdentityIntegrationTest {
    private String budget(Long version) {
        return "{\"limitAmount\":\"0.10000000\",\"normalLimitAmount\":\"0.08000000\","
            + "\"emergencyLimitAmount\":\"0.02000000\",\"expectedVersion\":" + version + "}";
    }

    @Test
    @DisplayName("P4 API：八个管理方法经真实Gateway，未来阶段与Fake结算入口不开放")
    void allEightManagementMethodsUseRealCoreAndExactGatewayRoutes() throws Exception {
        Session admin = initialize();
        String policies = "/api/v1/quotas/policies";
        var policy = expect(call("POST", policies, admin.key(), body(Map.of("scopeType", "EMPLOYEE_WEEK",
            "scopeId", 0, "weeklyLimitPoints", "20.000000", "effectiveFrom", "2026-01-01T00:00:00Z"))), 201);
        assertEquals("DRAFT", policy.get("status").asString());
        expect(call("POST", policies + "/" + policy.get("id").asLong() + "/publish", admin.key(),
            "{\"expectedRevision\":1}"), 200);
        expect(call("POST", policies + "/" + policy.get("id").asLong() + "/publish", admin.key(),
            "{\"expectedRevision\":1}"), 409);
        assertEquals(1, expect(call("GET", policies + "?scopeType=EMPLOYEE_WEEK&scopeId=0&status=ACTIVE",
            admin.key(), null), 200).get("total").asLong());
        var quotas = expect(call("GET", "/api/v1/employees/" + admin.employeeId() + "/quotas", admin.key(), null), 200);
        assertTrue(quotas.get("employeeWeekly").isNull());
        assertEquals(0, quotas.get("agentWeekly").size());
        String budget = "/api/v1/provider-budgets/USD/2026-10";
        var created = expect(call("PUT", budget, admin.key(), budget(null)), 201);
        assertEquals("0.10000000", created.get("limitAmount").asString());
        assertEquals(created, expect(call("GET", budget, admin.key(), null), 200));
        assertEquals(1,
            expect(call("GET", "/api/v1/provider-budgets?currency=USD&month=2026-10", admin.key(), null), 200)
                .get("total").asLong());
        expect(call("GET", "/api/v1/finance-operations/nonexistent-operation", admin.key(), null), 404);
        expect(call("GET", "/api/v1/provider-budgets/EUR/2026-10", admin.key(), null), 404);
        expect(call("PUT", budget, admin.key(), budget(null)), 409);
        expect(call("PUT", budget, admin.key(), budget(0L)), 200);
        expect(call("PUT", budget, admin.key(), budget(0L)), 409);
        for (String path : List.of("/api/v1/models/1/routing-policies", "/api/v1/models/1/routing-policies/1/publish",
            "/internal/v1/finance/reserve", "/internal/v1/finance/settle", "/v1/chat/completions")) {
            expect(call("POST", path, admin.key(), "{}"), 404);
        }
        expect(call("POST", "/api/v1/finance-operations/simulation-operation", admin.key(), "{}"), 405);
        assertEquals(0, scalar("SELECT COUNT(*) FROM finance_operation"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM outbox_event"));
        assertFalse(created.toString().contains("password"));
    }

    @Test
    @DisplayName("P4 RBAC：普通员工管理Key、运行Key、svc/bst均不能取得管理员资金权限")
    void currentAdministratorAuthorityProtectsEveryFinanceResource() throws Exception {
        Session admin = initialize();
        long employee = employee(admin, admin.teamId(), "FINANCE_READER");
        String key = issue(admin, employee, "finance-reader-credential", expiry()).get("key").asString();
        for (String path : List.of("/api/v1/quotas/policies", "/api/v1/provider-budgets",
            "/api/v1/provider-budgets/USD/2026-10", "/api/v1/employees/" + employee + "/quotas",
            "/api/v1/finance-operations/nonexistent-operation")) {
            expect(call("GET", path, key, null), 403);
            expect(call("GET", path, bootstrapToken, null), 401);
            expect(call("GET", path, serviceToken, null), 401);
            expect(call("GET", path, null, null), 401);
        }
        expect(call("PUT", "/api/v1/provider-budgets/USD/2026-10", key, budget(null)), 403);
        expect(call("POST", "/api/v1/quotas/policies", key,
            "{\"scopeType\":\"EMPLOYEE_WEEK\",\"scopeId\":0,\"weeklyLimitPoints\":\"1.000000\","
                + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\"}"),
            403);
        assertEquals(0, scalar("SELECT COUNT(*) FROM provider_spend_budget"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM quota_policy"));
    }

    @Test
    @DisplayName("P4 API：真实HTTP拒绝非法精度、重复键/参数、未知字段及不规范月份")
    void invalidRequestsCannotPartiallyWriteBudgetOrAudit() throws Exception {
        Session admin = initialize();
        long audits = scalar("SELECT COUNT(*) FROM admin_audit_event");
        for (String bad : List.of(budget(null).replace("\"0.10000000\"", "0.1"),
            budget(null).replace("0.10000000", "1e-1"), budget(null).replace("0.10000000", "-0.10000000"),
            budget(null).replace("\"0.10000000\"", "null"),
            budget(null).replace("0.10000000", "0.100000000"),
            budget(null).replace("\"normalLimitAmount\"", "\"unknown\""),
            budget(null).replace("0.08000000", "0.09000000"),
            budget(null).replace("null", "-1"), budget(null).replace("null", "1.0"),
            budget(null).replace("{", "{\"limitAmount\":\"0.10000000\","))) {
            int status = call("PUT", "/api/v1/provider-budgets/USD/2026-10", admin.key(), bad).status();
            assertTrue(status == 400 || status == 409);
        }
        expect(call("PUT", "/api/v1/provider-budgets/USD/2026-1", admin.key(), budget(null)), 400);
        expect(call("GET", "/api/v1/provider-budgets?page=1&page=2", admin.key(), null), 400);
        expect(call("GET", "/api/v1/provider-budgets?unknown=x", admin.key(), null), 400);
        expect(call("GET", "/api/v1/provider-budgets?size=101", admin.key(), null), 400);
        expect(call("GET", "/api/v1/employees/1/quotas?at=invalid", admin.key(), null), 400);
        expect(call("GET", "/api/v1/finance-operations/short", admin.key(), null), 400);
        assertEquals(audits, scalar("SELECT COUNT(*) FROM admin_audit_event"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM provider_spend_partition"));
    }

    @Test
    @DisplayName("P4 CAS和审计：并发更新只有一个提交，审计失败使父预算/分区全部回滚")
    void concurrentCasAndAuditFailureKeepBudgetAtomic() throws Exception {
        Session admin = initialize();
        String path = "/api/v1/provider-budgets/USD/2026-10";
        expect(call("PUT", path, admin.key(), budget(null)), 201);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<Integer>) () -> {
                start.await();
                return call("PUT", path, admin.key(), budget(0L)).status();
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            assertEquals(List.of(200, 409), java.util.stream.Stream.of(first.get(), second.get()).sorted().toList());
        }
        sql("CREATE TRIGGER reject_budget_audit BEFORE INSERT ON admin_audit_event FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try {
            expect(call("PUT", path, admin.key(), budget(1L).replace("0.10000000", "0.20000000")), 503);
            expect(call("PUT", "/api/v1/provider-budgets/EUR/2026-10", admin.key(), budget(null)), 503);
            assertEquals(1, scalar("SELECT COUNT(*) FROM provider_spend_budget"));
            assertEquals(2, scalar("SELECT COUNT(*) FROM provider_spend_partition"));
            assertEquals(1, scalar("SELECT version FROM provider_spend_budget"));
        } finally {
            sql("DROP TRIGGER reject_budget_audit");
        }
        expect(call("PUT", path, admin.key(), budget(1L)), 200);
    }
}
