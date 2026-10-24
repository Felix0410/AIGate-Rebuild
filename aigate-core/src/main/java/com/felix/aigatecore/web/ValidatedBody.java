package com.felix.aigatecore.web;

import com.aigate.contracts.ApiContractConstants;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 严格 JSON 校验器。先校验 JSON 树的真实类型，再读取字段，禁止标量强制转换、重复键和未知字段。
 *
 * @author AIGate
 */
public final class ValidatedBody {

    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final JsonNode node;

    /**
     * 创建ValidatedBody，显式注入当前组件依赖或不可变状态。
     *
     * @param node 已验证为对象的 JSON 树
     */
    private ValidatedBody(JsonNode node) {
        this.node = node;
    }

    /**
     * 解析并校验完整 JSON 对象，拒绝空正文、重复属性、未知属性和显式 null。
     *
     * @param bytes 原始 UTF-8 JSON 字节
     * @param allowed 当前操作允许出现的字段名白名单
     * @return 按字段白名单完成解析的请求正文
     */
    public static ValidatedBody parse(byte[] bytes, String... allowed) {
        if (bytes == null || bytes.length == 0 || bytes.length > ApiContractConstants.MAX_JSON_BODY_BYTES) {
            throw ApiException.invalid();
        }
        try {
            JsonNode node = JSON.readTree(bytes);
            if (node == null || !node.isObject() || !Set.of(allowed).containsAll(node.propertyNames())) {
                throw ApiException.invalid();
            }
            for (var value : node.properties()) {
                if (value.getValue().isNull()) {
                    throw ApiException.invalid();
                }
            }
            return new ValidatedBody(node);
        } catch (JacksonException exception) {
            throw ApiException.invalid();
        }
    }

    /**
     * 判断字段是否由客户端显式提供，用于区分 PATCH 的缺省字段与修改意图。
     *
     * @param key 当前接口白名单中的 JSON 字段名
     * @return 满足方法所述条件时为 true，否则为 false
     */
    public boolean has(String key) {
        return node.has(key);
    }

    /**
     * 读取真实字符串字段，按现有合同去除首尾空白并校验 Unicode 字符数。
     *
     * @param key 当前接口白名单中的 JSON 字段名
     * @param max 允许的最大 Unicode 字符数或数值上界
     * @return 当前操作生成或校验后的文本
     */
    public String text(String key, int max) {
        JsonNode value = node.get(key);
        if (value == null || !value.isString()) {
            throw ApiException.invalid();
        }
        String text = value.asString().strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > max) {
            throw ApiException.invalid();
        }
        return text;
    }

    /**
     * 读取员工编码，严格限制为大写字母开头的受控字符，不进行隐式大小写转换。
     *
     * @param key 当前接口白名单中的 JSON 字段名
     * @return 当前操作生成或校验后的文本
     */
    public String code(String key) {
        String code = text(key, 64);
        if (!Pattern.matches("[A-Z][A-Z0-9_-]{1,63}", code) || !code.equals(node.get(key).asString())) {
            throw ApiException.invalid();
        }
        return code;
    }

    /**
     * 读取业务状态或异常状态码；业务状态只允许当前合同定义的枚举值。
     *
     * @return 合同允许的业务状态
     */
    public String status() {
        String status = text("status", 16);
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) {
            throw ApiException.invalid();
        }
        return status;
    }

    /**
     * 读取能够无损表示为 Long 的 JSON 整数，拒绝字符串、浮点数和科学计数形式。
     *
     * @param key 当前接口白名单中的 JSON 字段名
     * @param zeroAllowed 是否允许合同中的 0 值哨兵
     * @return 按当前合同校验或计算后的数值
     */
    public long number(String key, boolean zeroAllowed) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw ApiException.invalid();
        }
        long number = value.longValue();
        if (number < (zeroAllowed ? 0 : 1)) {
            throw ApiException.invalid();
        }
        return number;
    }

    /**
     * 读取 UTC 时间字段并校验数据库支持的微秒精度。
     *
     * @param key 当前接口白名单中的 JSON 字段名
     * @return 符合 UTC 与数据库精度约束的时间
     */
    public Instant instant(String key) {
        return instantValue(text(key, 40));
    }

    /**
     * 解析带 Z 的 UTC 时间，拒绝在 DATETIME(6) 中会丢失精度的输入。
     *
     * @param value 待校验或转换的受控输入
     * @return 符合 UTC 与数据库精度约束的时间
     */
    public static Instant instantValue(String value) {
        try {
            if (!value.endsWith("Z")) {
                throw ApiException.invalid();
            }
            Instant result = Instant.parse(value);
            // V1 持久化合同使用 DATETIME(6)，超出微秒精度必须拒绝，避免静默截断。
            if (result.getNano() % 1000 != 0) {
                throw ApiException.invalid();
            }
            return result;
        } catch (DateTimeParseException exception) {
            throw ApiException.invalid();
        }
    }

    /**
     * 校验路径或查询参数中的十进制整数，不接受前导零、符号或科学计数法。
     *
     * @param value 待校验或转换的受控输入
     * @param zeroAllowed 是否允许合同中的 0 值哨兵
     * @return 按当前合同校验或计算后的数值
     */
    public static long pathId(String value, boolean zeroAllowed) {
        if (value == null || !value.matches(zeroAllowed ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}")) {
            throw ApiException.invalid();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.invalid();
        }
    }

    /**
     * 校验幂等键长度与可见 ASCII 字符范围。
     *
     * @param value 待校验或转换的受控输入
     * @return 当前操作生成或校验后的文本
     */
    public static String idempotency(String value) {
        if (value == null || !value.matches("[!-~]{8,128}")) {
            throw ApiException.invalid();
        }
        return value;
    }
}
