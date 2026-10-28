package com.aigate.contracts;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * P7纯文本请求的封闭解析与规范化；Core与Gateway共享语义，不共享HMAC密钥。 原文只在调用栈内持有，诊断字符串不包含Prompt。
 */
public final class ChatRequestV1 {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> FIELDS = Set.of("model", "stream", "messages", "max_tokens", "temperature",
        "top_p", "stream_options");
    private final ObjectNode canonical;

    private ChatRequestV1(ObjectNode canonical) {
        this.canonical = canonical;
    }

    /**
     * 严格解析公开V1请求，拒绝未知/重复/null字段、非文本消息及非法UTF-8。
     *
     * @param bytes 原始HTTP正文
     * @return 不包含凭证的受控内存请求
     */
    public static ChatRequestV1 parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > ApiContractConstants.MAX_JSON_BODY_BYTES) {
            throw invalid();
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.isObject() || !FIELDS.containsAll(root.propertyNames())) {
                throw invalid();
            }
            for (var property : root.properties()) {
                if (property.getValue().isNull()) {
                    throw invalid();
                }
            }
            String model = string(root.get("model"));
            if (!model.matches("[A-Z][A-Z0-9_-]{1,95}")) {
                throw invalid();
            }
            JsonNode stream = root.get("stream");
            JsonNode limit = root.get("max_tokens");
            JsonNode messages = root.get("messages");
            if (stream == null || !stream.isBoolean() || limit == null || !limit.isIntegralNumber()
                || !limit.canConvertToInt() || limit.intValue() < 1 || messages == null || !messages.isArray()
                || messages.isEmpty() || messages.size() > 64) {
                throw invalid();
            }
            var normalizedMessages = JSON.createArrayNode();
            for (JsonNode message : messages) {
                if (!message.isObject() || !Set.of("role", "content").equals(Set.copyOf(message.propertyNames()))) {
                    throw invalid();
                }
                String role = string(message.get("role"));
                String content = string(message.get("content"));
                if (!List.of("system", "user", "assistant").contains(role)
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(content)) {
                    throw invalid();
                }
                normalizedMessages.add(JSON.createObjectNode().put("role", role).put("content", content));
            }
            if (root.has("stream_options")) {
                JsonNode options = root.get("stream_options");
                if (!stream.booleanValue() || !options.isObject()
                    || !Set.of("include_usage").equals(Set.copyOf(options.propertyNames()))
                    || !options.get("include_usage").isBoolean() || !options.get("include_usage").booleanValue()) {
                    throw invalid();
                }
            }
            // 固定插入顺序；数值使用明确十进制默认值；不trim或归一化Prompt。
            ObjectNode canonical = JSON.createObjectNode().put("model", model).put("stream", stream.booleanValue());
            canonical.set("messages", normalizedMessages);
            canonical.put("max_tokens", limit.intValue());
            canonical.put("temperature", decimal(root, "temperature", BigDecimal.ZERO, new BigDecimal("2"), false));
            canonical.put("top_p", decimal(root, "top_p", BigDecimal.ZERO, BigDecimal.ONE, true));
            return new ChatRequestV1(canonical);
        } catch (CharacterCodingException | RuntimeException failure) {
            // 不链接Jackson异常：异常消息可能包含原始正文。
            throw invalid();
        }
    }

    private static String string(JsonNode value) {
        if (value == null || !value.isString()) {
            throw invalid();
        }
        return value.asString();
    }

    private static BigDecimal decimal(JsonNode root, String name, BigDecimal min, BigDecimal max, boolean exclusive) {
        if (!root.has(name)) {
            return BigDecimal.ONE;
        }
        JsonNode value = root.get(name);
        if (!value.isNumber()) {
            throw invalid();
        }
        BigDecimal number = value.decimalValue();
        if (number.compareTo(min) < (exclusive ? 1 : 0) || number.compareTo(max) > 0) {
            throw invalid();
        }
        // 不同JSON数字表示得到相同字节，含1、1.0、1e0及负零。
        return number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid ChatRequestV1");
    }

    /**
     * 获取固定字段顺序的规范JSON，调用方不得持久化或记录这些字节。
     *
     * @return 新分配的UTF-8规范字节
     */
    public byte[] canonicalBytes() {
        return JSON.writeValueAsBytes(canonical);
    }

    /**
     * 获取Core规范域；域分隔符为换行字节，不是反斜线和字母n。
     *
     * @return 含版本域的HMAC输入
     */
    public byte[] signingBytes() {
        byte[] prefix = "AIGATE:INVOCATION:V1\n".getBytes(StandardCharsets.UTF_8);
        byte[] body = canonicalBytes();
        return ByteBuffer.allocate(prefix.length + body.length).put(prefix).put(body).array();
    }

    /**
     * 生成独立请求树，供显式适配器映射；stream usage始终主动请求。
     *
     * @return 无凭证的正文副本
     */
    public ObjectNode providerBody() {
        ObjectNode body = canonical.deepCopy();
        if (body.get("stream").booleanValue()) {
            body.set("stream_options", JSON.createObjectNode().put("include_usage", true));
        }
        return body;
    }

    @Override
    public String toString() {
        return "ChatRequestV1[redacted]";
    }
}
