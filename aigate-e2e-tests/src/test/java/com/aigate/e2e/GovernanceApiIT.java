package com.aigate.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** P6真实Gateway/Core双JVM、MySQL状态机与角色隔离；目录数据明确仅为合成夹具。 */
class GovernanceApiIT extends AbstractIdentityIntegrationTest {
    private static final String BASE = "/api/v1/governance";

    private record Fixture(Session admin, long editor, String editorKey, long approver, String approverKey) {
    }

    private Fixture fixture() throws Exception {
        var admin = initialize();
        long editor = employee(admin, admin.teamId(), "GOV_EDITOR");
        long approver = employee(admin, admin.teamId(), "GOV_APPROVER");
        expect(call("PUT", role(editor, "POLICY_EDITOR", "GLOBAL", 0), admin.key(), null), 201);
        expect(call("PUT", role(approver, "SECURITY_APPROVER", "GLOBAL", 0), admin.key(), null), 201);
        String editorKey = issue(admin, editor, "gov-editor-key", expiry()).get("key").asString();
        String approverKey = issue(admin, approver, "gov-approver-key", expiry()).get("key").asString();
        return new Fixture(admin, editor, editorKey, approver, approverKey);
    }

    private ObjectNode policy(Long base) throws Exception {
        var node = (ObjectNode) json.readTree(Files.readString(
            projectRoot.resolve("config/test-fixtures/governance-policy.json")));
        if (base == null) {
            node.putNull("baseRevision");
        } else {
            node.put("baseRevision", base);
        }
        return node;
    }

    private String revision(long id, String action) {
        return BASE + "/policies/" + id + "/" + action;
    }

    private String review(long version) {
        return body(Map.of("decision", "APPROVE", "reasonCode", "SYNTHETIC_REVIEW", "expectedVersion", version));
    }

    private String publish(long version, Long active) {
        return "{\"expectedPolicyVersion\":" + version + ",\"expectedActiveRevisionId\":" + active + "}";
    }

    private long approved(Fixture f, Long base) throws Exception {
        long id = expect(call("POST", BASE + "/policies/drafts", f.editorKey(), policy(base).toString()), 201)
            .get("revisionId").asLong();
        expect(call("POST", revision(id, "submit"), f.editorKey(), null), 200);
        expect(call("POST", revision(id, "review"), f.approverKey(), review(1)), 200);
        return id;
    }

    private long published(Fixture f) throws Exception {
        long id = approved(f, null);
        expect(call("POST", revision(id, "publish"), f.approverKey(), publish(0, null)), 200);
        return id;
    }

    /** 只为管理接口建立外键，不能作为P3技术批准或真实Provider能力证据。 */
    private void catalog(Fixture f) throws Exception {
        long owner = f.admin().employeeId();
        sql("INSERT INTO agent(id,application_code,display_name,owner_team_id,created_by_employee_id,"
            + "created_at,updated_at) VALUES(1,'GOV_TEST','Synthetic'," + f.admin().teamId() + "," + owner
            + ",UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        sql("INSERT INTO provider(id,code,name,hosting_mode,base_url_host_policy,created_at,updated_at) "
            + "VALUES(1,'GOV_TEST','Synthetic','THIRD_PARTY_API','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        sql("INSERT INTO logical_model(id,model_code,display_name,model_family,immutable_model_revision,"
            + "capability_profile_json,created_at,updated_at) VALUES(1,'GOV_TEST','Synthetic','test','v1','{}',"
            + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        sql("INSERT INTO model_deployment(id,logical_model_id,provider_id,deployment_code,endpoint_uri,"
            + "remote_model_name,hosting_mode,region_code,capability_revision,adapter_compatibility_json,"
            + "adapter_evidence_ref,cost_model,status,max_concurrent,created_at,updated_at) "
            + "VALUES(1,1,1,'GOV_TEST','https://synthetic.invalid','test','THIRD_PARTY_API','TEST','v1','{}',"
            + "'test-only','PER_TOKEN_API','ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        sql("INSERT INTO agent_model_permission(agent_id,logical_model_id,granted_by,granted_at,created_at,updated_at)"
            + " VALUES(1,1," + owner + ",UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        for (long employee : List.of(f.editor(), f.approver())) {
            sql("INSERT INTO employee_agent_permission(employee_id,agent_id,granted_by_employee_id,granted_at,"
                + "created_at,updated_at) VALUES(" + employee + ",1," + owner
                + ",UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        }
    }

    private String exception(String until, String rule) {
        return body(Map.of("agentId", 1, "modelCode", "GOV_TEST", "ruleCode", rule,
            "reasonCode", "SYNTHETIC_FALSE_POSITIVE", "validUntil", until));
    }

    @Test
    @DisplayName("BIZP6-02/03：双角色自审拒绝、真实并发双CAS发布、旧配置不可变与安全GET")
    void independentReviewAndConcurrentPublishUseCurrentParentVersion() throws Exception {
        var f = fixture();
        expect(call("POST", BASE + "/policies/drafts", f.admin().key(), policy(null).toString()), 403);
        expect(call("PUT", role(f.editor(), "SECURITY_APPROVER", "GLOBAL", 0), f.admin().key(), null), 201);
        long first = expect(call("POST", BASE + "/policies/drafts", f.editorKey(), policy(null).toString()), 201)
            .get("revisionId").asLong();
        var empty = expect(call("GET", BASE + "/policies/active", f.editorKey(), null), 200);
        assertEquals("NOT_CONFIGURED", empty.get("status").asString());
        assertEquals(0, empty.get("policyVersion").asLong());
        expect(call("POST", revision(first, "submit"), f.editorKey(), null), 200);
        expect(call("POST", revision(first, "review"), f.editorKey(), review(1)), 403);
        expect(call("POST", revision(first, "review"), f.admin().key(), review(1)), 403);
        expect(call("POST", revision(first, "review"), f.approverKey(), review(1)), 200);
        expect(call("POST", revision(first, "publish"), f.editorKey(), publish(0, null)), 403);
        long second = approved(f, null);
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> {
                gate.await();
                return call("POST", revision(first, "publish"), f.approverKey(), publish(0, null)).status();
            });
            var b = workers.submit(() -> {
                gate.await();
                return call("POST", revision(second, "publish"), f.approverKey(), publish(0, null)).status();
            });
            gate.countDown();
            assertEquals(List.of(200, 409), java.util.stream.Stream.of(a.get(), b.get()).sorted().toList());
        }
        var active = expect(call("GET", BASE + "/policies/active", f.admin().key(), null), 200);
        assertEquals(1, active.get("policyVersion").asLong());
        assertFalse(active.toString().contains("MARKER"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM governance_policy_revision WHERE status='PUBLISHED'"));
        long third = approved(f, active.get("revisionId").asLong());
        expect(call("POST", revision(third, "publish"), f.approverKey(), publish(1, null)), 409);
        expect(call("POST", revision(third, "publish"), f.approverKey(),
            publish(1, active.get("revisionId").asLong())), 200);
        assertEquals(1, scalar("SELECT COUNT(*) FROM governance_policy_revision WHERE status='SUPERSEDED'"));
        expect(call("DELETE", role(f.approver(), "SECURITY_APPROVER", "GLOBAL", 0), f.admin().key(), null), 204);
        expect(call("POST", revision(third, "review"), f.approverKey(), review(1)), 403);
        expect(call("POST", "/v1/chat/completions", f.admin().key(), "{}"), 404);
        expect(call("POST", BASE + "/scan", f.admin().key(), "{}"), 404);
    }

    @Test
    @DisplayName("P6十二方法：Agent出站批准、当前Deployment边界CAS、本人例外/自审/撤销与安全分页")
    void approvalBoundariesAndScopedExceptionExposeOnlySafeMetadata() throws Exception {
        var f = fixture();
        catalog(f);
        published(f);
        String egress = body(Map.of("agentId", 1, "providerId", 1, "regionCode", "TEST",
            "approvedClassMax", "INTERNAL", "validFrom",
            Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),
            "validUntil", expiry(), "reasonCode", "SYNTHETIC_EGRESS"));
        expect(call("POST", BASE + "/agent-egress-approvals", f.admin().key(), egress), 403);
        expect(call("POST", BASE + "/agent-egress-approvals", f.approverKey(), egress), 201);
        expect(call("POST", BASE + "/agent-egress-approvals", f.approverKey(), egress), 201);
        assertEquals(1, expect(call("GET", BASE + "/agent-egress-approvals?status=ACTIVE&size=1",
            f.editorKey(), null), 200).get("total").asLong());
        assertEquals(1, expect(call("GET", BASE + "/agent-egress-approvals?status=REVOKED",
            f.approverKey(), null), 200).get("total").asLong());
        String boundary = "{\"approvedClassMax\":\"INTERNAL\",\"regionCode\":\"TEST\",\"validUntil\":\""
            + expiry() + "\",\"dataProcessingApproved\":true,\"expectedVersion\":null,"
            + "\"reasonCode\":\"SYNTHETIC_BOUNDARY\"}";
        expect(call("PUT", BASE + "/deployment-boundaries/1", f.approverKey(), boundary), 201);
        expect(call("PUT", BASE + "/deployment-boundaries/1", f.approverKey(), boundary), 409);
        var revoked = expect(call("PUT", BASE + "/deployment-boundaries/1", f.approverKey(),
            boundary.replace("\"expectedVersion\":null", "\"expectedVersion\":0")
                .replace("\"dataProcessingApproved\":true", "\"dataProcessingApproved\":false")),
            200);
        assertEquals("REVOKED", revoked.get("securityApprovalStatus").asString());
        String request = exception(expiry(), "APPROVED_SENSITIVE_MARKERS_V1");
        long id = expect(call("POST", BASE + "/exceptions", f.editorKey(), request, "gov-own-exception"), 201)
            .get("requestId").asLong();
        expect(call("POST", BASE + "/exceptions", f.editorKey(), request, "gov-own-exception"), 409);
        expect(call("PUT", role(f.editor(), "SECURITY_APPROVER", "GLOBAL", 0), f.admin().key(), null), 201);
        expect(call("POST", BASE + "/exceptions/" + id + "/review", f.editorKey(), review(0)), 403);
        expect(call("POST", BASE + "/exceptions/" + id + "/review", f.approverKey(), review(0)), 200);
        String revoke = body(Map.of("reasonCode", "SYNTHETIC_REVOKE", "expectedVersion", 1));
        expect(call("POST", BASE + "/exceptions/" + id + "/revoke", f.approverKey(), revoke), 200);
        expect(call("POST", BASE + "/exceptions/" + id + "/revoke", f.approverKey(), revoke), 409);
        for (String hard : List.of("CREDENTIAL_PREFIX_HARD_DENY_V1", "TRUSTED_CLASSIFICATION_FLOOR_V1", "UNKNOWN")) {
            expect(call("POST", BASE + "/exceptions", f.editorKey(), exception(expiry(), hard), "hard-" + hard), 400);
        }
        var decisions = expect(call("GET", BASE + "/decisions?size=1", f.approverKey(), null), 200);
        assertEquals(0, decisions.get("total").asLong());
        assertFalse(decisions.toString().contains("hmac"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ledger_entry"));
    }

    @Test
    @DisplayName("BIZP6-09：例外过期/绑定旧策略不可批准，普通员工只能为本人可用ACL申请")
    void exceptionsRejectExpiredOrSupersededPolicyAndForeignScopes() throws Exception {
        var f = fixture();
        catalog(f);
        long active = published(f);
        String request = exception(expiry(), "APPROVED_SENSITIVE_MARKERS_V1");
        long id = expect(call("POST", BASE + "/exceptions", f.editorKey(), request, "expired-exception"), 201)
            .get("requestId").asLong();
        sql("UPDATE governance_exception_request SET valid_until='2026-01-01' WHERE id=" + id);
        expect(call("POST", BASE + "/exceptions/" + id + "/review", f.approverKey(), review(0)), 409);
        long stale = expect(call("POST", BASE + "/exceptions", f.editorKey(), request, "stale-exception"), 201)
            .get("requestId").asLong();
        long next = approved(f, active);
        expect(call("POST", revision(next, "publish"), f.approverKey(), publish(1, active)), 200);
        expect(call("POST", BASE + "/exceptions/" + stale + "/review", f.approverKey(), review(0)), 409);
        long ordinary = employee(f.admin(), f.admin().teamId(), "GOV_ORDINARY");
        String key = issue(f.admin(), ordinary, "gov-ordinary-key", expiry()).get("key").asString();
        expect(call("POST", BASE + "/exceptions", key, request, "governance-no-acl"), 403);
        sql("INSERT INTO employee_agent_permission(employee_id,agent_id,granted_by_employee_id,granted_at,created_at,"
            + "updated_at) VALUES(" + ordinary + ",1," + f.admin().employeeId()
            + ",UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        expect(call("POST", BASE + "/exceptions", key, request, "ordinary-own"), 201);
        expect(call("POST", BASE + "/exceptions", key,
            request.replace("\"agentId\":1", "\"employeeId\":" + f.editor() + ",\"agentId\":1"), "governance-spoof"),
            400);
        expect(call("GET", BASE + "/decisions", key, null), 403);
    }

    @Test
    @DisplayName("D51-GOV01/02：严格JSON/规则/矩阵/角色范围，重复Header/Query和错误凭证均拒绝")
    void malformedPoliciesAndRequestsCannotCreateGovernanceState() throws Exception {
        var f = fixture();
        String valid = policy(null).toString();
        for (String invalid : List.of(valid.replace("TRUSTED_CLASSIFICATION_FLOOR_V1", "UNKNOWN"),
            valid.replace("\"ignoreCase\":true", "\"ignoreCase\":true,\"regex\":\"(a+)+$\""),
            valid.replace("TEST-CONFIDENTIAL-MARKER", "mgt_" + "A".repeat(43)),
            valid.replace("\"classificationRules\":[", "\"extra\":true,\"classificationRules\":["),
            valid.replace("\"baseRevision\":null", "\"baseRevision\":\"0\""),
            valid.replace("\"exemptible\":false", "\"exemptible\":null"),
            valid.replace("\"maxBufferedBytes\":262144", "\"maxBufferedBytes\":262145"))) {
            expect(call("POST", BASE + "/policies/drafts", f.editorKey(), invalid), 400);
        }
        assertEquals(0, scalar("SELECT COUNT(*) FROM governance_policy_revision"));
        expect(call("PUT", role(f.editor(), "POLICY_EDITOR", "TEAM", f.admin().teamId()), f.admin().key(), null), 400);
        expect(call("PUT", role(f.editor(), "SECURITY_APPROVER", "TEAM", f.admin().teamId()), f.admin().key(), null),
            400);
        for (String key : List.of(serviceToken, bootstrapToken)) {
            expect(call("POST", BASE + "/policies/drafts", key, valid), 401);
        }
        expect(request(corePort, "POST", BASE + "/policies/drafts", f.editorKey(), valid, Map.of()), 401);
        expect(call("GET", BASE + "/agent-egress-approvals?page=1&page=2", f.editorKey(), null), 400);
        expect(call("GET", BASE + "/decisions?from=2026-01-01T00:00:00Z&to=2026-10-01T00:00:00Z",
            f.editorKey(), null), 400);
        var duplicate = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + BASE + "/exceptions"))
            .header("Authorization", "Bearer " + f.editorKey()).header("Content-Type", "application/json")
            .header("Idempotency-Key", "one").header("Idempotency-Key", "two")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(400, client.send(duplicate, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    @DisplayName("P6真实审计写入失败：草稿、Review及Publish全部回滚，无半发布指针")
    void auditFailureAndDamagedRulesNeverCommitPartialPublication() throws Exception {
        var f = fixture();
        String trigger = "CREATE TRIGGER reject_governance_audit BEFORE INSERT ON admin_audit_event FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'";
        sql(trigger);
        try {
            expect(call("POST", BASE + "/policies/drafts", f.editorKey(), policy(null).toString()), 503);
            assertEquals(0, scalar("SELECT COUNT(*) FROM governance_policy"));
            assertEquals(0, scalar("SELECT COUNT(*) FROM governance_rule"));
        } finally {
            sql("DROP TRIGGER reject_governance_audit");
        }
        long id = expect(call("POST", BASE + "/policies/drafts", f.editorKey(), policy(null).toString()), 201)
            .get("revisionId").asLong();
        sql("UPDATE governance_rule SET enabled=0 WHERE revision_id=" + id
            + " AND rule_code='CREDENTIAL_PREFIX_HARD_DENY_V1'");
        expect(call("POST", revision(id, "submit"), f.editorKey(), null), 409);
        sql("UPDATE governance_rule SET enabled=1 WHERE revision_id=" + id
            + " AND rule_code='CREDENTIAL_PREFIX_HARD_DENY_V1'");
        expect(call("POST", revision(id, "submit"), f.editorKey(), null), 200);
        sql(trigger);
        try {
            expect(call("POST", revision(id, "review"), f.approverKey(), review(1)), 503);
            assertEquals(1, scalar("SELECT COUNT(*) FROM governance_policy_revision WHERE status='UNDER_REVIEW'"));
        } finally {
            sql("DROP TRIGGER reject_governance_audit");
        }
        expect(call("POST", revision(id, "review"), f.approverKey(), review(1)), 200);
        sql(trigger);
        try {
            expect(call("POST", revision(id, "publish"), f.approverKey(), publish(0, null)), 503);
            assertEquals(0, scalar("SELECT version FROM governance_policy"));
            assertEquals(1, scalar("SELECT COUNT(*) FROM governance_policy WHERE active_revision_id IS NULL"));
        } finally {
            sql("DROP TRIGGER reject_governance_audit");
        }
        sql("UPDATE governance_rule SET enabled=0 WHERE revision_id=" + id
            + " AND rule_code='CREDENTIAL_PREFIX_HARD_DENY_V1'");
        expect(call("POST", revision(id, "publish"), f.approverKey(), publish(0, null)), 409);
        sql("UPDATE governance_rule SET enabled=1 WHERE revision_id=" + id
            + " AND rule_code='CREDENTIAL_PREFIX_HARD_DENY_V1'");
        expect(call("POST", revision(id, "publish"), f.approverKey(), publish(0, null)), 200);
    }

    @Test
    @DisplayName("P6真实启动：缺失、复用其他用途或超预算配置均阻止Core启动，Gateway无治理密钥")
    void coreRequiresIndependentAuditMaterialAndBoundedScannerConfiguration() throws Exception {
        stop(core);
        try {
            for (var overrides : List.of(Map.of("AIGATE_GOVERNANCE_AUDIT_HMAC_KEY", ""),
                Map.of("AIGATE_GOVERNANCE_AUDIT_HMAC_KEY", providerEncryptionKey),
                Map.of("AIGATE_GOVERNANCE_SCANNER_BUDGET_MS", "201"))) {
                var failed = process("aigate-core", coreEnvironment("INIT", overrides));
                try {
                    assertTrue(failed.waitFor(30, TimeUnit.SECONDS));
                    assertTrue(failed.exitValue() != 0);
                } finally {
                    stop(failed);
                }
            }
        } finally {
            startCore("INIT", Map.of());
        }
        expect(call("GET", "/api/v1/me/profile", null, null), 401);
    }
}
