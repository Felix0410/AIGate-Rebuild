package com.felix.aigatecore;

import com.felix.aigatecore.identity.IdentityMapper;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Core 应用装配测试；此测试隔离数据库，真实 MySQL 事务由端到端套件验证。
 *
 * @author AIGate
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
class AigateCoreApplicationTests {

    @MockitoBean
    IdentityMapper identityMapper;

    /**
     * 为装配测试生成临时安全配置，禁止复用开发环境真实密钥。
     *
     * @param registry 装配测试动态属性注册入口
     */
    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        var random = new SecureRandom();
        byte[] service = new byte[32];
        byte[] pepper = new byte[32];
        random.nextBytes(service);
        random.nextBytes(pepper);
        registry.add("AIGATE_SERVICE_TOKEN",
            () -> "svc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(service));
        registry.add("AIGATE_MANAGEMENT_PEPPER", () -> Base64.getEncoder().encodeToString(pepper));
    }

    /**
     * 验证应用组件能够装配；本用例不代替真实数据库或网络验收。
     */
    @Test
    @DisplayName("验证应用组件能够装配；本用例不代替真实数据库或网络验收。")
    void contextLoads() {
    }
}
