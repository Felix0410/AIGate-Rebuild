package com.felix.aigatecore.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.felix.aigatecore.security.CatalogSecuritySettings;
import com.felix.aigatecore.security.RuntimeSecuritySettings;
import com.felix.aigatecore.security.SecuritySettings;
import com.felix.aigatecore.web.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** P6纯CPU规则/边界测试；不替代真实MySQL/HTTP/锁序验证。 */
class GovernanceScannerTests {
    private final JsonMapper json = JsonMapper.builder().build();

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) json.readTree(Files.readString(Path.of("../config/test-fixtures/governance-policy.json")));
    }

    private RuleRegistry.Compiled compile(ObjectNode node) {
        return RuleRegistry.compile(node.get("classificationRules"), node.get("egressMatrix"),
            node.get("outputReviewRules"));
    }

    private GovernanceSettings settings() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new GovernanceSettings(
            new MockEnvironment().withProperty("AIGATE_GOVERNANCE_AUDIT_HMAC_KEY_ID", "test-v1")
                .withProperty("AIGATE_GOVERNANCE_AUDIT_HMAC_KEY", Base64.getEncoder().encodeToString(bytes)),
            mock(SecuritySettings.class), mock(RuntimeSecuritySettings.class), mock(CatalogSecuritySettings.class));
    }

    private byte[] input(String role, String text) {
        return json
            .writeValueAsBytes(java.util.Map.of("messages", List.of(java.util.Map.of("role", role, "content", text)),
                "stream", false));
    }

    @Test
    @DisplayName("D51-GOV01/02：缺必选规则、未知Code/参数、重复顺序及矩阵硬拒绝均编译失败")
    void compilerOnlyAcceptsClosedRulesAndCompleteSafeMatrix() throws Exception {
        var variants = new java.util.ArrayList<ObjectNode>();
        var missing = fixture();
        ((tools.jackson.databind.node.ArrayNode) missing.get("classificationRules")).remove(1);
        variants.add(missing);
        var unknown = fixture();
        ((ObjectNode) unknown.get("classificationRules").get(1)).put("ruleCode", "UPLOAD_ARBITRARY_REGEX");
        variants.add(unknown);
        var regex = fixture();
        ((ObjectNode) regex.get("classificationRules").get(2).get("safeParameters")).put("regex", "(a+)+$");
        variants.add(regex);
        var disabled = fixture();
        ((ObjectNode) disabled.get("classificationRules").get(0)).put("enabled", false);
        variants.add(disabled);
        var order = fixture();
        ((ObjectNode) order.get("classificationRules").get(1)).put("ruleOrder", 0);
        variants.add(order);
        var incomplete = fixture();
        ((tools.jackson.databind.node.ArrayNode) incomplete.get("egressMatrix")).remove(7);
        variants.add(incomplete);
        var restricted = fixture();
        ((ObjectNode) restricted.get("egressMatrix").get(6)).put("decision", "ALLOW_IF_APPROVED");
        variants.add(restricted);
        var extraOutput = fixture();
        ((ObjectNode) extraOutput.get("outputReviewRules").get(0)).put("maxBufferedBytes", 262145);
        variants.add(extraOutput);
        var credentialMarker = fixture();
        ((ObjectNode) credentialMarker.get("classificationRules").get(2).get("safeParameters").get("markers").get(0))
            .put("matchText", "mgt_" + "A".repeat(43));
        variants.add(credentialMarker);
        var huge = fixture();
        var rules = (tools.jackson.databind.node.ArrayNode) huge.get("classificationRules");
        while (rules.size() <= 64) {
            rules.add(rules.get(0).deepCopy());
        }
        variants.add(huge);
        for (var policy : variants) {
            assertEquals("INVALID_GOVERNANCE_POLICY", assertThrows(ApiException.class, () -> compile(policy)).code());
        }
        assertEquals(4, compile(fixture()).rules().size());
    }

    @Test
    @DisplayName("D51-GOV04：高置信AIGate/Provider凭证检测，不因仅短前缀误报")
    void credentialSignalsAreBoundedAndHighConfidence() throws Exception {
        var scanner = new GovernanceScanner(settings(), () -> 0L);
        var policy = compile(fixture());
        for (String prefix : List.of("agk_", "empk_", "mgt_", "bst_", "svc_")) {
            assertTrue(scanner.input(policy, input("assistant", prefix + "A".repeat(43)), null).secret());
            assertFalse(scanner.input(policy, input("user", prefix + "short"), null).secret());
        }
        for (String secret : List.of("sk-proj-" + "A".repeat(32), "sk-ant-api03-" + "B".repeat(32),
            "AKIA" + "Z".repeat(16), "AIza" + "A".repeat(35))) {
            assertTrue(scanner.input(policy, input("user", secret), null).secret());
        }
    }

    @Test
    @DisplayName("D51-GOV03/04：扫描所有messages及assistant，可信下界不因用户PUBLIC文本降低")
    void everyMessageAndTrustedFloorContributeToClassification() throws Exception {
        var scanner = new GovernanceScanner(settings(), () -> 0L);
        var policy = compile(fixture());
        var node = json.createObjectNode().put("stream", false);
        var messages = node.putArray("messages");
        messages.addObject().put("role", "system").put("content", "public ordinary text");
        messages.addObject().put("role", "assistant").put("content", "user says PUBLIC; mgt_" + "C".repeat(43));
        var result = scanner.input(policy, json.writeValueAsBytes(node), "CONFIDENTIAL");
        assertEquals(RuleRegistry.DataClass.CONFIDENTIAL, result.floor());
        assertTrue(result.secret());
        assertEquals(RuleRegistry.DataClass.INTERNAL,
            scanner.input(policy, input("user", "ordinary"), "PUBLIC").floor());
    }

    @Test
    @DisplayName("D51-GOV06：UTF8/JSON/Unicode错误、额外字段、消息数和64KiB越界均fail closed")
    void malformedOrOversizedInputNeverBecomesAllow() throws Exception {
        var scanner = new GovernanceScanner(settings(), () -> 0L);
        var policy = compile(fixture());
        for (byte[] invalid : List.of(new byte[]{(byte) 0xc3, (byte) 0x28}, new byte[65537],
            "{\"messages\":[],\"stream\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "{\"messages\":[],\"stream\":false,\"dataClass\":\"PUBLIC\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            input("tool", "unsupported"), input("user", "\uD800"))) {
            assertNotNull(scanner.input(policy, invalid, null).failure());
        }
        var node = json.createObjectNode().put("stream", false);
        var messages = node.putArray("messages");
        for (int i = 0; i < 65; i++) {
            messages.addObject().put("role", "user").put("content", "a");
        }
        assertNotNull(scanner.input(policy, json.writeValueAsBytes(node), null).failure());
    }

    @Test
    @DisplayName("D51-GOV06/07：单调时钟预算耗尽拒绝，输出秘密/大小/UTF8错误不可回送")
    void budgetFailureAndOutputReviewAreConservative() throws Exception {
        var settings = settings();
        var ticks = new AtomicLong();
        var scanner = new GovernanceScanner(settings, () -> ticks.getAndAdd(1_000_000_000L));
        var policy = compile(fixture());
        assertEquals("GOVERNANCE_SCANNER_UNAVAILABLE",
            scanner.input(policy, input("user", "ordinary"), null).failure());
        var bounded = new GovernanceScanner(settings, () -> 0L);
        assertNull(bounded.output(policy, "safe response".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("OUTPUT_LIMIT_EXCEEDED", bounded.output(policy, new byte[262145]));
        assertEquals("OUTPUT_SECRET_DENIED", bounded.output(policy,
            ("mgt_" + "A".repeat(43)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("GOVERNANCE_SCANNER_UNAVAILABLE", bounded.output(policy, new byte[]{(byte) 0xff}));
    }

    @Test
    @DisplayName("P6审计密钥：真实HMAC稳定且有域分隔，缺失/复用材料和超预算配置拒绝")
    void auditHmacIsStableAndCannotReuseOtherKeyPurposes() {
        var settings = settings();
        byte[] input = "synthetic payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(java.util.Arrays.equals(settings.digest(input), settings.digest(input)));
        assertFalse(java.util.Arrays.equals(settings.digest(input), RuleRegistry.hash("synthetic payload")));
        var management = mock(SecuritySettings.class);
        when(management.usesPepper(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        var env = new MockEnvironment().withProperty("AIGATE_GOVERNANCE_AUDIT_HMAC_KEY_ID", "test")
            .withProperty("AIGATE_GOVERNANCE_AUDIT_HMAC_KEY", key);
        assertThrows(IllegalStateException.class, () -> new GovernanceSettings(env, management,
            mock(RuntimeSecuritySettings.class), mock(CatalogSecuritySettings.class)));
        env.withProperty("AIGATE_GOVERNANCE_SCANNER_BUDGET_MS", "201");
        assertThrows(IllegalStateException.class, () -> new GovernanceSettings(env, mock(SecuritySettings.class),
            mock(RuntimeSecuritySettings.class), mock(CatalogSecuritySettings.class)));
    }
}
