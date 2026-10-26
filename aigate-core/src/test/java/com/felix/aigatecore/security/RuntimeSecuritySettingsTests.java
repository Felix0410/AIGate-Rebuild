package com.felix.aigatecore.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 运行 Pepper 的用途隔离、启动校验和精确轮换截止测试。
 *
 * @author AIGate
 */
class RuntimeSecuritySettingsTests {

    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    private final SecuritySettings management = mock(SecuritySettings.class);

    /**
     * 不同填充值代表不同测试材料，不引用开发者环境中的真实 Pepper。
     */
    private String material(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 准备两种用途各自完整的当前版本配置。
     */
    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("AIGATE_AGENT_PEPPER_KEY_ID", "agent-v1")
            .withProperty("AIGATE_AGENT_PEPPER", material(1))
            .withProperty("AIGATE_EMPLOYEE_PEPPER_KEY_ID", "employee-v1")
            .withProperty("AIGATE_EMPLOYEE_PEPPER", material(2));
    }

    /**
     * 为当前用途增加历史版本，截止时间统一由固定时钟验证。
     */
    private MockEnvironment previous(MockEnvironment env, String prefix, int value) {
        return env.withProperty(prefix + "_PREVIOUS_PEPPER_KEY_ID", "old-version")
            .withProperty(prefix + "_PREVIOUS_PEPPER", material(value))
            .withProperty(prefix + "_PREVIOUS_VALID_UNTIL", NOW.toString());
    }

    /**
     * P2-17：缺少当前配置、部分历史配置、非法编码或短 Pepper 都拒绝启动。
     */
    @Test
    @DisplayName("P2-17：缺少当前配置、部分历史配置、非法编码或短 Pepper 都拒绝启动")
    void rejectsMissingMalformedOrPartialConfiguration() {
        for (String prefix : List.of("AIGATE_AGENT", "AIGATE_EMPLOYEE")) {
            // 1、只保留 previous 不能替代当前版本；任何当前字段缺失均拒绝启动。
            for (String field : List.of("_PEPPER", "_PEPPER_KEY_ID")) {
                MockEnvironment env = previous(environment(), prefix, 3);
                env.setProperty(prefix + field, "");
                assertThrows(IllegalStateException.class,
                    () -> new RuntimeSecuritySettings(env, management, Clock.fixed(NOW, ZoneOffset.UTC)));
            }

            // 2、非法 Base64、短材料、缺少历史截止或只有截止时间均不能被静默忽略。
            for (String value : List.of("%%%", Base64.getEncoder().encodeToString(new byte[8]))) {
                MockEnvironment env = environment().withProperty(prefix + "_PEPPER", value);
                assertThrows(IllegalStateException.class,
                    () -> new RuntimeSecuritySettings(env, management, Clock.fixed(NOW, ZoneOffset.UTC)));
            }
            for (String field : List.of("_PREVIOUS_PEPPER", "_PREVIOUS_PEPPER_KEY_ID", "_PREVIOUS_VALID_UNTIL")) {
                MockEnvironment env = environment().withProperty(prefix + field,
                    field.equals("_PREVIOUS_PEPPER") ? material(3) : "old-version");
                assertThrows(IllegalStateException.class,
                    () -> new RuntimeSecuritySettings(env, management, Clock.fixed(NOW, ZoneOffset.UTC)));
            }
        }
    }

    /**
     * P2-17：跨用途复用当前或历史材料、更名复用管理 Pepper 均拒绝启动。
     */
    @Test
    @DisplayName("P2-17：跨用途复用当前或历史材料、更名复用管理 Pepper 均拒绝启动")
    void rejectsReusedMaterialAcrossAllPurposesAndVersions() {
        // 1、运行用途之间按实际字节比较，已过期的历史配置也不能跨用途复用。
        for (MockEnvironment env : List.of(environment().withProperty("AIGATE_EMPLOYEE_PEPPER", material(1)),
            previous(environment(), "AIGATE_AGENT", 2),
            previous(previous(environment(), "AIGATE_AGENT", 3), "AIGATE_EMPLOYEE", 3),
            previous(environment(), "AIGATE_AGENT", 1))) {
            assertThrows(IllegalStateException.class,
                () -> new RuntimeSecuritySettings(env, management, Clock.fixed(NOW, ZoneOffset.UTC)));
        }

        // 2、管理配置已经持有当前及历史版本，这里确认两种运行用途都调用该隔离检查。
        when(management.usesPepper(any(byte[].class))).thenAnswer(
            invocation -> Arrays.equals(Base64.getDecoder().decode(material(1)), invocation.getArgument(0)));
        assertThrows(IllegalStateException.class,
            () -> new RuntimeSecuritySettings(environment(), management, Clock.fixed(NOW, ZoneOffset.UTC)));
        when(management.usesPepper(any(byte[].class))).thenAnswer(
            invocation -> Arrays.equals(Base64.getDecoder().decode(material(2)), invocation.getArgument(0)));
        assertThrows(IllegalStateException.class,
            () -> new RuntimeSecuritySettings(environment(), management, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    /**
     * P2-17：历史 Pepper 截止前 1us 接受，恰到截止只接受当前版本。
     */
    @Test
    @DisplayName("P2-17：历史 Pepper 截止前 1us 接受，恰到截止只接受当前版本")
    void previousVersionExpiresAtExactDeadline() {
        MockEnvironment env = previous(previous(environment(), "AIGATE_AGENT", 3), "AIGATE_EMPLOYEE", 4);
        RuntimeSecuritySettings before = new RuntimeSecuritySettings(env, management,
            Clock.fixed(NOW.minus(1, ChronoUnit.MICROS), ZoneOffset.UTC));
        RuntimeSecuritySettings deadline = new RuntimeSecuritySettings(env, management,
            Clock.fixed(NOW, ZoneOffset.UTC));
        for (boolean agentKey : List.of(true, false)) {
            assertEquals(2, before.accepted(agentKey).size());
            assertEquals(1, deadline.accepted(agentKey).size());
            assertEquals(agentKey ? "agent-v1" : "employee-v1", deadline.current(agentKey).id());
        }
    }
}
