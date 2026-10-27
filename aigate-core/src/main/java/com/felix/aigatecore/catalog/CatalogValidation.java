package com.felix.aigatecore.catalog;

import com.aigate.contracts.dto.LogicalModelDTO.CapabilityProfile;
import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;
import java.math.BigDecimal;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Catalog封闭JSON、模型身份和定点价格校验，不把外部任意Map直接用于路由。
 *
 * @author AIGate
 */
public final class CatalogValidation {

    private static final int MAX_TOKENS = 2_000_000;
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private CatalogValidation() {
    }

    /**
     * 读取内部JSON配置；语法损坏时失败关闭，不输出配置原文。
     *
     * @param value 数据库或受保护文件中的JSON
     * @return JSON树
     */
    public static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (RuntimeException failure) {
            throw ApiException.invalid();
        }
    }

    /**
     * 创建按插入顺序序列化的对象，用于稳定的审批规范摘要。
     *
     * @return 空对象
     */
    public static ObjectNode newObject() {
        return JSON.createObjectNode();
    }

    /**
     * 要求对象的字段恰好等于当前Schema，无缺省、null或额外字段。
     *
     * @param node 待检查对象
     * @param fields 完整字段集合
     */
    public static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || !node.propertyNames().equals(Set.of(fields))) {
            throw ApiException.invalid();
        }
        for (var property : node.properties()) {
            if (property.getValue().isNull()) {
                throw ApiException.invalid();
            }
        }
    }

    /**
     * 读取不进行隐式类型转换和去空白的配置字符串。
     *
     * @param node 当前对象
     * @param field 字段名
     * @param max 最大Unicode码点数
     * @return 原始非空字符串
     */
    public static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw ApiException.invalid();
        }
        String result = value.asString();
        if (result.isEmpty() || !result.equals(result.strip()) || !StandardCharsets.UTF_8.newEncoder().canEncode(result)
            || result.codePointCount(0, result.length()) > max) {
            throw ApiException.invalid();
        }
        return result;
    }

    /**
     * 读取精确JSON整数，拒绝字符串、浮点数、科学计数和溢出。
     *
     * @param node 当前对象
     * @param field 字段名
     * @param min 最小值
     * @param max 最大值
     * @return 合法整数
     */
    public static int integer(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
            || value.intValue() < min || value.intValue() > max) {
            throw ApiException.invalid();
        }
        return value.intValue();
    }

    /**
     * 读取真实布尔值，不接受字符串形式的true/false。
     *
     * @param node 当前对象
     * @param field 字段名
     * @return 合法布尔值
     */
    public static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw ApiException.invalid();
        }
        return value.booleanValue();
    }

    /**
     * 校验不可变Catalog编码，不替客户端修正空白或大小写。
     *
     * @param value 原编码
     * @param max 列允许的最大长度
     * @return 合法编码
     */
    public static String code(String value, int max) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_-]{1," + (max - 1) + "}")) {
            throw ApiException.invalid();
        }
        return value;
    }

    /**
     * 价格只能以固定刻度字符串输入，不能在保存时四舍五入或截断。
     *
     * @param value 原价格字符串
     * @param scale 当前价格的固定刻度
     * @return 可无损保存到DECIMAL(20,scale)的价格
     */
    public static BigDecimal decimal(String value, int scale) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]*)\\.[0-9]{" + scale + "}")) {
            throw ApiException.invalid();
        }
        BigDecimal result = new BigDecimal(value);
        if (result.precision() > 20) {
            throw ApiException.invalid();
        }
        return result;
    }

    /**
     * 校验固定七字段的模型能力，不把它当作Tokenizer或收费上界证据。
     *
     * @param node 客户端能力对象
     * @return 固定能力DTO
     */
    public static CapabilityProfile capability(JsonNode node) {
        exact(node, "schemaVersion", "chatCompletions", "streaming", "toolCalling", "maxInputTokens",
            "maxOutputTokens", "maxTotalTokens");
        integer(node, "schemaVersion", 1, 1);
        if (!bool(node, "chatCompletions") || bool(node, "toolCalling")) {
            throw ApiException.invalid();
        }
        int input = integer(node, "maxInputTokens", 1, MAX_TOKENS);
        int output = integer(node, "maxOutputTokens", 1, MAX_TOKENS);
        int total = integer(node, "maxTotalTokens", 1, MAX_TOKENS);
        if (input > total || output > total) {
            throw ApiException.invalid();
        }
        return new CapabilityProfile(1, true, bool(node, "streaming"), false, input, output, total);
    }

    /**
     * 把能力对象归一化为固定字段顺序，避免审批摘要依赖请求的字段排序。
     *
     * @param node 原能力对象
     * @return 规范JSON
     */
    public static ObjectNode capabilityJson(JsonNode node) {
        CapabilityProfile profile = capability(node);
        return newObject().put("schemaVersion", 1).put("chatCompletions", true).put("streaming", profile.streaming())
            .put("toolCalling", false).put("maxInputTokens", profile.maxInputTokens())
            .put("maxOutputTokens", profile.maxOutputTokens()).put("maxTotalTokens", profile.maxTotalTokens());
    }

    /**
     * 将准确FQDN转为小写ASCII IDNA，不允许通配符、IP、URL或尾点歧义。
     *
     * @param value 客户端域名
     * @return 规范域名
     */
    public static String hostname(String value) {
        if (value == null || !value.equals(value.strip()) || value.endsWith(".") || value.contains("*")
            || value.matches("[0-9.]+") || value.indexOf(':') >= 0 || value.indexOf('/') >= 0) {
            throw ApiException.invalid();
        }
        try {
            String host = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (host.length() > 253 || !host.contains(".") || host.endsWith(".") || host.matches("[0-9.]+")) {
                throw ApiException.invalid();
            }
            return host;
        } catch (IllegalArgumentException failure) {
            throw ApiException.invalid();
        }
    }

    /**
     * 封闭校验并排序HostPolicy；声明私网CIDR本身不构成审批。
     *
     * @param node 原策略
     * @param mode 供应商托管模式
     * @return 规范策略JSON
     */
    public static ObjectNode hostPolicy(JsonNode node, String mode) {
        // 1、检查字段、枚举和数组上限，拒绝未知配置或隐式类型转换。
        exact(node, "schemaVersion", "allowedHostnames", "allowedPorts", "tlsRequired", "allowPrivateNetwork",
            "approvedPrivateCidrs");
        integer(node, "schemaVersion", 1, 1);
        if (!Set.of("THIRD_PARTY_API", "SELF_HOSTED").contains(mode) || !bool(node, "tlsRequired")) {
            throw ApiException.invalid();
        }
        JsonNode hosts = node.get("allowedHostnames");
        JsonNode ports = node.get("allowedPorts");
        JsonNode cidrs = node.get("approvedPrivateCidrs");
        if (!hosts.isArray() || hosts.isEmpty() || hosts.size() > 32 || !ports.isArray() || ports.isEmpty()
            || ports.size() > 8 || !cidrs.isArray() || cidrs.size() > 16) {
            throw ApiException.invalid();
        }
        TreeSet<String> hostnames = new TreeSet<>();
        for (JsonNode host : hosts) {
            if (!host.isString()) {
                throw ApiException.invalid();
            }
            hostnames.add(hostname(host.asString()));
        }
        TreeSet<Integer> portNumbers = new TreeSet<>();
        for (JsonNode port : ports) {
            if (!port.isIntegralNumber() || !port.canConvertToInt() || port.intValue() < 1 || port.intValue() > 65535) {
                throw ApiException.invalid();
            }
            portNumbers.add(port.intValue());
        }
        TreeSet<String> networks = new TreeSet<>();
        for (JsonNode cidr : cidrs) {
            if (!cidr.isString()) {
                throw ApiException.invalid();
            }
            networks.add(CatalogEndpointValidator.privateCidr(cidr.asString()));
        }

        // 2、第三方首版只能使用公网HTTPS 443；本地私网须显式声明非空范围。
        boolean allowPrivate = bool(node, "allowPrivateNetwork");
        if (("THIRD_PARTY_API".equals(mode)
            && (allowPrivate || !networks.isEmpty() || !portNumbers.equals(Set.of(443))))
            || (allowPrivate && networks.isEmpty()) || (!allowPrivate && !networks.isEmpty())) {
            throw ApiException.invalid();
        }
        ObjectNode normalized = newObject().put("schemaVersion", 1);
        var hostArray = normalized.putArray("allowedHostnames");
        hostnames.forEach(hostArray::add);
        var portArray = normalized.putArray("allowedPorts");
        portNumbers.forEach(portArray::add);
        normalized.put("tlsRequired", true).put("allowPrivateNetwork", allowPrivate);
        var cidrArray = normalized.putArray("approvedPrivateCidrs");
        networks.forEach(cidrArray::add);
        return normalized;
    }

    /**
     * 校验适配候选并按协议固定顺序输出；deploymentId只由持久化层回填。
     *
     * @param input 原适配对象
     * @param model 当前固定模型身份
     * @param mode 当前托管模式
     * @param revision 部署适配版本
     * @param evidence 审计证据引用
     * @param now 服务端当前时刻
     * @param stored 是否读取已经绑定deploymentId的持久对象
     * @return 不含deploymentId的规范候选
     */
    public static ObjectNode adapter(JsonNode input, LogicalModelDO model, String mode, String revision,
        String evidence, Instant now, boolean stored) {
        ObjectNode node = input.isObject() ? (ObjectNode) input.deepCopy() : null;
        if (node == null) {
            throw ApiException.invalid();
        }
        if (stored) {
            JsonNode id = node.remove("deploymentId");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
                throw ApiException.invalid();
            }
        }
        exact(node, "adapterKind", "remoteModelIdentity", "tokenizerId", "tokenizerRevision", "inputBoundMethod",
            "maxInputTokens", "maxOutputTokens", "maxTotalTokens", "supportsJson", "supportsStreaming",
            "supportsFinalStreamUsage", "usageEvidenceMode", "billingCategories", "cachedInputMode",
            "extraChargesPossible", "providerSupportsIdempotency", "compatibilityRevision", "verifiedAt",
            "evidenceRef");
        CapabilityProfile profile = capability(json(model.capabilityProfileJson()));
        String identity = model.modelCode() + "@" + model.immutableModelRevision();
        String boundMethod = text(node, "inputBoundMethod", 32);
        String usage = text(node, "usageEvidenceMode", 32);
        String cache = text(node, "cachedInputMode", 32);
        Instant verified = ValidatedBody.instantValue(text(node, "verifiedAt", 40));
        int inputTokens = integer(node, "maxInputTokens", 1, profile.maxInputTokens());
        int outputTokens = integer(node, "maxOutputTokens", 1, profile.maxOutputTokens());
        int totalTokens = integer(node, "maxTotalTokens", 1, profile.maxTotalTokens());
        if (!"OPENAI_CHAT_TEXT_V1".equals(text(node, "adapterKind", 32))
            || !identity.equals(text(node, "remoteModelIdentity", 256))
            || !Set.of("VERIFIED_TOKENIZER", "PROVIDER_ENFORCED_BOUND").contains(boundMethod)
            || !("THIRD_PARTY_API".equals(mode) ? "VERIFIED_UPSTREAM_USAGE" : "VERIFIED_SELF_HOSTED_USAGE")
                .equals(usage)
            || !Set.of("SAME_INPUT_RATE", "NO_CACHE").contains(cache) || !bool(node, "supportsJson")
            || (bool(node, "supportsStreaming") && !profile.streaming()) || verified.isAfter(now)
            || bool(node, "extraChargesPossible") || bool(node, "providerSupportsIdempotency")
            || !revision.equals(text(node, "compatibilityRevision", 96))
            || !evidence.equals(text(node, "evidenceRef", 256)) || inputTokens > totalTokens
            || outputTokens > totalTokens
            || !node.get("billingCategories").equals(json("[\"INPUT_TOKENS\",\"OUTPUT_TOKENS\"]"))) {
            throw ApiException.invalid();
        }

        // 固定字段顺序参与审批摘要，原请求顺序和服务端主键不影响预审批。
        ObjectNode result = newObject().put("adapterKind", "OPENAI_CHAT_TEXT_V1").put("remoteModelIdentity", identity)
            .put("tokenizerId", text(node, "tokenizerId", 128))
            .put("tokenizerRevision", text(node, "tokenizerRevision", 96))
            .put("inputBoundMethod", boundMethod).put("maxInputTokens", inputTokens)
            .put("maxOutputTokens", outputTokens)
            .put("maxTotalTokens", totalTokens).put("supportsJson", true)
            .put("supportsStreaming", bool(node, "supportsStreaming"))
            .put("supportsFinalStreamUsage", bool(node, "supportsFinalStreamUsage")).put("usageEvidenceMode", usage);
        result.putArray("billingCategories").add("INPUT_TOKENS").add("OUTPUT_TOKENS");
        return result.put("cachedInputMode", cache).put("extraChargesPossible", false)
            .put("providerSupportsIdempotency", false).put("compatibilityRevision", revision)
            .put("verifiedAt", verified.toString()).put("evidenceRef", evidence);
    }
}
