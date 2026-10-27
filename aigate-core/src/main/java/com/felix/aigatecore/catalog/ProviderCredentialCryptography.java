package com.felix.aigatecore.catalog;

import com.felix.aigatecore.security.CatalogSecuritySettings;
import com.felix.aigatecore.security.CatalogSecuritySettings.KeyMaterial;
import com.felix.aigatecore.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Provider凭证AES-256-GCM与永久签发收据HMAC，不提供公开秘密读取接口。
 *
 * @author AIGate
 */
@Component
public class ProviderCredentialCryptography {

    /**
     * 数据库需要保存的密文与IV，只有Core内部使用。
     *
     * @param ciphertext 包含GCM认证标签的密文
     * @param iv 本次随机12字节IV
     * @param keyId 加密主密钥版本
     * @author AIGate
     */
    public record EncryptedSecret(byte[] ciphertext, byte[] iv, String keyId) {
    }

    private final CatalogSecuritySettings settings;
    private final SecureRandom random = new SecureRandom();

    /**
     * 注入当前及保留历史密钥。
     *
     * @param settings Catalog密钥配置
     */
    public ProviderCredentialCryptography(CatalogSecuritySettings settings) {
        this.settings = settings;
    }

    /**
     * 长度前缀绑定原始UTF-8字节，不能使用分隔符拼接秘密请求。
     */
    private static void bytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    /**
     * AAD绑定所属部署、凭证用途、修订与主密钥版本，防止跨记录搬移密文。
     */
    private static byte[] aad(long deployment, String kind, int revision, String keyId) {
        try (var buffer = new ByteArrayOutputStream();
            var output = new DataOutputStream(buffer)) {
            output.writeLong(deployment);
            bytes(output, kind.getBytes(StandardCharsets.UTF_8));
            output.writeInt(revision);
            bytes(output, keyId.getBytes(StandardCharsets.UTF_8));
            return buffer.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Credential encoding is unavailable");
        }
    }

    /**
     * 原样加密秘密，合法4096字节加上16字节标签不会截断。
     *
     * @param deployment 所属部署
     * @param kind 鉴权类型
     * @param revision 凭证修订
     * @param secret 原始秘密
     * @return 密文、随机IV和主密钥版本
     */
    public EncryptedSecret encrypt(long deployment, String kind, int revision, String secret) {
        if (secret == null) {
            throw ApiException.invalid();
        }
        byte[] plaintext = secret.getBytes(StandardCharsets.UTF_8);
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(secret) || plaintext.length < 1 || plaintext.length > 4096) {
            throw ApiException.invalid();
        }
        try {
            // 每次生成独立随机IV，不能为幂等重放重新执行加密。
            KeyMaterial key = settings.currentEncryption();
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.bytes(), "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(deployment, kind, revision, key.id()));
            return new EncryptedSecret(cipher.doFinal(plaintext), iv, key.id());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Credential encryption is unavailable");
        }
    }

    /**
     * Core内部验证密文完整性，异常不包含秘密、密文、IV或底层原因。
     *
     * @param credential 完整内部持久化记录
     * @return 原秘密；NONE凭证为空
     */
    public String decrypt(ProviderCredentialDO credential) {
        if ("NONE".equals(credential.credentialKind())) {
            return null;
        }
        KeyMaterial key = settings.encryption(credential.encryptionKeyId());
        if (key == null || credential.iv() == null || credential.iv().length != 12
            || credential.encryptedSecret() == null || credential.encryptedSecret().length < 17) {
            throw unavailable();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.bytes(), "AES"),
                new GCMParameterSpec(128, credential.iv()));
            cipher
                .updateAAD(aad(credential.deploymentId(), credential.credentialKind(), credential.credentialRevision(),
                    key.id()));
            byte[] plaintext = cipher.doFinal(credential.encryptedSecret());
            if (plaintext.length < 1 || plaintext.length > 4096) {
                throw unavailable();
            }
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    /**
     * 安全元数据中的当前凭证可用性；不泄漏具体损坏原因。
     *
     * @param credential 当前指针对应的凭证
     * @return 当前状态及密文完整性有效时为true
     */
    public boolean usable(ProviderCredentialDO credential) {
        if (credential == null || !"ACTIVE".equals(credential.status())) {
            return false;
        }
        try {
            decrypt(credential);
            return true;
        } catch (ApiException failure) {
            return false;
        }
    }

    /**
     * 按指定保留版本计算签发收据，不保存请求原文或普通秘密摘要。
     *
     * @param keyId 收据HMAC版本
     * @param deployment 所属部署
     * @param kind 鉴权类型
     * @param revision 凭证修订
     * @param secret 原秘密，NONE为空
     * @param actor 已认证签发者
     * @return HMAC-SHA256摘要
     */
    public byte[] receipt(String keyId, long deployment, String kind, int revision, String secret, long actor) {
        KeyMaterial key = settings.issue(keyId);
        if (key == null) {
            throw unavailable();
        }
        try (var buffer = new ByteArrayOutputStream();
            var output = new DataOutputStream(buffer)) {
            // 固定顺序、大端整型和长度前缀避免不同请求产生同一编码。
            bytes(output, "POST".getBytes(StandardCharsets.UTF_8));
            output.writeLong(deployment);
            bytes(output, kind.getBytes(StandardCharsets.UTF_8));
            output.writeInt(revision);
            output.writeBoolean(secret != null);
            bytes(output, secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8));
            output.writeLong(actor);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.bytes(), "HmacSHA256"));
            return mac.doFinal(buffer.toByteArray());
        } catch (GeneralSecurityException | IOException failure) {
            throw new IllegalStateException("Credential receipt signing is unavailable");
        }
    }

    /**
     * 构造固定的历史材料不可用错误，不把它当作重新签发许可。
     */
    private static ApiException unavailable() {
        return new ApiException(503, "CREDENTIAL_HISTORY_UNAVAILABLE", "Credential history is unavailable");
    }
}
