package com.felix.aigatecore;

import com.felix.aigatecore.identity.IdentityMapper;
import com.felix.aigatecore.catalog.CatalogMapper;
import com.felix.aigatecore.runtime.RuntimeIdentityMapper;

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

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext context;

    @MockitoBean
    IdentityMapper identityMapper;

    @MockitoBean
    RuntimeIdentityMapper runtimeIdentityMapper;

    @MockitoBean
    CatalogMapper catalogMapper;

    @MockitoBean
    com.felix.aigatecore.finance.FinanceMapper financeMapper;

    @MockitoBean
    com.felix.aigatecore.finance.GrantMapper grantMapper;

    /**
     * 为装配测试生成临时安全配置，禁止复用开发环境真实密钥。
     *
     * @param registry 装配测试动态属性注册入口
     */
    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add("AIGATE_COMPANY_ZONE_ID", () -> "Asia/Shanghai");
        // 为装配测试生成独立随机凭证，不使用开发环境密钥。
        var random = new SecureRandom();
        byte[] service = new byte[32];
        byte[] pepper = new byte[32];
        random.nextBytes(service);
        random.nextBytes(pepper);
        registry.add("AIGATE_SERVICE_TOKEN",
            () -> "svc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(service));
        registry.add("AIGATE_MANAGEMENT_PEPPER", () -> Base64.getEncoder().encodeToString(pepper));
        for (String purpose : java.util.List.of("PROVIDER_ENCRYPTION_KEY", "PROVIDER_ISSUE_PEPPER")) {
            byte[] material = new byte[32];
            random.nextBytes(material);
            registry.add("AIGATE_" + purpose + (purpose.endsWith("PEPPER") ? "_KEY_ID" : "_ID"),
                () -> purpose.toLowerCase(java.util.Locale.ROOT));
            registry.add("AIGATE_" + purpose, () -> Base64.getEncoder().encodeToString(material));
        }
        registry.add("AIGATE_CATALOG_APPROVALS_FILE", () -> "");
        for (String purpose : java.util.List.of("AGENT", "EMPLOYEE")) {
            byte[] runtime = new byte[32];
            random.nextBytes(runtime);
            registry.add("AIGATE_" + purpose + "_PEPPER_KEY_ID",
                () -> purpose.toLowerCase(java.util.Locale.ROOT) + "-v1");
            registry.add("AIGATE_" + purpose + "_PEPPER", () -> Base64.getEncoder().encodeToString(runtime));
        }
    }

    /**
     * 验证应用组件能够装配；本用例不代替真实数据库或网络验收。
     */
    @Test
    @DisplayName("验证应用组件能够装配；本用例不代替真实数据库或网络验收。")
    void contextLoads() {
        // 默认部署不可装配Fake账务门面，测试profile不能因普通启动而自动启用。
        org.junit.jupiter.api.Assertions.assertFalse(context.containsBean("fakeFinanceFacade"));
    }
}
