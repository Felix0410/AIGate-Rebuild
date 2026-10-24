package com.felix.aigatecore.security;

import com.aigate.contracts.ApiContractConstants;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 管理密钥的安全生成和摘要计算工具，使用 CSPRNG 随机数及 HMAC-SHA256。
 *
 * @author AIGate
 */
@Component
public class CredentialCodec {

    private final SecureRandom random = new SecureRandom();

    /**
     * 使用 32 字节安全随机数生成带 mgt_ 前缀的无填充 Base64URL 密钥。
     *
     * @return 当前操作生成或校验后的文本
     */
    public String newManagementKey() {
        byte[] bytes = new byte[ApiContractConstants.SECRET_RANDOM_BYTES];
        random.nextBytes(bytes);
        return "mgt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 计算管理密钥的 HMAC-SHA256 摘要，原始密钥不进入数据库。
     *
     * @param token 待校验的原始凭证，只允许在当前调用内使用
     * @param pepper 独立 HMAC 密钥及其版本
     * @return 管理凭证的 HMAC-SHA256 摘要字节
     */
    public byte[] digest(String token, SecuritySettings.Pepper pepper) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.bytes(), "HmacSHA256"));
            return mac.doFinal(token.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC unavailable");
        }
    }
}
