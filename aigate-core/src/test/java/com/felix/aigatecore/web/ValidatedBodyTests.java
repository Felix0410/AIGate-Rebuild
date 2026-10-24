package com.felix.aigatecore.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 严格输入校验的单元测试，覆盖 JSON 歧义、标量类型、数值边界、UTC 时间和精度约束。
 *
 * @author AIGate
 */
class ValidatedBodyTests {

    /**
     * 将测试 JSON 按 UTF-8 交给严格校验器。
     *
     * @param input 客户端输入的候选 JSON 或查询参数
     * @param fields 当前操作的字段名白名单或安全审计字段名列表
     * @return 按字段白名单完成解析的请求正文
     */
    private ValidatedBody body(String input, String... fields) {
        return ValidatedBody.parse(input.getBytes(StandardCharsets.UTF_8), fields);
    }

    /**
     * 拒绝非对象、重复键、尾随 JSON、未知字段和显式 null。
     */
    @Test
    @DisplayName("拒绝非对象、重复键、尾随 JSON、未知字段和显式 null。")
    void rejectsAmbiguousJsonAndUnknownOrNullFields() {
        for (String input : List.of("[]", "null", "{} {}", "{\"name\":null}", "{\"name\":\"a\",\"name\":\"b\"}",
            "{\"extra\":true}")) {
            assertThrows(ApiException.class, () -> body(input, "name"));
        }
    }

    /**
     * 拒绝把字符串、布尔或浮点数当作整数 ID，并覆盖 Long 溢出。
     */
    @Test
    @DisplayName("拒绝把字符串、布尔或浮点数当作整数 ID，并覆盖 Long 溢出。")
    void rejectsScalarCoercionAndNumericIdAmbiguity() {
        for (String value : List.of("\"1\"", "1.0", "1e0", "true", "-1", "0", "9223372036854775808")) {
            assertThrows(ApiException.class, () -> body("{\"id\":" + value + "}", "id").number("id", false));
        }
        assertEquals(1, body("{\"id\":1}", "id").number("id", false));
        assertThrows(ApiException.class, () -> body("{\"name\":123}", "name").text("name", 80));
    }

    /**
     * 验证 ID、从零开始的版本号及幂等键字符范围。
     */
    @Test
    @DisplayName("验证 ID、从零开始的版本号及幂等键字符范围。")
    void validatesPathIdsVersionsAndIdempotencyKeys() {
        for (String input : List.of("-1", "0", "1e3", "01", "9223372036854775808")) {
            assertThrows(ApiException.class, () -> ValidatedBody.pathId(input, false));
        }
        assertEquals(0, ValidatedBody.pathId("0", true));
        assertEquals(0, body("{\"expectedVersion\":0}", "expectedVersion").number("expectedVersion", true));
        for (String input : List.of("short", "space key", "newline\nkey")) {
            assertThrows(ApiException.class, () -> ValidatedBody.idempotency(input));
        }
    }

    /**
     * 验证名称规范化、编码严格匹配及 UTC 微秒精度。
     */
    @Test
    @DisplayName("验证名称规范化、编码严格匹配及 UTC 微秒精度。")
    void normalizesNamesButKeepsEmployeeCodesStrictAndUtcDatesLossless() {
        assertEquals("Team", body("{\"name\":\"  Team  \"}", "name").text("name", 80));
        assertThrows(ApiException.class,
            () -> body("{\"employeeCode\":\" lower \"}", "employeeCode").code("employeeCode"));
        assertThrows(ApiException.class, () -> ValidatedBody.instantValue("2026-10-24T00:00:00+08:00"));
        assertThrows(ApiException.class, () -> ValidatedBody.instantValue("2026-10-24T00:00:00.123456789Z"));
    }
}
