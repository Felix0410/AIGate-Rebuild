package com.felix.aigatecore.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 封闭Schema、定点价格及全部DNS答案的边界测试，解析器受控且不发Provider HTTP。
 *
 * @author AIGate
 */
class CatalogValidationTests {

    /** 构造合法模型能力，后续逐项变异以验证缺失/未知/类型边界。 */
    private ObjectNode capability() {
        return (ObjectNode) CatalogValidation.json("{\"schemaVersion\":1,\"chatCompletions\":true,\"streaming\":true,"
            + "\"toolCalling\":false,\"maxInputTokens\":4096,\"maxOutputTokens\":1024,\"maxTotalTokens\":4096}");
    }

    /** 构造声明策略；私网声明仍需要独立审批才能在业务层激活。 */
    private ObjectNode policy(boolean privateNetwork) {
        return (ObjectNode) CatalogValidation.json("{\"schemaVersion\":1,\"allowedHostnames\":[\"api.example.com\"],"
            + "\"allowedPorts\":[443],\"tlsRequired\":true,\"allowPrivateNetwork\":" + privateNetwork
            + ",\"approvedPrivateCidrs\":" + (privateNetwork ? "[\"10.20.0.0/16\"]" : "[]") + "}");
    }

    /**
     * 必填字段逐一移除或变异，不能只验证单一正常样例。
     */
    @Test
    @DisplayName("P3-07、D47-API17：能力Schema拒绝缺失、null、未知字段、类型转换和不支持工具")
    void capabilityRejectsEveryMissingOrAmbiguousField() {
        for (String field : capability().propertyNames()) {
            ObjectNode missing = capability();
            missing.remove(field);
            assertThrows(ApiException.class, () -> CatalogValidation.capability(missing));
            ObjectNode nullValue = capability();
            nullValue.putNull(field);
            assertThrows(ApiException.class, () -> CatalogValidation.capability(nullValue));
        }
        for (ObjectNode invalid : List.of(capability().put("toolCalling", true), capability().put("schemaVersion", "1"),
            capability().put("maxInputTokens", 4097), capability().put("maxOutputTokens", 0),
            capability().put("maxTotalTokens", 2_000_001), capability().put("maxTotalTokens", 4096.0),
            capability().put("extra", true))) {
            assertThrows(ApiException.class, () -> CatalogValidation.capability(invalid));
        }
        assertEquals(4096, CatalogValidation.capability(capability()).maxTotalTokens());
    }

    /**
     * 金额必须以固定精度字符串无损进入DECIMAL，秘密必须保留原字节。
     */
    @Test
    @DisplayName("P3-25、37：固定6/8位价格和UTF-8秘密字节边界严格校验")
    void pricesAndRawSecretsNeverCoerceRoundOrTrim() {
        for (int scale : List.of(6, 8)) {
            String max = "9".repeat(20 - scale) + "." + "9".repeat(scale);
            assertEquals(max, CatalogValidation.decimal(max, scale).toPlainString());
            for (String invalid : List.of("-1." + "0".repeat(scale), "1e2", "1.0", "NaN", " 1." + "0".repeat(scale),
                "0" + max, "9" + max, "1." + "0".repeat(scale + 1))) {
                assertThrows(ApiException.class, () -> CatalogValidation.decimal(invalid, scale));
            }
        }
        String raw = "  fake-provider-secret\n";
        var body = ValidatedBody.parse(("{\"secret\":\"  fake-provider-secret\\n\"}")
            .getBytes(StandardCharsets.UTF_8), "secret");
        assertTrue(raw.equals(body.rawText("secret", 4096)));
        assertThrows(ApiException.class, () -> ValidatedBody.parse("{\"secret\":\"\\uD800\"}"
            .getBytes(StandardCharsets.UTF_8), "secret").rawText("secret", 4096));
        assertEquals("MODEL-" + "A".repeat(90), CatalogValidation.code("MODEL-" + "A".repeat(90), 96));
        assertThrows(ApiException.class, () -> CatalogValidation.code("MODEL-" + "A".repeat(91), 96));
    }

    /**
     * HostPolicy只接受准确FQDN和受控私网，不把通配符或元数据地址当允许范围。
     */
    @Test
    @DisplayName("P3-13、14、D47-API15：HostPolicy封闭、域名规范化及私网CIDR边界")
    void hostPolicyRejectsUnknownUnsafeOrOverbroadClaims() {
        for (String host : List.of("127.0.0.1", "localhost", "*.example.com", "example.com.", "http://example.com",
            "user@example.com", "example.com/path", " api.example.com", "[::1]")) {
            ObjectNode invalid = policy(false);
            invalid.putArray("allowedHostnames").add(host);
            assertThrows(ApiException.class, () -> CatalogValidation.hostPolicy(invalid, "THIRD_PARTY_API"));
        }
        for (String cidr : List.of("0.0.0.0/0", "::/0", "169.254.169.254/32", "127.0.0.0/8", "100.100.100.200/32",
            "10.0.0.0/7", "host.example.com/24", "10.1/16", "10.1.2.03/24", "fe80::/10", "fc00::%eth0/7")) {
            assertThrows(ApiException.class, () -> CatalogEndpointValidator.privateCidr(cidr));
        }
        assertEquals("10.20.0.0/16", CatalogEndpointValidator.privateCidr("10.20.1.2/16"));
        assertThrows(ApiException.class, () -> CatalogValidation.hostPolicy(policy(true), "THIRD_PARTY_API"));
        ObjectNode wrongPort = policy(false);
        wrongPort.putArray("allowedPorts").add(80);
        assertThrows(ApiException.class, () -> CatalogValidation.hostPolicy(wrongPort, "THIRD_PARTY_API"));
        assertThrows(ApiException.class,
            () -> CatalogValidation.hostPolicy(policy(false).put("extra", true), "SELF_HOSTED"));
        ObjectNode normalized = policy(false);
        normalized.putArray("allowedHostnames").add("API.EXAMPLE.COM").add("api.example.com");
        assertEquals(1, CatalogValidation.hostPolicy(normalized, "THIRD_PARTY_API").get("allowedHostnames").size());
    }

    /**
     * 不使用宿主机真实DNS，以可变解析答案验证重绑定和混合A/AAAA失败关闭。
     */
    @Test
    @DisplayName("P3-14、15：每次校验重新解析全部地址，重绑定/混合危险答案失败关闭")
    void endpointChecksEveryCurrentDnsAnswerAndNeverFallsBack() throws Exception {
        AtomicReference<InetAddress[]> answers = new AtomicReference<>(
            new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        CatalogEndpointValidator validator = new CatalogEndpointValidator(host -> answers.get());
        JsonNode policy = CatalogValidation.hostPolicy(policy(false), "THIRD_PARTY_API");
        String endpoint = "https://api.example.com/v1/chat/completions";
        assertEquals(endpoint, validator.check(endpoint, policy, "THIRD_PARTY_API"));
        for (String unsafe : List.of("127.0.0.1", "169.254.169.254", "10.20.1.5", "100.100.100.200", "0.0.0.0",
            "198.18.0.1", "224.0.0.1", "168.63.129.16", "::1", "fe80::1", "fc00::1", "2001:db8::1",
            "2001:2::1", "3fff::1")) {
            answers.set(new InetAddress[]{InetAddress.getByName("93.184.216.34"), InetAddress.getByName(unsafe)});
            assertEquals(400,
                assertThrows(ApiException.class, () -> validator.check(endpoint, policy, "THIRD_PARTY_API")).status());
        }
        answers.set(new InetAddress[]{InetAddress.getByName("10.20.1.5")});
        JsonNode privatePolicy = CatalogValidation.hostPolicy(policy(true), "SELF_HOSTED");
        assertEquals(endpoint, validator.check(endpoint, privatePolicy, "SELF_HOSTED"));
        answers.set(new InetAddress[]{InetAddress.getByName("10.21.1.5")});
        assertThrows(ApiException.class, () -> validator.check(endpoint, privatePolicy, "SELF_HOSTED"));
        ObjectNode ipv6Policy = policy(true);
        ipv6Policy.putArray("approvedPrivateCidrs").add("fc00::/7");
        answers.set(new InetAddress[]{InetAddress.getByName("fd00:ec2::254")});
        assertThrows(ApiException.class, () -> validator.check(endpoint,
            CatalogValidation.hostPolicy(ipv6Policy, "SELF_HOSTED"), "SELF_HOSTED"));
        CatalogEndpointValidator unavailable = new CatalogEndpointValidator(host -> {
            throw new IllegalStateException("Controlled test DNS failure");
        });
        assertEquals(503,
            assertThrows(ApiException.class, () -> unavailable.check(endpoint, policy, "THIRD_PARTY_API")).status());
    }

    /**
     * 慢DNS有确定的等待上限，超时不返回上次地址或继续业务。
     */
    @Test
    @DisplayName("P3-15：受控慢DNS在等待上限返回503，不使用陈旧结论")
    void dnsTimeoutFailsWithinBoundedWait() {
        CatalogEndpointValidator validator = new CatalogEndpointValidator(host -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Controlled slow resolver");
        });
        long started = System.nanoTime();
        try {
            assertEquals(503, assertThrows(ApiException.class, () -> validator.check(
                "https://api.example.com/v1/chat/completions", policy(false), "THIRD_PARTY_API")).status());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
        } finally {
            validator.close();
        }
    }

    /**
     * URL解析前先阻断非HTTPS、身份信息、端口和路径歧义。
     */
    @Test
    @DisplayName("P3-13：URL scheme、userinfo、IP、query/fragment与编码绕过全部拒绝")
    void endpointSyntaxRejectsAmbiguousOrSecretBearingUrls() {
        CatalogEndpointValidator validator = new CatalogEndpointValidator(host -> new InetAddress[0]);
        JsonNode policy = CatalogValidation.hostPolicy(policy(false), "THIRD_PARTY_API");
        for (String endpoint : List.of("http://api.example.com/v1", "file:///etc/passwd", "gopher://api.example.com/v1",
            "https://api.example.com.evil.test/v1", "https://user:secret@api.example.com/v1",
            "https://127.0.0.1/v1", "https://api.example.com:80/v1", "https://api.example.com/v1?key=secret",
            "https://api.example.com/v1#fragment", "https://api.example.com/%2e%2e/v1",
            "https://api.example.com/../v1")) {
            assertEquals(400,
                assertThrows(ApiException.class, () -> validator.check(endpoint, policy, "THIRD_PARTY_API")).status());
        }
    }
}
