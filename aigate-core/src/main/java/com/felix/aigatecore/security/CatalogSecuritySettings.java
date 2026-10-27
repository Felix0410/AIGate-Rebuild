package com.felix.aigatecore.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Provider密文主密钥与签发收据Pepper，当前及历史材料必须与其它用途独立。
 *
 * @author AIGate
 */
@Component
public class CatalogSecuritySettings {

    /**
     * Core内存中的密钥材料，禁止序列化或记录日志。
     *
     * @param id 稳定密钥版本标识
     * @param bytes 解码后的材料
     * @author AIGate
     */
    public record KeyMaterial(String id, byte[] bytes) {
    }

    private final Map<String, KeyMaterial> encryption;
    private final Map<String, KeyMaterial> issue;
    private final KeyMaterial currentEncryption;
    private final KeyMaterial currentIssue;

    /**
     * 校验两类Catalog密钥配置及跨用途材料隔离。
     *
     * @param environment 配置来源
     * @param management 管理Pepper配置
     * @param runtime 运行Pepper配置
     */
    public CatalogSecuritySettings(Environment environment, SecuritySettings management,
        RuntimeSecuritySettings runtime) {
        encryption = read(environment, "AIGATE_PROVIDER_ENCRYPTION_KEY_ID", "AIGATE_PROVIDER_ENCRYPTION_KEY",
            "AIGATE_PROVIDER_PREVIOUS_ENCRYPTION_KEY_ID", "AIGATE_PROVIDER_PREVIOUS_ENCRYPTION_KEY", true);
        issue = read(environment, "AIGATE_PROVIDER_ISSUE_PEPPER_KEY_ID", "AIGATE_PROVIDER_ISSUE_PEPPER",
            "AIGATE_PROVIDER_ISSUE_PREVIOUS_PEPPER_KEY_ID", "AIGATE_PROVIDER_ISSUE_PREVIOUS_PEPPER", false);
        currentEncryption = encryption.get(environment.getProperty("AIGATE_PROVIDER_ENCRYPTION_KEY_ID"));
        currentIssue = issue.get(environment.getProperty("AIGATE_PROVIDER_ISSUE_PEPPER_KEY_ID"));

        // 所有保留版本均参与隔离检查，不能通过更改keyId或保留过期材料来复用密钥。
        var materials = new ArrayList<>(encryption.values());
        materials.addAll(issue.values());
        for (int i = 0; i < materials.size(); i++) {
            byte[] candidate = materials.get(i).bytes();
            if (management.usesPepper(candidate) || runtime.usesPepper(candidate)) {
                throw invalid();
            }
            for (int j = 0; j < i; j++) {
                if (Arrays.equals(candidate, materials.get(j).bytes())) {
                    throw invalid();
                }
            }
        }
    }

    /**
     * 固定错误不回显配置值和底层解码异常。
     */
    private static IllegalStateException invalid() {
        return new IllegalStateException("Independent current Catalog encryption and receipt keys are required");
    }

    /**
     * 当前版本必填，历史版本必须完整成对提供，保留用于历史密文与永久收据验证。
     */
    private static Map<String, KeyMaterial> read(Environment env, String currentId, String currentValue,
        String previousId, String previousValue, boolean aes) {
        try {
            Map<String, KeyMaterial> result = new LinkedHashMap<>();
            KeyMaterial current = decode(env.getProperty(currentId), env.getProperty(currentValue), aes);
            result.put(current.id(), current);
            String oldId = env.getProperty(previousId, "");
            String oldValue = env.getProperty(previousValue, "");
            if (!oldId.isEmpty() || !oldValue.isEmpty()) {
                KeyMaterial previous = decode(oldId, oldValue, aes);
                if (result.putIfAbsent(previous.id(), previous) != null) {
                    throw invalid();
                }
            }
            return Map.copyOf(result);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * AES-256要求恰32字节；收据HMAC至少32字节，版本名不接受空白或任意文本。
     */
    private static KeyMaterial decode(String id, String encoded, boolean aes) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}") || encoded == null) {
            throw invalid();
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (aes ? bytes.length != 32 : bytes.length < 32) {
            throw invalid();
        }
        return new KeyMaterial(id, bytes);
    }

    /**
     * 获取本次新加密使用的唯一当前版本。
     *
     * @return 当前AES主密钥
     */
    public KeyMaterial currentEncryption() {
        return currentEncryption;
    }

    /**
     * 按历史keyId选择解密材料，不回退到不匹配的当前版本。
     *
     * @param id 密文记录的版本
     * @return 对应材料，未保留时为空
     */
    public KeyMaterial encryption(String id) {
        return id == null ? null : encryption.get(id);
    }

    /**
     * 获取本次新收据使用的唯一当前Pepper。
     *
     * @return 当前收据Pepper
     */
    public KeyMaterial currentIssue() {
        return currentIssue;
    }

    /**
     * 按历史keyId验证已有收据，不因轮换重新签发。
     *
     * @param id 收据记录的版本
     * @return 对应材料，未保留时为空
     */
    public KeyMaterial issue(String id) {
        return id == null ? null : issue.get(id);
    }
}
