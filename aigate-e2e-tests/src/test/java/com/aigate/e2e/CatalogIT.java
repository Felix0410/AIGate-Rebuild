package com.aigate.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * P3真实MySQL、独立Gateway/Core及真实加密回归，审批和DNS仅使用隔离测试输入。
 *
 * @author AIGate
 */
class CatalogIT extends AbstractIdentityIntegrationTest {

    /** 保存管理员及实际创建的目录记录，HTTP请求中的配置只用于构造独立测试批准。 */
    private record Catalog(Session admin, JsonNode provider, JsonNode model, JsonNode deployment,
        JsonNode policy, ObjectNode request) {
    }

    /** 双运行身份仍来自真实P2签发，不能用管理角色代替。 */
    private record RuntimeIdentity(long agent, long employee, String agentKey, String employeeKey) {
    }

    private final Path manifest = outputDirectory.resolve("catalog-test-approvals.json");
    private final Path hosts = outputDirectory.resolve("catalog-test-hosts");
    private final List<JsonNode> approvalEntries = new ArrayList<>();
    private boolean initializedFiles;

    /**
     * Core仅使用本测试的保护文件及hosts映射，绝不从公网DNS获取测试配置。
     *
     * @param mode 运维模式
     * @param overrides 当前场景覆盖
     * @return 隔离运行环境
     */
    @Override
    protected Map<String, String> coreEnvironment(String mode, Map<String, String> overrides) {
        Map<String, String> environment = super.coreEnvironment(mode, overrides);
        try {
            if (!initializedFiles) {
                Files.createDirectories(outputDirectory);
                writeManifest();
                writeHosts();
                initializedFiles = true;
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Test fixtures are unavailable");
        }
        environment.putIfAbsent("AIGATE_CATALOG_APPROVALS_FILE", manifest.toString());
        environment.put("JDK_JAVA_OPTIONS", "-Djdk.net.hosts.file=" + hosts);
        return environment;
    }

    /** 测试域名只指向受控数字地址，P3没有任何HTTP发送实现。 */
    private void writeHosts() throws IOException {
        Files.writeString(hosts, "93.184.216.34 api.catalog.test\n10.20.1.5 private.catalog.test\n"
            + "169.254.169.254 metadata.catalog.test\n127.0.0.1 loopback.catalog.test\n");
    }

    /** 普通管理HTTP不能写此文件，只有测试模拟独立安全运维安装批准。 */
    private void writeManifest() throws IOException {
        ObjectNode root = json.createObjectNode().put("schemaVersion", 1);
        var array = root.putArray("approvals");
        approvalEntries.forEach(array::add);
        Files.writeString(manifest, root.toString());
        Files.setPosixFilePermissions(manifest, PosixFilePermissions.fromString("rw-------"));
    }

    /** 每个场景复位批准快照，避免上一测试的批准意外授权下一场景。 */
    @BeforeEach
    void resetApprovalSnapshot() throws Exception {
        approvalEntries.clear();
        writeManifest();
        writeHosts();
        stop(core);
        startCore("INIT", Map.of());
    }

    /** 读取真实自增ID，不假设固定的生产或测试主键。 */
    private long id(JsonNode value) {
        return value.get("id").asLong();
    }

    /** 固定七字段能力仅为测试元数据，不证明真实Provider上限。 */
    private ObjectNode profile() {
        return json.createObjectNode().put("schemaVersion", 1).put("chatCompletions", true).put("streaming", true)
            .put("toolCalling", false).put("maxInputTokens", 4096).put("maxOutputTokens", 1024)
            .put("maxTotalTokens", 4096);
    }

    /** 生成候选HostPolicy，私网声明需要另装批准文件才能ACTIVE。 */
    private ObjectNode policy(String mode) {
        ObjectNode policy = json.createObjectNode().put("schemaVersion", 1);
        boolean local = "SELF_HOSTED".equals(mode);
        policy.putArray("allowedHostnames").add(local ? "private.catalog.test" : "api.catalog.test");
        policy.putArray("allowedPorts").add(443);
        policy.put("tlsRequired", true).put("allowPrivateNetwork", local);
        var cidrs = policy.putArray("approvedPrivateCidrs");
        if (local) {
            cidrs.add("10.20.0.0/16");
        }
        return policy;
    }

    /** 按公开协议构造候选适配，缺省不含服务端deploymentId。 */
    private ObjectNode adapter(JsonNode model, String mode) {
        ObjectNode adapter = json.createObjectNode().put("adapterKind", "OPENAI_CHAT_TEXT_V1")
            .put("remoteModelIdentity",
                model.get("modelCode").asString() + "@" + model.get("immutableModelRevision").asString())
            .put("tokenizerId", "test-tokenizer").put("tokenizerRevision", "test-v1")
            .put("inputBoundMethod", "VERIFIED_TOKENIZER")
            .put("maxInputTokens", 4096).put("maxOutputTokens", 1024).put("maxTotalTokens", 4096)
            .put("supportsJson", true)
            .put("supportsStreaming", true).put("supportsFinalStreamUsage", true)
            .put("usageEvidenceMode",
                "SELF_HOSTED".equals(mode) ? "VERIFIED_SELF_HOSTED_USAGE" : "VERIFIED_UPSTREAM_USAGE");
        adapter.putArray("billingCategories").add("INPUT_TOKENS").add("OUTPUT_TOKENS");
        return adapter.put("cachedInputMode", "NO_CACHE").put("extraChargesPossible", false)
            .put("providerSupportsIdempotency", false).put("compatibilityRevision", "test-cap-v1")
            .put("verifiedAt", "2026-10-01T00:00:00Z").put("evidenceRef", "TEST-ADAPTER-1");
    }

    /** 创建真实Provider和Model，部署先保持DRAFT且不自动获得任何审批。 */
    private Catalog catalog(String mode) throws Exception {
        Session admin = initialize();
        JsonNode policy = policy(mode);
        JsonNode provider = expect(
            call("POST", "/api/v1/providers", admin.key(), body(Map.of("code", "CATALOG_PROVIDER",
                "name", "Test Provider", "hostingMode", mode, "baseUrlHostPolicy", policy))),
            201);
        JsonNode model = expect(call("POST", "/api/v1/models", admin.key(), body(Map.of("modelCode", "TEXT-Q4-STABLE",
            "displayName", "Test Model", "modelFamily", "TEXT-Q4", "immutableModelRevision", "stable-r1",
            "capabilityProfile", profile()))), 201);
        ObjectNode request = json.createObjectNode().put("providerId", id(provider))
            .put("deploymentCode", "CATALOG_DEPLOYMENT")
            .put("endpointUri",
                "https://" + ("SELF_HOSTED".equals(mode) ? "private" : "api") + ".catalog.test/v1/chat/completions")
            .put("remoteModelName", "remote-test-model").put("regionCode", "TEST-REGION")
            .put("capabilityRevision", "test-cap-v1");
        request.set("adapterCompatibility", adapter(model, mode));
        request.put("adapterEvidenceRef", "TEST-ADAPTER-1").put("maxConcurrent", 2);
        JsonNode deployment = expect(
            call("POST", "/api/v1/models/" + id(model) + "/deployments", admin.key(), request.toString()), 201);
        return new Catalog(admin, provider, model, deployment, policy, request);
    }

    /** 对照文档固定顺序独立生成测试审批摘要，不通过管理API自报批准。 */
    private void approve(Catalog catalog, boolean allowNoAuth, Instant until) throws Exception {
        ObjectNode canonical = json.createObjectNode().put("schemaVersion", 1)
            .put("providerCode", catalog.provider().get("code").asString())
            .put("hostingMode", catalog.provider().get("hostingMode").asString())
            .put("modelCode", catalog.model().get("modelCode").asString())
            .put("modelFamily", catalog.model().get("modelFamily").asString())
            .put("immutableModelRevision", catalog.model().get("immutableModelRevision").asString());
        canonical.set("hostPolicy", catalog.policy());
        for (String name : List.of("endpointUri", "remoteModelName", "regionCode", "capabilityRevision")) {
            canonical.put(name, catalog.request().get(name).asString());
        }
        canonical.set("adapterCompatibility", catalog.request().get("adapterCompatibility"));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        approvalEntries.add(json.createObjectNode().put("approvalRef", "SEC-TEST-" + approvalEntries.size())
            .put("configDigest", digest).put("approvedBy", "independent-test-security")
            .put("approvedAt", Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS).toString())
            .put("validUntil", until.truncatedTo(ChronoUnit.MICROS).toString()).put("allowNoAuth", allowNoAuth));
        writeManifest();
        stop(core);
        startCore("INIT", Map.of());
    }

    /** 注册Secret到脱敏集合后发送，响应只能有安全元数据。 */
    private JsonNode credential(Catalog catalog, int revision, String kind, String secret, String idem)
        throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of("credentialKind", kind, "credentialRevision", revision));
        if (secret != null) {
            secretValues.add(secret);
            request.put("secret", secret);
        }
        Reply reply = call("POST", "/api/v1/deployments/" + id(catalog.deployment()) + "/credentials",
            catalog.admin().key(),
            body(request), idem);
        JsonNode metadata = expect(reply, 201);
        assertEquals(8, metadata.size());
        assertFalse(reply.body().contains("encryptedSecret"));
        assertFalse(reply.body().contains("secret"));
        assertTrue(reply.headers().get("cache-control").contains("no-store"));
        return metadata;
    }

    /** 只发布测试双价，读取实时父版本，避免把旧CAS版本当幂等。 */
    private void prices(Catalog catalog) throws Exception {
        String model = "/api/v1/models/" + id(catalog.model());
        expect(call("POST", model + "/points-prices", catalog.admin().key(),
            "{\"revision\":1,\"pointPer1kInput\":\"4.000000\",\"pointPer1kOutput\":\"8.000000\"}"), 201);
        long version = expect(call("GET", model, catalog.admin().key(), null), 200).get("version").asLong();
        expect(call("POST", model + "/points-prices/1/publish", catalog.admin().key(),
            body(Map.of("expectedModelVersion", version))), 200);
        if ("THIRD_PARTY_API".equals(catalog.provider().get("hostingMode").asString())) {
            String deployment = "/api/v1/deployments/" + id(catalog.deployment());
            expect(call("POST", deployment + "/purchase-prices", catalog.admin().key(),
                "{\"revision\":1,\"currency\":\"USD\",\"amountPer1kInput\":\"0.10000000\","
                    + "\"amountPer1kOutput\":\"0.20000000\"}"),
                201);
            version = expect(call("GET", deployment, catalog.admin().key(), null), 200).get("version").asLong();
            expect(call("POST", deployment + "/purchase-prices/1/publish", catalog.admin().key(),
                body(Map.of("expectedDeploymentVersion", version))), 200);
        }
    }

    /** 显式激活仍需当前凭证、价格和受保护批准，先读实时CAS版本。 */
    private JsonNode activate(Catalog catalog) throws Exception {
        String path = "/api/v1/deployments/" + id(catalog.deployment());
        long version = expect(call("GET", path, catalog.admin().key(), null), 200).get("version").asLong();
        return expect(
            call("PATCH", path, catalog.admin().key(), body(Map.of("expectedVersion", version, "status", "ACTIVE"))),
            200);
    }

    /** 建立真实双运行Key，可分别省略两条ACL以验证它们不能互相代替。 */
    private RuntimeIdentity runtime(Catalog catalog, boolean employeeAcl, boolean modelAcl) throws Exception {
        long employee = employee(catalog.admin(), catalog.admin().teamId(), "CATALOG_EMPLOYEE");
        long agent = expect(
            call("POST", "/api/v1/agents", catalog.admin().key(), body(Map.of("applicationCode", "CATALOG_AGENT",
                "displayName", "Catalog Agent", "ownerTeamId", catalog.admin().teamId()))),
            201).get("id").asLong();
        String expires = body(Map.of("expiresAt", expiry()));
        String agentKey = expect(call("POST", "/api/v1/agents/" + agent + "/keys", catalog.admin().key(), expires,
            "catalog-runtime-agent"), 201).get("key").asString();
        String employeeKey = expect(
            call("POST", "/api/v1/employees/" + employee + "/runtime-credentials", catalog.admin().key(),
                expires, "catalog-runtime-employee"),
            201).get("key").asString();
        secretValues.addAll(List.of(agentKey, employeeKey));
        if (employeeAcl) {
            expect(call("PUT", "/api/v1/agents/" + agent + "/employees/" + employee, catalog.admin().key(), null), 201);
        }
        if (modelAcl) {
            expect(
                call("PUT", "/api/v1/agents/" + agent + "/models/" + id(catalog.model()), catalog.admin().key(), null),
                201);
        }
        return new RuntimeIdentity(agent, employee, agentKey, employeeKey);
    }

    /** 内网RPC只接受服务身份和两类运行Key，默认无正文/参数。 */
    private Reply eligibility(Catalog catalog, RuntimeIdentity runtime) throws Exception {
        return request(corePort, "GET",
            "/internal/v1/catalog/models/" + catalog.model().get("modelCode").asString() + "/eligibility",
            runtime.agentKey(), null, Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken,
                "X-Employee-Key", runtime.employeeKey()));
    }

    /**
     * 合法管理链路与内网就绪检查跑通，但公网上仍没有聊天或秘密领取入口。
     */
    @Test
    @DisplayName("P3-04、07、11、17、19、23、29、30、32：真实目录闭环与双ACL就绪")
    void completesCatalogWithTrustedApprovalPricesCredentialAndBothAcls() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        assertEquals("DRAFT", catalog.deployment().get("status").asString());
        assertFalse(catalog.deployment().get("hasApprovedAdapterCompatibility").asBoolean());
        RuntimeIdentity runtime = runtime(catalog, true, true);
        assertFalse(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        prices(catalog);
        credential(catalog, 1, "BEARER", "fake-provider-" + random(), "catalog-first-credential");
        approve(catalog, false, Instant.now().plusSeconds(3600));
        JsonNode active = activate(catalog);
        assertTrue(active.get("hasApprovedAdapterCompatibility").asBoolean());
        assertTrue(active.get("hasActiveCredential").asBoolean());
        assertFalse(active.has("endpointUri"));
        assertFalse(active.has("adapterCompatibility"));
        assertTrue(active.get("capacityPoolId").isNull());
        JsonNode eligible = expect(eligibility(catalog, runtime), 200);
        assertEquals(8, eligible.size());
        assertTrue(eligible.get("eligible").asBoolean());
        assertEquals(1, eligible.get("deployments").size());
        assertEquals("USD", eligible.get("deployments").get(0).get("currency").asString());
        assertEquals(6, eligible.get("deployments").get(0).size());
        expect(call("GET", "/api/v1/models/" + id(catalog.model()) + "/deployments?status=ACTIVE",
            catalog.admin().key(), null), 200);
        expect(call("GET", "/api/v1/providers?hostingMode=THIRD_PARTY_API&status=ACTIVE", catalog.admin().key(), null),
            200);
        expect(call("GET", "/api/v1/models?status=ACTIVE", catalog.admin().key(), null), 200);
        expect(call("GET", "/api/v1/agents/" + runtime.agent() + "/models?status=GRANTED", catalog.admin().key(), null),
            200);
        expect(call("POST", "/v1/chat/completions", runtime.agentKey(), "{}"), 404);
        expect(call("GET", "/internal/v1/catalog/models/TEXT-Q4-STABLE/eligibility", runtime.agentKey(), null), 404);
        expect(request(corePort, "GET", "/internal/v1/invocations/inv_fake/provider-credential", null, null,
            Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken)), 404);
    }

    /**
     * HTTP层和业务层共同拒绝未知/不可变字段，不能只依赖前端表单。
     */
    @Test
    @DisplayName("P3-08、13、18、25、33、D47-API15～17：严格输入、身份不可暗换及SSRF登记拒绝")
    void rejectsUnknownImmutableAndUnsafeCatalogInputs() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        for (String patch : List.of("{\"expectedVersion\":0}", "{\"expectedVersion\":0,\"modelCode\":\"OTHER_MODEL\"}",
            "{\"expectedVersion\":0,\"capabilityProfile\":{}}", "{\"expectedVersion\":0,\"status\":null}")) {
            expect(call("PATCH", "/api/v1/models/" + id(catalog.model()), catalog.admin().key(), patch), 400);
        }
        ObjectNode invalidPolicy = policy("THIRD_PARTY_API").put("approval", true);
        expect(call("POST", "/api/v1/providers", catalog.admin().key(), body(Map.of("code", "BAD_POLICY",
            "name", "Bad", "hostingMode", "THIRD_PARTY_API", "baseUrlHostPolicy", invalidPolicy))), 400);
        for (String name : List.of("deploymentId", "capacityPoolId", "costModel", "endpointUri")) {
            ObjectNode invalid = catalog.request().deepCopy().put("deploymentCode", "INVALID_DEPLOYMENT");
            if ("deploymentId".equals(name)) {
                ((ObjectNode) invalid.get("adapterCompatibility")).put(name, 999);
            } else if ("endpointUri".equals(name)) {
                invalid.put(name, "http://api.catalog.test/v1/chat/completions");
            } else {
                invalid.put(name, 1);
            }
            expect(call("POST", "/api/v1/models/" + id(catalog.model()) + "/deployments", catalog.admin().key(),
                invalid.toString()), 400);
        }
        ObjectNode wrongModel = catalog.request().deepCopy().put("deploymentCode", "WRONG_MODEL");
        ((ObjectNode) wrongModel.get("adapterCompatibility")).put("remoteModelIdentity", "OTHER_MODEL@v1");
        expect(call("POST", "/api/v1/models/" + id(catalog.model()) + "/deployments", catalog.admin().key(),
            wrongModel.toString()), 400);
        for (String path : List.of("/api/v1/providers", "/api/v1/models",
            "/api/v1/models/" + id(catalog.model()) + "/deployments")) {
            expect(call("GET", path + "?size=101", catalog.admin().key(), null), 400);
            expect(call("GET", path + "?status=UNKNOWN", catalog.admin().key(), null), 400);
            expect(call("GET", path + "?page=1&page=2", catalog.admin().key(), null), 400);
            expect(call("GET", path, catalog.admin().key(), "{}"), 400);
        }
        String prices = "/api/v1/models/" + id(catalog.model()) + "/points-prices";
        for (String invalid : List.of("{\"revision\":1,\"pointPer1kInput\":1,\"pointPer1kOutput\":\"1.000000\"}",
            "{\"revision\":1,\"pointPer1kInput\":\"-1.000000\",\"pointPer1kOutput\":\"1.000000\"}",
            "{\"revision\":1,\"pointPer1kInput\":\"1.0000001\",\"pointPer1kOutput\":\"1.000000\"}")) {
            expect(call("POST", prices, catalog.admin().key(), invalid), 400);
        }
        assertEquals(1, scalar("SELECT COUNT(*) FROM model_deployment"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM model_points_price_version"));
        long boundId = scalar(
            "SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(adapter_compatibility_json,'$.deploymentId')) AS UNSIGNED) "
                + "FROM model_deployment");
        assertEquals(id(catalog.deployment()), boundId);
    }

    /**
     * 唯一键竞争和CAS都经过真实Gateway、Core及MySQL，不以Mock代替事务竞态。
     */
    @Test
    @DisplayName("P3-02、10：Provider/Model/Deployment唯一键竞争与CAS只允许一个提交")
    void concurrentCatalogCreationAndUpdatesRemainUniqueAndVersioned() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        List<String> paths = List.of("/api/v1/providers", "/api/v1/models",
            "/api/v1/models/" + id(catalog.model()) + "/deployments");
        List<String> payloads = List.of(
            body(Map.of("code", "CONCURRENT_PROVIDER", "name", "Concurrent", "hostingMode", "THIRD_PARTY_API",
                "baseUrlHostPolicy", policy("THIRD_PARTY_API"))),
            body(Map.of("modelCode", "CONCURRENT_MODEL", "displayName", "Concurrent",
                "modelFamily", "Family", "immutableModelRevision", "r1", "capabilityProfile", profile())),
            catalog.request().deepCopy().put("deploymentCode", "CONCURRENT_DEPLOYMENT").toString());
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            String payload = payloads.get(index);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = pool.submit(() -> call("POST", path, catalog.admin().key(), payload));
                var second = pool.submit(() -> call("POST", path, catalog.admin().key(), payload));
                List<Integer> statuses = List.of(first.get().status(), second.get().status());
                assertTrue(statuses.contains(201));
                assertTrue(statuses.contains(409));
            }
        }
        for (String table : List.of("provider", "logical_model", "model_deployment")) {
            assertEquals(2, scalar("SELECT COUNT(*) FROM " + table));
        }
        String provider = "/api/v1/providers/" + id(catalog.provider());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(
                () -> call("PATCH", provider, catalog.admin().key(), "{\"expectedVersion\":0,\"name\":\"First\"}"));
            var second = pool.submit(
                () -> call("PATCH", provider, catalog.admin().key(), "{\"expectedVersion\":0,\"name\":\"Second\"}"));
            List<Integer> statuses = List.of(first.get().status(), second.get().status());
            assertTrue(statuses.contains(200));
            assertTrue(statuses.contains(409));
        }
        assertEquals(1, expect(call("GET", provider, catalog.admin().key(), null), 200).get("version").asLong());
        JsonNode page = expect(call("GET", "/api/v1/providers?size=1&page=2", catalog.admin().key(), null), 200);
        assertEquals(2, page.get("total").asLong());
        assertEquals(1, page.get("items").size());
    }

    /**
     * 团队经理、普通员工和运行Key都不能通过同团队或已有ACL提升目录管理权限。
     */
    @Test
    @DisplayName("P3-05：非全局管理员无法读取或写入任何Catalog安全资源")
    void catalogManagementRequiresCurrentGlobalAdministrator() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        long manager = employee(catalog.admin(), catalog.admin().teamId(), "CATALOG_MANAGER");
        expect(
            call("PUT", role(manager, "TEAM_MANAGER", "TEAM", catalog.admin().teamId()), catalog.admin().key(), null),
            201);
        String managerKey = issue(catalog.admin(), manager, "catalog-manager-key", expiry()).get("key").asString();
        long ordinary = employee(catalog.admin(), catalog.admin().teamId(), "CATALOG_ORDINARY");
        String ordinaryKey = issue(catalog.admin(), ordinary, "catalog-ordinary-key", expiry()).get("key").asString();
        RuntimeIdentity runtime = runtime(catalog, true, true);
        for (String key : List.of(managerKey, ordinaryKey)) {
            for (String path : List.of("/api/v1/providers", "/api/v1/providers/" + id(catalog.provider()),
                "/api/v1/models",
                "/api/v1/models/" + id(catalog.model()), "/api/v1/deployments/" + id(catalog.deployment()),
                "/api/v1/models/" + id(catalog.model()) + "/points-prices",
                "/api/v1/deployments/" + id(catalog.deployment()) + "/credentials",
                "/api/v1/agents/" + runtime.agent() + "/models")) {
                expect(call("GET", path, key, null), 403);
            }
            expect(call("PATCH", "/api/v1/providers/" + id(catalog.provider()), key,
                "{\"expectedVersion\":0,\"name\":\"No\"}"), 403);
            expect(call("POST", "/api/v1/models/" + id(catalog.model()) + "/points-prices", key,
                "{\"revision\":1,\"pointPer1kInput\":\"1.000000\",\"pointPer1kOutput\":\"1.000000\"}"), 403);
            expect(call("POST", "/api/v1/deployments/" + id(catalog.deployment()) + "/credentials", key,
                "{\"credentialKind\":\"BEARER\",\"credentialRevision\":1,\"secret\":\"fake-value\"}",
                "catalog-forbidden-key"), 403);
            expect(call("DELETE", "/api/v1/deployments/" + id(catalog.deployment()) + "/credentials/99999", key, null),
                403);
            expect(call("DELETE", "/api/v1/agents/" + runtime.agent() + "/models/" + id(catalog.model()), key, null),
                403);
        }
        expect(call("GET", "/api/v1/models", runtime.agentKey(), null), 401);
        expect(call("DELETE", role(catalog.admin().employeeId(), "PLATFORM_ADMIN", "GLOBAL", 0), catalog.admin().key(),
            null), 409);
    }

    /**
     * 两个真实管理员竞争同父版本，只有一条草稿被发布；旧发布价格和原币种不可改。
     */
    @Test
    @DisplayName("P3-19～25：双价格不可变、不同管理员发布CAS、币种独立及历史顺序")
    void pricesPublishWithParentCasAndKeepImmutableCurrencyHistory() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        long secondAdmin = employee(catalog.admin(), catalog.admin().teamId(), "SECOND_CATALOG_ADMIN");
        expect(call("PUT", role(secondAdmin, "PLATFORM_ADMIN", "GLOBAL", 0), catalog.admin().key(), null), 201);
        String secondKey = issue(catalog.admin(), secondAdmin, "second-catalog-admin", expiry()).get("key").asString();
        String points = "/api/v1/models/" + id(catalog.model()) + "/points-prices";
        for (int revision : List.of(1, 2)) {
            JsonNode draft = expect(call("POST", points, catalog.admin().key(), body(Map.of("revision", revision,
                "pointPer1kInput", "4.000000", "pointPer1kOutput", "8.000000"))), 201);
            assertTrue(draft.get("effectiveFrom").isNull());
            assertEquals("4.000000", draft.get("pointPer1kInput").asString());
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(
                () -> call("POST", points + "/1/publish", catalog.admin().key(), "{\"expectedModelVersion\":0}"));
            var b = pool.submit(() -> call("POST", points + "/2/publish", secondKey, "{\"expectedModelVersion\":0}"));
            List<Integer> status = List.of(a.get().status(), b.get().status());
            assertTrue(status.contains(200));
            assertTrue(status.contains(409));
        }
        assertEquals(1, scalar("SELECT COUNT(*) FROM model_points_price_version WHERE status='PUBLISHED'"));
        JsonNode history = expect(call("GET", points, catalog.admin().key(), null), 200);
        assertEquals(1, history.get("items").get(0).get("revision").asInt());
        assertEquals(2, history.get("items").get(1).get("revision").asInt());
        expect(call("PATCH", points + "/1", catalog.admin().key(), "{}"), 404);
        expect(call("POST", points + "/1/publish", catalog.admin().key(), "{\"expectedModelVersion\":0}"), 409);
        String purchase = "/api/v1/deployments/" + id(catalog.deployment()) + "/purchase-prices";
        for (int revision : List.of(1, 2)) {
            expect(call("POST", purchase, catalog.admin().key(),
                body(Map.of("revision", revision, "currency", revision == 1 ? "USD" : "CNY",
                    "amountPer1kInput", "0.10000000", "amountPer1kOutput", "0.20000000"))),
                201);
            expect(call("POST", purchase + "/" + revision + "/publish", catalog.admin().key(),
                body(Map.of("expectedDeploymentVersion", revision - 1))), 200);
        }
        JsonNode purchaseHistory = expect(call("GET", purchase, catalog.admin().key(), null), 200);
        assertEquals("USD", purchaseHistory.get("items").get(0).get("currency").asString());
        assertEquals("CNY", purchaseHistory.get("items").get(1).get("currency").asString());
        assertEquals("0.10000000", purchaseHistory.get("items").get(1).get("amountPer1kInput").asString());
        assertEquals(1, expect(call("GET", "/api/v1/models/" + id(catalog.model()), catalog.admin().key(), null), 200)
            .get("version").asLong());
    }

    /**
     * 持久秘密通过真实MySQL读出后使用独立JCA解密核验，断言不打印秘密或明文字节。
     */
    // 表只存在于测试容器，IDE不应把它绑定到开发者的数据源。
    @SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
    private void assertEncryptedRow(long id, long deployment, int revision, String expected) throws Exception {
        try (var connection = db();
            var statement = connection
                .prepareStatement("SELECT encrypted_secret,iv,encryption_key_id FROM provider_credential WHERE id=?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                byte[] ciphertext = rows.getBytes(1);
                byte[] iv = rows.getBytes(2);
                String keyId = rows.getString(3);
                assertEquals(expected.getBytes(StandardCharsets.UTF_8).length + 16, ciphertext.length);
                assertEquals(12, iv.length);
                try (var buffer = new ByteArrayOutputStream();
                    var output = new DataOutputStream(buffer)) {
                    output.writeLong(deployment);
                    byte[] kind = "BEARER".getBytes(StandardCharsets.UTF_8);
                    output.writeInt(kind.length);
                    output.write(kind);
                    output.writeInt(revision);
                    byte[] keyName = keyId.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(keyName.length);
                    output.write(keyName);
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(Base64.getDecoder().decode(providerEncryptionKey), "AES"),
                        new GCMParameterSpec(128, iv));
                    cipher.updateAAD(buffer.toByteArray());
                    assertTrue(
                        MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), cipher.doFinal(ciphertext)),
                        "Only compare plaintext in memory; never render secret values");
                }
            }
        }
    }

    /**
     * 相同幂等键、不同签发者/修订和不同请求原字节都在真实数据库上裁决。
     */
    @Test
    @DisplayName("P3-26、27、37、38：真实AES最大字节、并发永久收据与安全元数据")
    void credentialsEncryptMaximumBytesAndReplayWithoutNewCiphertextOrPointer() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        String secret = "中".repeat(1365) + "A";
        JsonNode first = credential(catalog, 1, "BEARER", secret, "maximum-provider-secret");
        long credentialId = id(first);
        assertEncryptedRow(credentialId, id(catalog.deployment()), 1, secret);
        String path = "/api/v1/deployments/" + id(catalog.deployment()) + "/credentials";
        String payload = body(Map.of("credentialKind", "BEARER", "credentialRevision", 1, "secret", secret));
        long auditCount = scalar("SELECT COUNT(*) FROM admin_audit_event");
        long version = expect(
            call("GET", "/api/v1/deployments/" + id(catalog.deployment()), catalog.admin().key(), null), 200)
            .get("version").asLong();
        JsonNode replay = expect(call("POST", path, catalog.admin().key(), payload, "maximum-provider-secret"), 200);
        assertEquals(first, replay);
        assertEquals(auditCount, scalar("SELECT COUNT(*) FROM admin_audit_event"));
        assertEquals(version,
            expect(call("GET", "/api/v1/deployments/" + id(catalog.deployment()), catalog.admin().key(), null), 200)
                .get("version").asLong());
        assertEquals("IDEMPOTENCY_CONFLICT", expect(call("POST", path, catalog.admin().key(),
            body(Map.of("credentialKind", "BEARER", "credentialRevision", 2, "secret", secret)),
            "maximum-provider-secret"), 409)
            .get("error").get("code").asString());
        String tooLong = "A".repeat(4097);
        secretValues.add(tooLong);
        expect(
            call("POST", path, catalog.admin().key(), body(Map.of("credentialKind", "BEARER", "credentialRevision", 2,
                "secret", tooLong)), "oversized-provider-secret"),
            400);
        String nextSecret = "  fake-provider-" + random() + "\n";
        secretValues.add(nextSecret);
        String next = body(Map.of("credentialKind", "BEARER", "credentialRevision", 2, "secret", nextSecret));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> call("POST", path, catalog.admin().key(), next, "concurrent-provider-receipt"));
            var b = pool.submit(() -> call("POST", path, catalog.admin().key(), next, "concurrent-provider-receipt"));
            List<Reply> replies = List.of(a.get(), b.get());
            assertEquals(1, replies.stream().filter(r -> r.status() == 201).count());
            assertEquals(1, replies.stream().filter(r -> r.status() == 200).count());
            assertEquals(json.readTree(replies.get(0).body()), json.readTree(replies.get(1).body()));
        }
        assertEquals(2, scalar("SELECT COUNT(*) FROM provider_credential"));
        JsonNode history = expect(call("GET", path, catalog.admin().key(), null), 200);
        assertEquals(2, history.get("total").asLong());
        assertFalse(history.toString().contains(secret));
        assertFalse(history.toString().contains("issueRequestHmac"));
        assertEncryptedRow(history.get("items").get(1).get("id").asLong(), id(catalog.deployment()), 2, nextSecret);
        Reply audit = call("GET", "/api/v1/admin/audit-events", catalog.admin().key(), null);
        expect(audit, 200);
        assertFalse(audit.body().contains(secret));
        assertFalse(audit.body().contains(nextSecret));
    }

    /**
     * 两种部署共享真实模型和POINTS价格，采购币种及NONE批准仍独立。
     */
    @Test
    @DisplayName("P3-11、20、23、34、35：同模型第三方/本地部署共享POINTS但不混淆采购价和免鉴权批准")
    void selfHostedAndThirdPartyShareModelButKeepIndependentCostAndApproval() throws Exception {
        Catalog external = catalog("THIRD_PARTY_API");
        JsonNode privatePolicy = policy("SELF_HOSTED");
        JsonNode localProvider = expect(
            call("POST", "/api/v1/providers", external.admin().key(), body(Map.of("code", "LOCAL_PROVIDER",
                "name", "Local", "hostingMode", "SELF_HOSTED", "baseUrlHostPolicy", privatePolicy))),
            201);
        ObjectNode localRequest = external.request().deepCopy().put("providerId", id(localProvider))
            .put("deploymentCode", "LOCAL_DEPLOYMENT")
            .put("endpointUri", "https://private.catalog.test/v1/chat/completions")
            .put("remoteModelName", "local-remote-model");
        localRequest.set("adapterCompatibility", adapter(external.model(), "SELF_HOSTED"));
        JsonNode localDeployment = expect(
            call("POST", "/api/v1/models/" + id(external.model()) + "/deployments", external.admin().key(),
                localRequest.toString()),
            201);
        Catalog local = new Catalog(external.admin(), localProvider, external.model(), localDeployment, privatePolicy,
            localRequest);
        String localCredentials = "/api/v1/deployments/" + id(localDeployment) + "/credentials";
        expect(call("POST", localCredentials, external.admin().key(),
            "{\"credentialKind\":\"NONE\",\"credentialRevision\":1}",
            "unapproved-none-credential"), 409);
        expect(call("POST", "/api/v1/deployments/" + id(localDeployment) + "/purchase-prices", external.admin().key(),
            "{\"revision\":1,\"currency\":\"USD\",\"amountPer1kInput\":\"0.10000000\","
                + "\"amountPer1kOutput\":\"0.10000000\"}"),
            400);
        prices(external);
        credential(external, 1, "BEARER", "fake-provider-" + random(), "external-provider-credential");
        approve(external, false, Instant.now().plusSeconds(3600));
        approve(local, true, Instant.now().plusSeconds(3600));
        JsonNode none = credential(local, 1, "NONE", null, "approved-none-credential");
        assertEquals("NONE", none.get("credentialKind").asString());
        assertEquals(1, scalar(
            "SELECT COUNT(*) FROM provider_credential WHERE credential_kind='NONE' "
                + "AND encrypted_secret IS NULL AND iv IS NULL"));
        activate(external);
        activate(local);
        RuntimeIdentity runtime = runtime(external, true, true);
        JsonNode ready = expect(eligibility(external, runtime), 200);
        assertEquals(2, ready.get("deployments").size());
        assertTrue(ready.get("deployments").get(1).get("currency").isNull());
        assertTrue(ready.get("deployments").get(1).get("purchasePriceVersionId").isNull());
        assertEquals(1, scalar("SELECT COUNT(*) FROM model_points_price_version"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM deployment_purchase_price_version"));
    }

    /**
     * 双ACL各自生效，撤销后新的内网请求必须看到已提交状态，恢复原唯一行。
     */
    @Test
    @DisplayName("P3-09、29～31、40：双ACL缺一不可、授权幂等、原行恢复和当前模型状态")
    void bothAclRelationsAreRequiredAndCommittedRevocationIsImmediate() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        RuntimeIdentity runtime = runtime(catalog, false, true);
        expect(eligibility(catalog, runtime), 403);
        expect(call("PUT", "/api/v1/agents/" + runtime.agent() + "/employees/" + runtime.employee(),
            catalog.admin().key(), null), 201);
        String path = "/api/v1/agents/" + runtime.agent() + "/models/" + id(catalog.model());
        expect(call("DELETE", path, catalog.admin().key(), null), 204);
        expect(eligibility(catalog, runtime), 403);
        long audits = scalar("SELECT COUNT(*) FROM admin_audit_event");
        expect(call("DELETE", path, catalog.admin().key(), null), 204);
        assertEquals(audits, scalar("SELECT COUNT(*) FROM admin_audit_event"));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> call("PUT", path, catalog.admin().key(), null));
            var b = pool.submit(() -> call("PUT", path, catalog.admin().key(), null));
            List<Integer> replies = List.of(a.get().status(), b.get().status());
            assertTrue(replies.contains(201));
            assertTrue(replies.contains(204));
        }
        assertEquals(1, scalar("SELECT COUNT(*) FROM agent_model_permission"));
        assertEquals(2, scalar("SELECT version FROM agent_model_permission"));
        expect(eligibility(catalog, runtime), 200);
        // 同时撤销与恢复按实际提交次序决定最终状态，下一次检查必须与持久事实一致。
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var revoke = pool.submit(() -> call("DELETE", path, catalog.admin().key(), null));
            var grant = pool.submit(() -> call("PUT", path, catalog.admin().key(), null));
            expect(revoke.get(), 204);
            assertTrue(grant.get().status() == 201 || grant.get().status() == 204);
        }
        JsonNode relation = expect(
            call("GET", "/api/v1/agents/" + runtime.agent() + "/models", catalog.admin().key(), null), 200)
            .get("items").get(0);
        boolean granted = "GRANTED".equals(relation.get("status").asString());
        expect(eligibility(catalog, runtime), granted ? 200 : 403);
        assertEquals(1, scalar("SELECT COUNT(*) FROM agent_model_permission"));
        assertTrue(relation.get("version").asLong() == 3 || relation.get("version").asLong() == 4);
        expect(call("PUT", path, catalog.admin().key(), null), granted ? 204 : 201);
        expect(call("PATCH", "/api/v1/models/" + id(catalog.model()), catalog.admin().key(),
            "{\"expectedVersion\":0,\"status\":\"DISABLED\"}"), 200);
        expect(eligibility(catalog, runtime), 409);
        assertEquals(1, scalar("SELECT COUNT(*) FROM agent_model_permission WHERE status='GRANTED'"));
        expect(call("PATCH", "/api/v1/models/" + id(catalog.model()), catalog.admin().key(),
            "{\"expectedVersion\":1,\"status\":\"ACTIVE\"}"), 200);
        expect(call("PATCH", "/api/v1/agents/" + runtime.agent(), catalog.admin().key(),
            "{\"expectedVersion\":0,\"status\":\"DISABLED\"}"), 200);
        expect(eligibility(catalog, runtime), 401);
    }

    /**
     * 有效目录批准仍不能覆盖服务身份、两类真实Key、未知参数和内网路由边界。
     */
    @Test
    @DisplayName("P3-30、32、40：私有eligibility验证服务身份、Key用途与精确输入边界")
    void privateEligibilityNeverTrustsManagementKeysOrForgedIdentityFields() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        RuntimeIdentity runtime = runtime(catalog, true, true);
        String path = "/internal/v1/catalog/models/TEXT-Q4-STABLE/eligibility";
        expect(
            request(corePort, "GET", path, runtime.agentKey(), null, Map.of("X-Employee-Key", runtime.employeeKey())),
            401);
        for (String wrong : List.of(catalog.admin().key(), bootstrapToken, serviceToken, runtime.employeeKey())) {
            expect(request(corePort, "GET", path, wrong, null,
                Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken,
                    "X-Employee-Key", runtime.employeeKey())),
                401);
        }
        expect(request(corePort, "GET", path, runtime.agentKey(), "{}",
            Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken,
                "X-Employee-Key", runtime.employeeKey())),
            400);
        expect(request(corePort, "GET", path + "?employeeId=" + runtime.employee(), runtime.agentKey(), null,
            Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken, "X-Employee-Key",
                runtime.employeeKey())),
            400);
        expect(request(corePort, "GET", path, runtime.agentKey(), null,
            Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken)), 401);
        expect(
            request(corePort, "GET", "/internal/v1/catalog/models/UNKNOWN_MODEL/eligibility", runtime.agentKey(), null,
                Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken, "X-Employee-Key",
                    runtime.employeeKey())),
            404);
    }

    /**
     * 生命周期与独立批准分离：HTTP自报不能激活，撤回/到期或Provider停用影响下一检查。
     */
    @Test
    @DisplayName("P3-06、17、28、34、35、41：独立批准、过期撤回、Provider状态和安全策略冲突")
    void currentApprovalAndProviderStateGateActivationAndEveryEligibilityCheck() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        prices(catalog);
        JsonNode key = credential(catalog, 1, "BEARER", "fake-provider-" + random(), "approval-gated-credential");
        RuntimeIdentity runtime = runtime(catalog, true, true);
        String path = "/api/v1/deployments/" + id(catalog.deployment());
        long version = expect(call("GET", path, catalog.admin().key(), null), 200).get("version").asLong();
        expect(call("PATCH", path, catalog.admin().key(), body(Map.of("expectedVersion", version, "status", "ACTIVE"))),
            409);
        approve(catalog, false, Instant.now().plusSeconds(3600));
        activate(catalog);
        assertTrue(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        // MAINTENANCE立即退出候选；显式恢复仍复核当前批准、价格和凭证。
        long activeVersion = expect(call("GET", path, catalog.admin().key(), null), 200).get("version").asLong();
        expect(call("PATCH", path, catalog.admin().key(),
            body(Map.of("expectedVersion", activeVersion, "status", "MAINTENANCE"))), 200);
        assertFalse(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        activate(catalog);
        assertTrue(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());

        // 1、声明了同样的evidenceRef仍不能替代被独立运维撤回的快照。
        approvalEntries.clear();
        writeManifest();
        stop(core);
        startCore("INIT", Map.of());
        assertFalse(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        approve(catalog, false, Instant.now().minusSeconds(1));
        assertFalse(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        approvalEntries.clear();
        approve(catalog, false, Instant.now().plusSeconds(3600));
        assertTrue(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());

        // 2、策略更新使ACTIVE部署不再匹配时，版本、配置和成功审计都不改变。
        String provider = "/api/v1/providers/" + id(catalog.provider());
        ObjectNode changed = policy("THIRD_PARTY_API");
        changed.putArray("allowedHostnames").add("another.catalog.test");
        long audits = scalar("SELECT COUNT(*) FROM admin_audit_event");
        assertEquals("HOST_POLICY_CONFLICT", expect(call("PATCH", provider, catalog.admin().key(),
            body(Map.of("expectedVersion", 0, "baseUrlHostPolicy", changed))), 409).get("error").get("code")
            .asString());
        assertEquals(audits, scalar("SELECT COUNT(*) FROM admin_audit_event"));
        assertEquals(0, expect(call("GET", provider, catalog.admin().key(), null), 200).get("version").asLong());
        expect(call("PATCH", provider, catalog.admin().key(), "{\"expectedVersion\":0,\"status\":\"DISABLED\"}"), 200);
        assertFalse(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        version = expect(call("GET", path, catalog.admin().key(), null), 200).get("version").asLong();
        expect(call("PATCH", path, catalog.admin().key(), body(Map.of("expectedVersion", version, "status", "ACTIVE"))),
            409);
        expect(call("PATCH", provider, catalog.admin().key(), "{\"expectedVersion\":1,\"status\":\"ACTIVE\"}"), 200);

        // 3、当前凭证吊销原子清空指针并禁用部署，重复吊销不增加审计。
        expect(call("DELETE", path + "/credentials/" + id(key), catalog.admin().key(), null), 204);
        assertFalse(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        assertEquals("DISABLED", expect(call("GET", path, catalog.admin().key(), null), 200).get("status").asString());
        assertEquals(1, scalar("SELECT COUNT(*) FROM model_deployment WHERE active_credential_id IS NULL"));
        audits = scalar("SELECT COUNT(*) FROM admin_audit_event");
        expect(call("DELETE", path + "/credentials/" + id(key), catalog.admin().key(), null), 204);
        assertEquals(audits, scalar("SELECT COUNT(*) FROM admin_audit_event"));
    }

    /**
     * 已有收据跨Deployment/revision也不能再签发，同修订不同Actor由真实唯一键裁决。
     */
    @Test
    @DisplayName("P3-28、38：跨部署/原字节幂等冲突、双Actor同修订唯一和错误密钥版本失败关闭")
    void receiptsAreActorScopedAcrossDeploymentsAndRevisionConflictsRemainUnique() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        String secret = "fake-provider-" + random();
        JsonNode first = credential(catalog, 1, "BEARER", secret, "cross-deployment-receipt");
        ObjectNode secondRequest = catalog.request().deepCopy().put("deploymentCode", "SECOND_DEPLOYMENT");
        long secondDeployment = expect(
            call("POST", "/api/v1/models/" + id(catalog.model()) + "/deployments", catalog.admin().key(),
                secondRequest.toString()),
            201).get("id").asLong();
        String payload = body(Map.of("credentialKind", "BEARER", "credentialRevision", 1, "secret", secret));
        String credentials = "/api/v1/deployments/" + id(catalog.deployment()) + "/credentials";
        assertEquals("IDEMPOTENCY_CONFLICT",
            expect(call("POST", "/api/v1/deployments/" + secondDeployment + "/credentials",
                catalog.admin().key(), payload, "cross-deployment-receipt"), 409).get("error").get("code").asString());
        secretValues.add(secret + " ");
        expect(call("POST", credentials, catalog.admin().key(),
            body(Map.of("credentialKind", "BEARER", "credentialRevision", 1,
                "secret", secret + " ")),
            "cross-deployment-receipt"), 409);
        expect(call("DELETE", "/api/v1/deployments/" + secondDeployment + "/credentials/" + id(first),
            catalog.admin().key(), null), 404);
        long secondAdmin = employee(catalog.admin(), catalog.admin().teamId(), "CREDENTIAL_ADMIN");
        expect(call("PUT", role(secondAdmin, "PLATFORM_ADMIN", "GLOBAL", 0), catalog.admin().key(), null), 201);
        String adminKey = issue(catalog.admin(), secondAdmin, "credential-second-admin", expiry()).get("key")
            .asString();
        String next = body(Map.of("credentialKind", "BEARER", "credentialRevision", 2, "secret", secret));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> call("POST", credentials, catalog.admin().key(), next, "actor-one-revision-two"));
            var b = pool.submit(() -> call("POST", credentials, adminKey, next, "actor-two-revision-two"));
            List<Integer> status = List.of(a.get().status(), b.get().status());
            assertTrue(status.contains(201));
            assertTrue(status.contains(409));
        }
        assertEquals(2, scalar("SELECT COUNT(*) FROM provider_credential"));
        prices(catalog);
        approve(catalog, false, Instant.now().plusSeconds(3600));
        activate(catalog);
        RuntimeIdentity runtime = runtime(catalog, true, true);
        assertTrue(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        sql("UPDATE provider_credential SET encryption_key_id='untrusted-version' WHERE id="
            + "(SELECT active_credential_id FROM model_deployment WHERE id=" + id(catalog.deployment()) + ")");
        assertFalse(
            expect(call("GET", "/api/v1/deployments/" + id(catalog.deployment()), catalog.admin().key(), null), 200)
                .get("hasActiveCredential").asBoolean());
        assertFalse(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
    }

    /**
     * 审计故障注入要验证真实数据库回滚，不能只检查HTTP错误码。
     */
    @Test
    @DisplayName("P3-42：创建、发布、签发、吊销和ACL撤销在审计失败时全部回滚")
    void auditFailureRollsBackEveryCatalogMutationAndCredentialPointer() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        prices(catalog);
        JsonNode credential = credential(catalog, 1, "BEARER", "fake-provider-" + random(), "audit-initial-credential");
        RuntimeIdentity runtime = runtime(catalog, true, true);
        String price = "/api/v1/models/" + id(catalog.model()) + "/points-prices";
        expect(call("POST", price, catalog.admin().key(),
            "{\"revision\":2,\"pointPer1kInput\":\"1.000000\",\"pointPer1kOutput\":\"2.000000\"}"), 201);
        long audits = scalar("SELECT COUNT(*) FROM admin_audit_event");
        String nextSecret = "fake-provider-" + random();
        secretValues.add(nextSecret);
        String next = body(Map.of("credentialKind", "BEARER", "credentialRevision", 2, "secret", nextSecret));
        String credentials = "/api/v1/deployments/" + id(catalog.deployment()) + "/credentials";
        sql("CREATE TRIGGER reject_catalog_audit BEFORE INSERT ON admin_audit_event "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try {
            expect(call("POST", "/api/v1/providers", catalog.admin().key(),
                body(Map.of("code", "AUDIT_REJECTED", "name", "Rejected",
                    "hostingMode", "THIRD_PARTY_API", "baseUrlHostPolicy", policy("THIRD_PARTY_API")))),
                503);
            expect(call("POST", price + "/2/publish", catalog.admin().key(), "{\"expectedModelVersion\":1}"), 503);
            expect(call("POST", credentials, catalog.admin().key(), next, "audit-retry-credential"), 503);
            expect(call("DELETE", credentials + "/" + id(credential), catalog.admin().key(), null), 503);
            expect(call("DELETE", "/api/v1/agents/" + runtime.agent() + "/models/" + id(catalog.model()),
                catalog.admin().key(), null), 503);
            assertEquals(1, scalar("SELECT COUNT(*) FROM provider"));
            assertEquals(1, scalar("SELECT COUNT(*) FROM provider_credential"));
            assertEquals(1, scalar("SELECT COUNT(*) FROM provider_credential WHERE status='ACTIVE'"));
            assertEquals(1,
                scalar("SELECT COUNT(*) FROM model_points_price_version WHERE revision=2 AND status='DRAFT'"));
            assertEquals(1, scalar("SELECT COUNT(*) FROM agent_model_permission WHERE status='GRANTED'"));
            assertEquals(id(credential),
                scalar("SELECT active_credential_id FROM model_deployment WHERE id=" + id(catalog.deployment())));
            assertEquals(audits, scalar("SELECT COUNT(*) FROM admin_audit_event"));
            expect(eligibility(catalog, runtime), 200);
        } finally {
            sql("DROP TRIGGER reject_catalog_audit");
        }
        expect(call("POST", credentials, catalog.admin().key(), next, "audit-retry-credential"), 201);
        assertEquals(2, scalar("SELECT COUNT(*) FROM provider_credential"));
    }

    /**
     * 缺失/复用配置和坏审批文件必须阻止真实JVM启动，合法轮换保留旧收据和密文。
     */
    @Test
    @DisplayName("P3-36、39：真实Core拒绝坏配置，AES/收据轮换保留历史且缺失历史不重签")
    void realStartupRejectsBadTrustRootsAndRotatedKeysPreserveReceipts() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        String secret = "fake-provider-" + random();
        credential(catalog, 1, "BEARER", secret, "rotation-history-receipt");
        Path invalidManifest = outputDirectory.resolve("invalid-catalog-manifest.json");
        Files.writeString(invalidManifest, "{\"schemaVersion\":1,\"approvals\":[],\"unknown\":true}");
        stop(core);
        for (Map<String, String> bad : List.of(Map.of("AIGATE_PROVIDER_ENCRYPTION_KEY", ""),
            Map.of("AIGATE_PROVIDER_ISSUE_PEPPER", providerEncryptionKey),
            Map.of("AIGATE_CATALOG_APPROVALS_FILE", invalidManifest.toString()))) {
            Process rejected = process("aigate-core", coreEnvironment("OFF", bad));
            try {
                assertTrue(rejected.waitFor(30, TimeUnit.SECONDS), "Invalid trust configuration must fail startup");
                assertTrue(rejected.exitValue() != 0);
            } finally {
                stop(rejected);
            }
        }
        String nextEncryption = Base64.getEncoder().encodeToString(randomBytes());
        String nextIssue = Base64.getEncoder().encodeToString(randomBytes());
        secretValues.addAll(List.of(nextEncryption, nextIssue));
        Map<String, String> rotated = new HashMap<>(Map.of("AIGATE_PROVIDER_ENCRYPTION_KEY_ID", "provider-enc-v2",
            "AIGATE_PROVIDER_ENCRYPTION_KEY", nextEncryption, "AIGATE_PROVIDER_PREVIOUS_ENCRYPTION_KEY_ID",
            "provider-enc-v1",
            "AIGATE_PROVIDER_PREVIOUS_ENCRYPTION_KEY", providerEncryptionKey,
            "AIGATE_PROVIDER_ISSUE_PEPPER_KEY_ID", "provider-issue-v2", "AIGATE_PROVIDER_ISSUE_PEPPER", nextIssue,
            "AIGATE_PROVIDER_ISSUE_PREVIOUS_PEPPER_KEY_ID", "provider-issue-v1",
            "AIGATE_PROVIDER_ISSUE_PREVIOUS_PEPPER", providerIssuePepper));
        startCore("INIT", rotated);
        String credentials = "/api/v1/deployments/" + id(catalog.deployment()) + "/credentials";
        String payload = body(Map.of("credentialKind", "BEARER", "credentialRevision", 1, "secret", secret));
        expect(call("POST", credentials, catalog.admin().key(), payload, "rotation-history-receipt"), 200);
        assertTrue(
            expect(call("GET", "/api/v1/deployments/" + id(catalog.deployment()), catalog.admin().key(), null), 200)
                .get("hasActiveCredential").asBoolean());
        credential(catalog, 2, "BEARER", secret, "new-keyring-receipt");
        rotated.keySet().removeIf(name -> name.contains("PREVIOUS"));
        stop(core);
        startCore("INIT", rotated);
        assertEquals("CREDENTIAL_HISTORY_UNAVAILABLE", expect(call("POST", credentials, catalog.admin().key(), payload,
            "rotation-history-receipt"), 503).get("error").get("code").asString());
        expect(call("POST", credentials, catalog.admin().key(),
            body(Map.of("credentialKind", "BEARER", "credentialRevision", 2,
                "secret", secret)),
            "new-keyring-receipt"), 200);
        assertEquals(2, scalar("SELECT COUNT(*) FROM provider_credential"));
    }

    /**
     * 改变真实Core解析结果后重启，只保留原批准不能让已变危险的地址继续成为候选。
     */
    @Test
    @DisplayName("P3-14、15：真实Core拒绝loopback/metadata，DNS改变后目录立即失去候选")
    void dangerousRegistrationAndChangedDnsFailClosedWithoutHttp() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        for (String host : List.of("loopback.catalog.test", "metadata.catalog.test")) {
            ObjectNode badPolicy = policy("THIRD_PARTY_API");
            badPolicy.putArray("allowedHostnames").add(host);
            JsonNode badProvider = expect(call("POST", "/api/v1/providers", catalog.admin().key(), body(Map.of("code",
                host.startsWith("loopback") ? "LOOPBACK_PROVIDER" : "METADATA_PROVIDER", "name", "Unsafe",
                "hostingMode", "THIRD_PARTY_API",
                "baseUrlHostPolicy", badPolicy))), 201);
            ObjectNode request = catalog.request().deepCopy().put("providerId", id(badProvider))
                .put("deploymentCode", "UNSAFE_DEPLOYMENT")
                .put("endpointUri", "https://" + host + "/v1/chat/completions");
            expect(call("POST", "/api/v1/models/" + id(catalog.model()) + "/deployments", catalog.admin().key(),
                request.toString()), 400);
        }
        prices(catalog);
        credential(catalog, 1, "BEARER", "fake-provider-" + random(), "dns-safe-credential");
        approve(catalog, false, Instant.now().plusSeconds(3600));
        activate(catalog);
        RuntimeIdentity runtime = runtime(catalog, true, true);
        assertTrue(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        Files.writeString(hosts, "127.0.0.1 api.catalog.test\n");
        stop(core);
        startCore("INIT", Map.of());
        assertFalse(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        assertEquals(1, scalar("SELECT COUNT(*) FROM model_deployment"));
    }

    /**
     * Core已提交但成功响应被中间代理丢弃，随后重放只能返回原安全收据。
     */
    @Test
    @DisplayName("P3-32、38：签发成功响应丢失、Gateway不重试、Core停机有界失败且Provider发送0")
    void lostCredentialResponseAndCoreOutageNeverIssueTwiceOrCallProvider() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        String secret = "fake-provider-" + random();
        secretValues.add(secret);
        String path = "/api/v1/deployments/" + id(catalog.deployment()) + "/credentials";
        String payload = body(Map.of("credentialKind", "BEARER", "credentialRevision", 1, "secret", secret));
        AtomicInteger forwarded = new AtomicInteger();
        AtomicInteger providerRequests = new AtomicInteger();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer fakeProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            proxy.setExecutor(executor);
            fakeProvider.setExecutor(executor);
            fakeProvider.createContext("/", exchange -> {
                providerRequests.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            proxy.createContext("/", exchange -> {
                try {
                    if ("GET".equals(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(401, -1);
                        return;
                    }
                    forwarded.incrementAndGet();
                    var builder = HttpRequest.newBuilder(URI.create(coreUrl + exchange.getRequestURI()))
                        .timeout(Duration.ofSeconds(5));
                    for (String header : List.of("Authorization", "Content-Type", "Idempotency-Key",
                        "X-AIGate-Service-Authorization",
                        "X-AIGate-Client-Address", "X-Request-Id")) {
                        String value = exchange.getRequestHeaders().getFirst(header);
                        if (value != null) {
                            builder.header(header, value);
                        }
                    }
                    var response = client.send(
                        builder.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()))
                            .build(),
                        HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 201) {
                        exchange.sendResponseHeaders(504, -1);
                    } else {
                        exchange.sendResponseHeaders(502, -1);
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // 客户端中断时关闭连接，最终以实际数据库断言判断是否提交。
                } finally {
                    exchange.close();
                }
            });
            proxy.start();
            fakeProvider.start();
            try {
                // 1、只有真实Core收到一次签发，故意丢弃首次成功响应。
                stop(gateway);
                startGateway("http://127.0.0.1:" + proxy.getAddress().getPort());
                expect(call("POST", path, catalog.admin().key(), payload, "lost-catalog-credential"), 504);
                assertEquals(1, forwarded.get());
                stop(gateway);
                startGateway(coreUrl);
                long audits = scalar("SELECT COUNT(*) FROM admin_audit_event");
                expect(call("POST", path, catalog.admin().key(), payload, "lost-catalog-credential"), 200);
                assertEquals(1, scalar("SELECT COUNT(*) FROM provider_credential"));
                assertEquals(audits, scalar("SELECT COUNT(*) FROM admin_audit_event"));

                // 2、Core停机时写入不能被自动重试，恢复后没有额外凭证。
                stop(core);
                int status = call("POST", path, catalog.admin().key(), payload, "outage-catalog-credential").status();
                assertTrue(status == 503 || status == 504);
                status = call("GET", "/api/v1/providers", catalog.admin().key(), null).status();
                assertTrue(status == 503 || status == 504);
                startCore("INIT", Map.of());
                assertEquals(1, scalar("SELECT COUNT(*) FROM provider_credential"));
                expect(call("POST", "/v1/chat/completions", catalog.admin().key(), "{}"), 404);
                expect(
                    call("POST", "/v1/chat/completions?baseUrl=http://127.0.0.1:" + fakeProvider.getAddress().getPort(),
                        catalog.admin().key(), "{}"),
                    404);
                assertEquals(0, providerRequests.get());
                assertEquals(0, scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='core_db' "
                    + "AND table_name IN ('invocation','dispatch_permit','capacity_pool')"));
            } finally {
                proxy.stop(0);
                fakeProvider.stop(0);
            }
        }
    }

    /**
     * 真实重复Header不能被默认取首值，避免代理和Core对同一请求产生不同身份或收据解释。
     */
    @Test
    @DisplayName("P3-40：重复运行认证头和凭证幂等头被真实HTTP入口拒绝")
    void duplicateHeadersCannotCreateAmbiguousIdentityOrReceipt() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        RuntimeIdentity runtime = runtime(catalog, true, true);
        var request = HttpRequest
            .newBuilder(URI.create(coreUrl + "/internal/v1/catalog/models/TEXT-Q4-STABLE/eligibility"))
            .timeout(Duration.ofSeconds(5)).GET().header("Authorization", "Bearer " + runtime.agentKey())
            .header("Authorization", "Bearer " + runtime.agentKey()).header("X-Employee-Key", runtime.employeeKey())
            .header("X-AIGate-Service-Authorization", "Bearer " + serviceToken).build();
        assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/api/v1/deployments/"
            + id(catalog.deployment()) + "/credentials")).timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + catalog.admin().key()).header("Content-Type", "application/json")
            .header("Idempotency-Key", "first-receipt-key").header("Idempotency-Key", "second-receipt-key")
            .POST(HttpRequest.BodyPublishers
                .ofString("{\"credentialKind\":\"BEARER\",\"secret\":\"fake-test-value\",\"credentialRevision\":1}"))
            .build();
        assertEquals(400, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, scalar("SELECT COUNT(*) FROM provider_credential"));
    }

    /**
     * 不完整价格和跨父指针都不能被已有ACTIVE状态掩盖。
     */
    @Test
    @DisplayName("P3-23：缺采购价或指向其它模型的价格时，目录检查拒绝成为候选")
    void missingOrCrossOwnerPricePointersCannotMakeDeploymentEligible() throws Exception {
        Catalog catalog = catalog("THIRD_PARTY_API");
        credential(catalog, 1, "BEARER", "fake-provider-" + random(), "price-gate-credential");
        approve(catalog, false, Instant.now().plusSeconds(3600));
        String points = "/api/v1/models/" + id(catalog.model()) + "/points-prices";
        expect(call("POST", points, catalog.admin().key(),
            "{\"revision\":1,\"pointPer1kInput\":\"1.000000\",\"pointPer1kOutput\":\"1.000000\"}"), 201);
        expect(call("POST", points + "/1/publish", catalog.admin().key(), "{\"expectedModelVersion\":0}"), 200);
        String deployment = "/api/v1/deployments/" + id(catalog.deployment());
        expect(call("PATCH", deployment, catalog.admin().key(), "{\"expectedVersion\":1,\"status\":\"ACTIVE\"}"), 409);
        // 仅在隔离库模拟损坏状态：就绪检查仍须逐项检查，不能信任ACTIVE字符串。
        sql("UPDATE model_deployment SET status='ACTIVE' WHERE id=" + id(catalog.deployment()));
        RuntimeIdentity runtime = runtime(catalog, true, true);
        assertFalse(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        expect(call("POST", deployment + "/purchase-prices", catalog.admin().key(),
            "{\"revision\":1,\"currency\":\"USD\",\"amountPer1kInput\":\"0.10000000\","
                + "\"amountPer1kOutput\":\"0.10000000\"}"),
            201);
        expect(call("POST", deployment + "/purchase-prices/1/publish", catalog.admin().key(),
            "{\"expectedDeploymentVersion\":1}"), 200);
        assertTrue(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        long ownPrice = scalar(
            "SELECT active_points_price_version_id FROM logical_model WHERE id=" + id(catalog.model()));
        JsonNode other = expect(call("POST", "/api/v1/models", catalog.admin().key(),
            body(Map.of("modelCode", "DIFFERENT_MODEL",
                "displayName", "Different", "modelFamily", "OtherFamily", "immutableModelRevision", "r2",
                "capabilityProfile", profile()))),
            201);
        String otherPoints = "/api/v1/models/" + id(other) + "/points-prices";
        JsonNode otherPrice = expect(call("POST", otherPoints, catalog.admin().key(),
            "{\"revision\":1,\"pointPer1kInput\":\"2.000000\",\"pointPer1kOutput\":\"3.000000\"}"), 201);
        expect(call("POST", otherPoints + "/1/publish", catalog.admin().key(), "{\"expectedModelVersion\":0}"), 200);
        sql("UPDATE logical_model SET active_points_price_version_id=" + id(otherPrice) + " WHERE id="
            + id(catalog.model()));
        assertFalse(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
        sql("UPDATE logical_model SET active_points_price_version_id=" + ownPrice + " WHERE id=" + id(catalog.model()));
        assertTrue(expect(eligibility(catalog, runtime), 200).get("eligible").asBoolean());
    }
}
