package com.felix.aigatecore.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.node.ObjectNode;

/**
 * 审批证据只来自受保护快照，精确绑定配置、物理文件和固定时间边界。
 *
 * @author AIGate
 */
class CatalogApprovalTests {

    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    @TempDir
    Path directory;

    /** 构造封闭模型能力，不调用真实模型或收费服务。 */
    private LogicalModelDO model() {
        return new LogicalModelDO(5, "MODEL_TEST", "Test", "Family", "v1",
            "{\"schemaVersion\":1,\"chatCompletions\":true,\"streaming\":true,\"toolCalling\":false,"
                + "\"maxInputTokens\":4096,\"maxOutputTokens\":1024,\"maxTotalTokens\":4096}",
            "ACTIVE", null, null, 0, NOW, NOW);
    }

    /** 大小写和域名顺序由生产规范算法归一化。 */
    private ProviderDO provider(String host) {
        return new ProviderDO(3, "PROVIDER_TEST", "Test", "THIRD_PARTY_API", "ACTIVE",
            "{\"schemaVersion\":1,\"allowedHostnames\":[\"" + host + "\"],\"allowedPorts\":[443],"
                + "\"tlsRequired\":true,\"allowPrivateNetwork\":false,\"approvedPrivateCidrs\":[]}",
            0, NOW, NOW);
    }

    /** 适配对象包含服务端ID；摘要必须排除此ID但保持真实模型身份。 */
    private ObjectNode adapter() {
        return (ObjectNode) CatalogValidation.json("{\"deploymentId\":7,\"adapterKind\":\"OPENAI_CHAT_TEXT_V1\","
            + "\"remoteModelIdentity\":\"MODEL_TEST@v1\",\"tokenizerId\":\"test\",\"tokenizerRevision\":\"v1\","
            + "\"inputBoundMethod\":\"VERIFIED_TOKENIZER\",\"maxInputTokens\":4096,\"maxOutputTokens\":1024,"
            + "\"maxTotalTokens\":4096,\"supportsJson\":true,"
            + "\"supportsStreaming\":true,\"supportsFinalStreamUsage\":true,"
            + "\"usageEvidenceMode\":\"VERIFIED_UPSTREAM_USAGE\","
            + "\"billingCategories\":[\"INPUT_TOKENS\",\"OUTPUT_TOKENS\"],"
            + "\"cachedInputMode\":\"NO_CACHE\",\"extraChargesPossible\":false,\"providerSupportsIdempotency\":false,"
            + "\"compatibilityRevision\":\"cap-v1\","
            + "\"verifiedAt\":\"2026-10-25T00:00:00Z\",\"evidenceRef\":\"TEST-1\"}");
    }

    /** 构造尚未激活的候选，批准与状态分别校验。 */
    private DeploymentDO deployment(ObjectNode adapter, String remoteModel) {
        return new DeploymentDO(7, 5, 3, "DEPLOY_TEST", "https://api.example.com/v1/chat/completions", remoteModel,
            "THIRD_PARTY_API", "TEST", "cap-v1", adapter.toString(), "TEST-1", "PER_TOKEN_API", "DRAFT",
            100, 1, 2, null, null, null, 0, NOW, NOW);
    }

    /** 生成不含任何ProviderSecret的批准条目。 */
    private ObjectNode approval() {
        return CatalogValidation.newObject().put("approvalRef", "SEC-TEST")
            .put("configDigest",
                CatalogApprovals.digest(provider("api.example.com"), model(), deployment(adapter(), "remote-test"),
                    NOW))
            .put("approvedBy", "independent-security-test").put("approvedAt", NOW.minusSeconds(10).toString())
            .put("validUntil", NOW.plusSeconds(3600).toString()).put("allowNoAuth", false);
    }

    /** 按场景写入临时受保护文件，不读写开发环境审批清单。 */
    private CatalogApprovals load(ObjectNode entry, Clock clock) throws Exception {
        Path file = directory.resolve("approval.json");
        ObjectNode root = CatalogValidation.newObject().put("schemaVersion", 1);
        root.putArray("approvals").add(entry);
        Files.writeString(file, root.toString());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return new CatalogApprovals(
            new MockEnvironment().withProperty("AIGATE_CATALOG_APPROVALS_FILE", file.toString()), clock);
    }

    /**
     * 无清单和只有自报证据时不能放行，字段顺序和域名规范写法不影响合法摘要。
     */
    @Test
    @DisplayName("P3-33～35：独立审批精确绑定配置，自报证据和相近配置不能放行")
    void approvalMatchesExactCanonicalConfigurationOnly() throws Exception {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DeploymentDO deployment = deployment(adapter(), "remote-test");
        assertNull(
            new CatalogApprovals(new MockEnvironment(), clock).find(provider("api.example.com"), model(), deployment));
        CatalogApprovals approvals = load(approval(), clock);
        assertNotNull(approvals.find(provider("api.example.com"), model(), deployment));
        assertEquals(CatalogApprovals.digest(provider("api.example.com"), model(), deployment, NOW),
            CatalogApprovals.digest(provider("API.EXAMPLE.COM"), model(), deployment, NOW));
        assertNull(approvals.find(provider("api.example.com"), model(), deployment(adapter(), "different-remote")));
        assertNotEquals(CatalogApprovals.digest(provider("api.example.com"), model(), deployment, NOW),
            CatalogApprovals.digest(provider("api.example.com"), model(), deployment(adapter(), "different-remote"),
                NOW));
        ObjectNode create = adapter();
        assertThrows(com.felix.aigatecore.web.ApiException.class,
            () -> CatalogValidation.adapter(create, model(), "THIRD_PARTY_API", "cap-v1", "TEST-1", NOW, false));
        create.remove("deploymentId");
        assertEquals("MODEL_TEST@v1",
            CatalogValidation.adapter(create, model(), "THIRD_PARTY_API", "cap-v1", "TEST-1", NOW, false)
                .get("remoteModelIdentity").asString());
        for (String field : List.of("tokenizerId", "tokenizerRevision", "evidenceRef", "maxTotalTokens")) {
            ObjectNode missing = create.deepCopy();
            missing.remove(field);
            assertThrows(com.felix.aigatecore.web.ApiException.class,
                () -> CatalogValidation.adapter(missing, model(), "THIRD_PARTY_API", "cap-v1", "TEST-1", NOW, false));
        }
        for (ObjectNode invalid : List.of(create.deepCopy().put("extraChargesPossible", true),
            create.deepCopy().put("maxInputTokens", 4097), create.deepCopy().put("supportsJson", false),
            create.deepCopy().put("compatibilityRevision", "wrong-revision"),
            create.deepCopy().put("usageEvidenceMode", "VERIFIED_SELF_HOSTED_USAGE"))) {
            assertThrows(com.felix.aigatecore.web.ApiException.class,
                () -> CatalogValidation.adapter(invalid, model(), "THIRD_PARTY_API", "cap-v1", "TEST-1", NOW, false));
        }

    }

    /**
     * 截止前1us有效，到边界失效；撤回后新快照不能沿用旧批准。
     */
    @Test
    @DisplayName("P3-35：固定Clock精确截止和重启撤回立即改变后续检查")
    void approvalExpiresAtExactMicrosecondAndWithdrawalChangesSnapshot() throws Exception {
        ObjectNode entry = approval().put("validUntil", NOW.toString());
        CatalogApprovals before = load(entry, Clock.fixed(NOW.minus(1, ChronoUnit.MICROS), ZoneOffset.UTC));
        assertNotNull(before.find(provider("api.example.com"), model(), deployment(adapter(), "remote-test")));
        CatalogApprovals deadline = load(entry, Clock.fixed(NOW, ZoneOffset.UTC));
        assertNull(deadline.find(provider("api.example.com"), model(), deployment(adapter(), "remote-test")));
        Files.writeString(directory.resolve("approval.json"), "{\"schemaVersion\":1,\"approvals\":[]}");
        CatalogApprovals removed = new CatalogApprovals(
            new MockEnvironment().withProperty("AIGATE_CATALOG_APPROVALS_FILE",
                directory.resolve("approval.json").toString()),
            Clock.fixed(NOW, ZoneOffset.UTC));
        assertNull(removed.find(provider("api.example.com"), model(), deployment(adapter(), "remote-test")));
    }

    /**
     * 不能用未知字段、未来批准或比验证证据更早的批准伪造完整技术审查。
     */
    @Test
    @DisplayName("P3-36：封闭Manifest和审批时间校验，格式错误不进入就绪检查")
    void manifestRejectsUnknownMissingAndInvalidApprovalFields() throws Exception {
        for (ObjectNode invalid : List.of(approval().put("extra", true), approval().putNull("approvedBy"),
            approval().put("approvedAt", NOW.plusSeconds(1).toString()), approval().put("validUntil", "bad"),
            approval().put("configDigest", "untrusted"))) {
            assertThrows(IllegalStateException.class, () -> load(invalid, Clock.fixed(NOW, ZoneOffset.UTC)));
        }
        ObjectNode tooEarly = approval().put("approvedAt", "2026-10-24T00:00:00Z");
        assertNull(load(tooEarly, Clock.fixed(NOW, ZoneOffset.UTC)).find(provider("api.example.com"), model(),
            deployment(adapter(), "remote-test")));
    }

    /**
     * 校验真实文件属性，不能把可公开写入或符号链接文件当审批信任根。
     */
    @Test
    @DisplayName("P3-36：重复摘要、group/other写权限和符号链接文件拒绝启动")
    void manifestRejectsDuplicateApprovalsAndUnprotectedFiles() throws Exception {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        load(approval(), clock);
        Path file = directory.resolve("approval.json");
        MockEnvironment env = new MockEnvironment().withProperty("AIGATE_CATALOG_APPROVALS_FILE", file.toString());
        ObjectNode root = CatalogValidation.newObject().put("schemaVersion", 1);
        root.putArray("approvals").add(approval()).add(approval());
        Files.writeString(file, root.toString());
        assertThrows(IllegalStateException.class, () -> new CatalogApprovals(env, clock));
        load(approval(), clock);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));
        assertThrows(IllegalStateException.class, () -> new CatalogApprovals(env, clock));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Path link = directory.resolve("link.json");
        Files.createSymbolicLink(link, file);
        assertThrows(IllegalStateException.class, () -> new CatalogApprovals(new MockEnvironment()
            .withProperty("AIGATE_CATALOG_APPROVALS_FILE", link.toString()), clock));
    }
}
