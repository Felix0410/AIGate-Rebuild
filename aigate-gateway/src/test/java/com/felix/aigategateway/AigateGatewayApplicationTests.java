package com.felix.aigategateway;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Gateway 应用装配测试，验证路由和安全配置可以在无数据库依赖时创建。
 *
 * @author AIGate
 */
@SpringBootTest
class AigateGatewayApplicationTests {

    /**
     * 为装配测试生成临时安全配置，禁止复用开发环境真实密钥。
     *
     * @param registry 装配测试动态属性注册入口
     */
    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        registry.add("AIGATE_SERVICE_TOKEN",
            () -> "svc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /**
     * 验证应用组件能够装配；本用例不代替真实数据库或网络验收。
     */
    @Test
    @DisplayName("验证应用组件能够装配；本用例不代替真实数据库或网络验收。")
    void contextLoads() {
    }
}
