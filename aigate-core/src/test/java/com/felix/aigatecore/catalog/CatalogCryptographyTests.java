package com.felix.aigatecore.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.felix.aigatecore.security.CatalogSecuritySettings;
import com.felix.aigatecore.security.RuntimeSecuritySettings;
import com.felix.aigatecore.security.SecuritySettings;
import com.felix.aigatecore.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 使用真实JCA AES-GCM/HMAC验证秘密边界、篡改、历史版本及启动用途隔离。
 *
 * @author AIGate
 */
class CatalogCryptographyTests {

    /** 固定测试材料只在测试内使用，不读取开发环境秘密。 */
    private String material(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 准备两个独立用途的当前版本，历史版本按场景追加。 */
    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("AIGATE_PROVIDER_ENCRYPTION_KEY_ID", "enc-v1")
            .withProperty("AIGATE_PROVIDER_ENCRYPTION_KEY", material(1))
            .withProperty("AIGATE_PROVIDER_ISSUE_PEPPER_KEY_ID", "issue-v1")
            .withProperty("AIGATE_PROVIDER_ISSUE_PEPPER", material(2));
    }

    /** 使用真实配置校验器；P1/P2隔离检查由测试指定的受控Mock给出。 */
    private CatalogSecuritySettings settings(MockEnvironment environment) {
        return new CatalogSecuritySettings(environment, mock(SecuritySettings.class),
            mock(RuntimeSecuritySettings.class));
    }

    /** 组装内部记录，测试对字节比较只输出真假，不能让JUnit失败消息泄露原秘密。 */
    private ProviderCredentialDO row(ProviderCredentialCryptography.EncryptedSecret encrypted, long deployment,
        int revision) {
        return new ProviderCredentialDO(1, deployment, "BEARER", encrypted.ciphertext(), encrypted.iv(),
            encrypted.keyId(),
            revision, 4, "test-receipt-key", new byte[32], "issue-v1", "ACTIVE", Instant.EPOCH, null, null);
    }

    /**
     * 真实加密/解密覆盖UTF-8物理字节边界和不去空白。
     */
    @Test
    @DisplayName("P3-26、37：4096字节合法、4097字节拒绝、随机IV与原字节保留")
    void encryptsExactMaximumUtf8BytesWithUniqueNonceAndNoTrimming() {
        var crypto = new ProviderCredentialCryptography(settings(environment()));
        for (String secret : List.of("A".repeat(4096), "中".repeat(1365) + "A", "  fake-provider-value\n")) {
            var encrypted = crypto.encrypt(2, "BEARER", 3, secret);
            assertEquals(secret.getBytes(StandardCharsets.UTF_8).length + 16, encrypted.ciphertext().length);
            assertEquals(12, encrypted.iv().length);
            assertTrue(MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                crypto.decrypt(row(encrypted, 2, 3)).getBytes(StandardCharsets.UTF_8)));
            assertFalse(Arrays.equals(secret.getBytes(StandardCharsets.UTF_8), encrypted.ciphertext()));
            var second = crypto.encrypt(2, "BEARER", 3, secret);
            assertFalse(Arrays.equals(encrypted.iv(), second.iv()));
        }
        for (String secret : List.of("", "A".repeat(4097), "中".repeat(1365) + "AA", "\uD800")) {
            assertThrows(ApiException.class, () -> crypto.encrypt(2, "BEARER", 3, secret));
        }
    }

    /**
     * GCM认证标签、所属部署、修订和主密钥都不能被替换。
     */
    @Test
    @DisplayName("P3-28、37：tag/AAD/密钥篡改和未知keyId均安全拒绝")
    void authenticatedEncryptionRejectsTamperingAndCrossRecordMoves() {
        var crypto = new ProviderCredentialCryptography(settings(environment()));
        var encrypted = crypto.encrypt(2, "BEARER", 3, "fake-provider-test-value");
        byte[] damaged = encrypted.ciphertext().clone();
        damaged[damaged.length - 1] ^= 1;
        var corrupt = new ProviderCredentialCryptography.EncryptedSecret(damaged, encrypted.iv(), encrypted.keyId());
        for (ProviderCredentialDO invalid : List.of(row(corrupt, 2, 3), row(encrypted, 99, 3), row(encrypted, 2, 4),
            row(new ProviderCredentialCryptography.EncryptedSecret(encrypted.ciphertext(), encrypted.iv(), "unknown"),
                2, 3))) {
            assertEquals("CREDENTIAL_HISTORY_UNAVAILABLE",
                assertThrows(ApiException.class, () -> crypto.decrypt(invalid)).code());
            assertFalse(crypto.usable(invalid));
        }
        var wrongKey = new ProviderCredentialCryptography(
            settings(environment().withProperty("AIGATE_PROVIDER_ENCRYPTION_KEY", material(3))));
        assertThrows(ApiException.class, () -> wrongKey.decrypt(row(encrypted, 2, 3)));
    }

    /**
     * 永久收据绑定全部原始请求字段，不能靠分隔符或NONE存在性混淆。
     */
    @Test
    @DisplayName("P3-38：签发HMAC绑定部署、类型、版本、秘密原字节和签发者")
    void receiptCanonicalEncodingSeparatesAllBusinessInputs() {
        var crypto = new ProviderCredentialCryptography(settings(environment()));
        byte[] original = crypto.receipt("issue-v1", 2, "BEARER", 3, "fake-value", 4);
        assertTrue(MessageDigest.isEqual(original, crypto.receipt("issue-v1", 2, "BEARER", 3, "fake-value", 4)));
        for (byte[] other : List.of(crypto.receipt("issue-v1", 9, "BEARER", 3, "fake-value", 4),
            crypto.receipt("issue-v1", 2, "API_KEY_HEADER", 3, "fake-value", 4),
            crypto.receipt("issue-v1", 2, "BEARER", 5, "fake-value", 4),
            crypto.receipt("issue-v1", 2, "BEARER", 3, "fake-value ", 4),
            crypto.receipt("issue-v1", 2, "BEARER", 3, "fake-value", 8))) {
            assertFalse(MessageDigest.isEqual(original, other));
        }
        assertFalse(MessageDigest.isEqual(crypto.receipt("issue-v1", 2, "NONE", 3, null, 4),
            crypto.receipt("issue-v1", 2, "NONE", 3, "", 4)));
    }

    /**
     * 轮换后只按记录中的历史版本解密和追验，未保留材料不能导致重新签发。
     */
    @Test
    @DisplayName("P3-39：新版本签发、历史材料追验和缺失历史失败关闭")
    void preservesHistoricalCiphertextAndReceiptAfterKeyRotation() {
        var old = new ProviderCredentialCryptography(settings(environment()));
        var encrypted = old.encrypt(2, "BEARER", 3, "fake-provider-test-value");
        byte[] hmac = old.receipt("issue-v1", 2, "BEARER", 3, "fake-provider-test-value", 4);
        MockEnvironment rotated = environment().withProperty("AIGATE_PROVIDER_ENCRYPTION_KEY_ID", "enc-v2")
            .withProperty("AIGATE_PROVIDER_ENCRYPTION_KEY", material(3))
            .withProperty("AIGATE_PROVIDER_PREVIOUS_ENCRYPTION_KEY_ID", "enc-v1")
            .withProperty("AIGATE_PROVIDER_PREVIOUS_ENCRYPTION_KEY", material(1))
            .withProperty("AIGATE_PROVIDER_ISSUE_PEPPER_KEY_ID", "issue-v2")
            .withProperty("AIGATE_PROVIDER_ISSUE_PEPPER", material(4))
            .withProperty("AIGATE_PROVIDER_ISSUE_PREVIOUS_PEPPER_KEY_ID", "issue-v1")
            .withProperty("AIGATE_PROVIDER_ISSUE_PREVIOUS_PEPPER", material(2));
        var current = new ProviderCredentialCryptography(settings(rotated));
        assertTrue("fake-provider-test-value".equals(current.decrypt(row(encrypted, 2, 3))));
        assertTrue(
            MessageDigest.isEqual(hmac, current.receipt("issue-v1", 2, "BEARER", 3, "fake-provider-test-value", 4)));
        assertEquals("enc-v2", current.encrypt(2, "BEARER", 4, "new-fake-value").keyId());
        var removed = new ProviderCredentialCryptography(
            settings(environment().withProperty("AIGATE_PROVIDER_ENCRYPTION_KEY_ID", "enc-v2")
                .withProperty("AIGATE_PROVIDER_ENCRYPTION_KEY", material(3))
                .withProperty("AIGATE_PROVIDER_ISSUE_PEPPER_KEY_ID", "issue-v2")
                .withProperty("AIGATE_PROVIDER_ISSUE_PEPPER", material(4))));
        assertThrows(ApiException.class, () -> removed.decrypt(row(encrypted, 2, 3)));
        assertThrows(ApiException.class,
            () -> removed.receipt("issue-v1", 2, "BEARER", 3, "fake-provider-test-value", 4));
    }

    /**
     * 配置无效必须拒绝启动，异常消息不带秘密材料。
     */
    @Test
    @DisplayName("P3-39：缺失、非法编码、部分历史、用途复用或更名复用都拒绝启动")
    void configurationRejectsIncompleteOrReusedCurrentAndHistoricalMaterial() {
        for (MockEnvironment invalid : List.of(environment().withProperty("AIGATE_PROVIDER_ENCRYPTION_KEY", ""),
            environment().withProperty("AIGATE_PROVIDER_ENCRYPTION_KEY", "%%%"),
            environment().withProperty("AIGATE_PROVIDER_ENCRYPTION_KEY",
                Base64.getEncoder().encodeToString(new byte[33])),
            environment().withProperty("AIGATE_PROVIDER_ISSUE_PEPPER", Base64.getEncoder().encodeToString(new byte[8])),
            environment().withProperty("AIGATE_PROVIDER_ISSUE_PEPPER", material(1)),
            environment().withProperty("AIGATE_PROVIDER_PREVIOUS_ENCRYPTION_KEY_ID", "enc-v0"),
            environment().withProperty("AIGATE_PROVIDER_ISSUE_PREVIOUS_PEPPER", material(3)),
            environment().withProperty("AIGATE_PROVIDER_PREVIOUS_ENCRYPTION_KEY_ID", "enc-v0")
                .withProperty("AIGATE_PROVIDER_PREVIOUS_ENCRYPTION_KEY", material(2)))) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> settings(invalid));
            assertFalse(failure.toString().contains(material(1)));
        }
        SecuritySettings management = mock(SecuritySettings.class);
        RuntimeSecuritySettings runtime = mock(RuntimeSecuritySettings.class);
        when(management.usesPepper(any(byte[].class))).thenAnswer(
            invocation -> Arrays.equals(Base64.getDecoder().decode(material(1)), invocation.getArgument(0)));
        assertThrows(IllegalStateException.class,
            () -> new CatalogSecuritySettings(environment(), management, runtime));
        when(runtime.usesPepper(any(byte[].class))).thenReturn(true);
        assertThrows(IllegalStateException.class,
            () -> new CatalogSecuritySettings(environment(), mock(SecuritySettings.class), runtime));
    }
}
