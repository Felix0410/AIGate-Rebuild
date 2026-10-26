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
 * JSON 正文解析工具，检查对象结构、字段类型、允许字段和取值范围。
 *
 * @author AIGate
 */
public final class ValidatedBody {

    // 解析阶段即拒绝重复键和尾随 JSON，避免不同解析器对同一请求产生不同解释。
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private final JsonNode node;

    /**
     * 保存已经检查过结构的 JSON 对象。
     *
     * @param node 已验证为对象的 JSON 树
     */
    private ValidatedBody(JsonNode node) {
        this.node = node;
    }

    /**
     * 解析并校验完整 JSON 对象，拒绝空正文、重复属性、未知属性和显式 null。
     *
     * @param bytes UTF-8 编码的 JSON 正文
     * @param allowed 当前接口允许的字段名
     * @return 按字段白名单完成解析的请求正文
     */
    public static ValidatedBody parse(byte[] bytes, String... allowed) {
        // 1、检查正文非空，且不超过 64 KiB。
        if (bytes == null || bytes.length == 0 || bytes.length > ApiContractConstants.MAX_JSON_BODY_BYTES) {
            throw ApiException.invalid();
        }

        try {
            // 2、读取 JSON 对象，拒绝重复键、尾随内容和白名单以外的字段。
            JsonNode node = JSON.readTree(bytes);

            if (node == null || !node.isObject() || !Set.of(allowed).containsAll(node.propertyNames())) {
                throw ApiException.invalid();
            }

            // 3、拒绝显式 null，PATCH 不修改的字段应直接省略。
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
     * @return 提供了该字段时为 true，否则为 false
     */
    public boolean has(String key) {
        // 检查客户端是否提交了该字段，供 PATCH 和审计使用。
        return node.has(key);
    }

    /**
     * 读取真实字符串字段，按接口约定去除首尾空白并校验 Unicode 字符数。
     *
     * @param key 当前接口白名单中的 JSON 字段名
     * @param max 允许的最大字符数
     * @return 去除首尾空白并检查长度后的字符串
     */
    public String text(String key, int max) {
        // 1、读取真实字符串类型，不把数字或布尔值转为字符串。
        JsonNode value = node.get(key);

        if (value == null || !value.isString()) {
            throw ApiException.invalid();
        }

        // 2、去除首尾空白，再按 Unicode 码点检查长度。
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
     * @return 检查通过的员工编码
     */
    public String code(String key) {
        // 读取员工编码，检查大写格式，且不能靠去空白修正原始输入。
        String code = text(key, 64);

        if (!Pattern.matches("[A-Z][A-Z0-9_-]{1,63}", code) || !code.equals(node.get(key).asString())) {
            throw ApiException.invalid();
        }

        return code;
    }

    /**
     * 读取业务状态或异常状态码；业务状态只允许当前接口约定的枚举值。
     *
     * @return ACTIVE 或 DISABLED
     */
    public String status() {
        String status = text("status", 16);

        // 只接受 ACTIVE 或 DISABLED 状态。
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
     * @return 检查类型和下界后的整数
     */
    public long number(String key, boolean zeroAllowed) {
        // 1、要求值为 JSON 整数，且能无损转换为 Long。
        JsonNode value = node.get(key);

        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw ApiException.invalid();
        }

        // 2、检查数值下界，只有版本号等指定字段允许为 0。
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
     * @param value 待检查或转换的值
     * @return 符合 UTC 与数据库精度约束的时间
     */
    public static Instant instantValue(String value) {
        try {
            // 1、要求时间以 Z 结尾，表示 UTC。
            if (!value.endsWith("Z")) {
                throw ApiException.invalid();
            }

            // 2、解析时间，拒绝超出 MySQL DATETIME(6) 微秒精度的值。
            Instant result = Instant.parse(value);

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
     * @param value 待检查或转换的值
     * @param zeroAllowed 是否允许合同中的 0 值哨兵
     * @return 检查格式和范围后的 ID 或允许的 0 值
     */
    public static long pathId(String value, boolean zeroAllowed) {
        // 1、检查十进制文本，拒绝符号、前导零和科学计数法。
        if (value == null || !value.matches(zeroAllowed ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}")) {
            throw ApiException.invalid();
        }

        try {
            // 2、转换为 Long，超出范围时返回参数错误。
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.invalid();
        }
    }

    /**
     * 校验幂等键长度与可见 ASCII 字符范围。
     *
     * @param value 待检查或转换的值
     * @return 检查通过的幂等键
     */
    public static String idempotency(String value) {
        // 幂等键长度为 8～128，只允许可见 ASCII 字符，不允许空白或控制字符。
        if (value == null || !value.matches("[!-~]{8,128}")) {
            throw ApiException.invalid();
        }

        return value;
    }
}
