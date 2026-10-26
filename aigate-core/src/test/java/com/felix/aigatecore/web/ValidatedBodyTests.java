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
     * @param fields 允许的字段名或本次修改的字段名
     * @return 解析后的 JSON 正文
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
        // 逐个传入非对象、重复键、尾随 JSON、未知字段和 null，要求全部拒绝。
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
        // 1、拒绝非整数类型、非正数和超出 Long 上界的 ID。
        for (String value : List.of("\"1\"", "1.0", "1e0", "true", "-1", "0", "9223372036854775808")) {
            assertThrows(ApiException.class, () -> body("{\"id\":" + value + "}", "id").number("id", false));
        }

        // 2、确认合法整数可读取，数字不能作为名称字符串。
        assertEquals(1, body("{\"id\":1}", "id").number("id", false));
        assertThrows(ApiException.class, () -> body("{\"name\":123}", "name").text("name", 80));
    }

    /**
     * 验证 ID、从零开始的版本号及幂等键字符范围。
     */
    @Test
    @DisplayName("验证 ID、从零开始的版本号及幂等键字符范围。")
    void validatesPathIdsVersionsAndIdempotencyKeys() {
        // 1、拒绝非法路径 ID。
        for (String input : List.of("-1", "0", "1e3", "01", "9223372036854775808")) {
            assertThrows(ApiException.class, () -> ValidatedBody.pathId(input, false));
        }

        // 2、确认版本号允许为 0。
        assertEquals(0, ValidatedBody.pathId("0", true));
        assertEquals(0, body("{\"expectedVersion\":0}", "expectedVersion").number("expectedVersion", true));

        // 3、拒绝过短、含空白和换行的幂等键。
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
        // 1、确认名称会去除首尾空白。
        assertEquals("Team", body("{\"name\":\"  Team  \"}", "name").text("name", 80));

        // 2、检查员工编码不能靠去空白修正，时间必须为 UTC 且不超过微秒精度。
        assertThrows(ApiException.class,
            () -> body("{\"employeeCode\":\" lower \"}", "employeeCode").code("employeeCode"));

        assertThrows(ApiException.class, () -> ValidatedBody.instantValue("2026-10-24T00:00:00+08:00"));
        assertThrows(ApiException.class, () -> ValidatedBody.instantValue("2026-10-24T00:00:00.123456789Z"));
    }
}
