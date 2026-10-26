package com.felix.aigatecore.security;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Agent 与员工运行凭证的独立 Pepper 配置，不与管理凭证共用密钥材料。
 *
 * @author AIGate
 */
@Component
public class RuntimeSecuritySettings {

    private final PepperWindow agent;

    private final PepperWindow employee;

    private final Clock clock;

    /**
     * 当前与历史 Pepper 的接收窗口，历史配置只用于验证已有凭证。
     *
     * @param current 当前密钥
     * @param previous 历史密钥，可为 null
     * @param until 历史截止时间，可为 null
     * @author AIGate
     */
    private record PepperWindow(SecuritySettings.Pepper current, SecuritySettings.Pepper previous, Instant until) {
    }

    /**
     * 读取两类运行 Pepper，并检查当前及历史材料不能跨用途复用。
     *
     * @param environment Spring 配置来源
     * @param management 管理凭证配置
     * @param clock 服务端时钟
     */
    public RuntimeSecuritySettings(Environment environment, SecuritySettings management, Clock clock) {
        this.clock = clock;
        this.agent = readWindow(environment, "AIGATE_AGENT");
        this.employee = readWindow(environment, "AIGATE_EMPLOYEE");

        // 1、收集当前及历史材料，过期配置也不能与其他用途共享密钥。
        List<SecuritySettings.Pepper> versions = new ArrayList<>();
        versions.add(agent.current());
        versions.add(employee.current());
        if (agent.previous() != null) {
            versions.add(agent.previous());
        }
        if (employee.previous() != null) {
            versions.add(employee.previous());
        }

        // 2、比较实际字节，不能通过更换版本名或 Base64 写法规避用途隔离。
        for (int i = 0; i < versions.size(); i++) {
            if (management.usesPepper(versions.get(i).bytes())) {
                throw invalidConfiguration();
            }
            for (int j = 0; j < i; j++) {
                if (Arrays.equals(versions.get(i).bytes(), versions.get(j).bytes())) {
                    throw invalidConfiguration();
                }
            }
        }
    }

    /**
     * 构造固定配置错误，不能包含环境变量原文或解码异常原因。
     */
    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException("Independent runtime peppers and explicit rotation windows are required");
    }

    /**
     * 读取当前材料和完整历史窗口，任何部分配置都不能悄悄回退。
     */
    private static PepperWindow readWindow(Environment environment, String prefix) {
        try {
            SecuritySettings.Pepper current = decode(environment.getProperty(prefix + "_PEPPER_KEY_ID"),
                environment.getProperty(prefix + "_PEPPER"));
            String previousValue = environment.getProperty(prefix + "_PREVIOUS_PEPPER", "");
            String previousId = environment.getProperty(prefix + "_PREVIOUS_PEPPER_KEY_ID", "");
            String previousUntil = environment.getProperty(prefix + "_PREVIOUS_VALID_UNTIL", "");
            if (previousValue.isEmpty()) {
                if (!previousId.isEmpty() || !previousUntil.isEmpty()) {
                    throw invalidConfiguration();
                }
                return new PepperWindow(current, null, null);
            }

            SecuritySettings.Pepper previous = decode(previousId, previousValue);
            if (previous.id().equals(current.id()) || previousUntil.isEmpty()) {
                throw invalidConfiguration();
            }
            return new PepperWindow(current, previous, Instant.parse(previousUntil));
        } catch (RuntimeException failure) {
            // 固定错误不携带配置原文和异常原因，避免启动日志泄露密钥。
            throw invalidConfiguration();
        }
    }

    /**
     * 校验版本名和至少 32 字节的 Base64 材料。
     */
    private static SecuritySettings.Pepper decode(String id, String encoded) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}") || encoded == null) {
            throw invalidConfiguration();
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length < 32) {
            throw invalidConfiguration();
        }
        return new SecuritySettings.Pepper(id, bytes);
    }

    /**
     * 获取新签发凭证使用的 Pepper。
     *
     * @param agentKey 是否为 Agent 凭证
     * @return 当前用途的 Pepper
     */
    public SecuritySettings.Pepper current(boolean agentKey) {
        return (agentKey ? agent : employee).current();
    }

    /**
     * 获取当前可用于认证的 Pepper，不接受过期历史版本。
     *
     * @param agentKey 是否为 Agent 凭证
     * @return 当前及允许窗口内的历史 Pepper
     */
    public List<SecuritySettings.Pepper> accepted(boolean agentKey) {
        PepperWindow window = agentKey ? agent : employee;
        return window.previous() != null && clock.instant().isBefore(window.until())
            ? List.of(window.current(), window.previous())
            : List.of(window.current());
    }
}
