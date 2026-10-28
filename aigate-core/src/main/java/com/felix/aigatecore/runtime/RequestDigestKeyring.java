package com.felix.aigatecore.runtime;

import com.aigate.contracts.ChatRequestV1;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Core专属请求HMAC版本环。历史请求只按已持久化的原keyId核验，缺失时禁止回退新key。 由未来Runtime配置装配，不进入Gateway，也不自行生成开发缺省密钥。
 */
public final class RequestDigestKeyring {
    private final String currentId;
    private final Map<String, byte[]> keys;

    /**
     * 复制受信材料；要求每个版本至少256位且不同版本不能复用实际材料。
     *
     * @param currentId 新请求使用的版本
     * @param materials 已经通过跨用途隔离检查的Core独立密钥
     */
    public RequestDigestKeyring(String currentId, Map<String, byte[]> materials) {
        if (currentId == null || materials == null || !materials.containsKey(currentId) || materials.size() > 16) {
            throw invalid();
        }
        Map<String, byte[]> copy = new HashMap<>();
        for (var entry : materials.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z0-9_-]{1,32}")
                || entry.getValue() == null || entry.getValue().length < 32
                || copy.values().stream().anyMatch(key -> MessageDigest.isEqual(key, entry.getValue()))) {
                throw invalid();
            }
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        this.currentId = currentId;
        keys = Map.copyOf(copy);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Independent versioned request digest keys are required");
    }

    /**
     * 取新请求的版本标识，调用者与digest一起持久化，不能仅保存摘要。
     *
     * @return 当前非秘密版本名
     */
    public String currentId() {
        return currentId;
    }

    /**
     * 计算原版本的32字节HMAC；密钥不存在或未知schema一律拒绝。
     *
     * @param keyId 原请求的密钥版本
     * @param schemaVersion 原请求的规范版本，P7仅为1
     * @param request 严格解析的请求
     * @return 新分配的二进制摘要
     */
    public byte[] digest(String keyId, int schemaVersion, ChatRequestV1 request) {
        byte[] key = keyId == null ? null : keys.get(keyId);
        if (key == null || schemaVersion != 1 || request == null) {
            throw invalid();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(request.signingBytes());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Request HMAC unavailable");
        }
    }

    /**
     * 对数据库原摘要作常量时间比较，不因轮换修改原记录或重建出站机会。
     *
     * @param keyId 原密钥版本
     * @param schemaVersion 原schema
     * @param request 当前请求
     * @param expected 数据库存储的32字节摘要
     * @return 相同请求才为true
     */
    public boolean matches(String keyId, int schemaVersion, ChatRequestV1 request, byte[] expected) {
        byte[] actual = digest(keyId, schemaVersion, request);
        return expected != null && expected.length == 32 && MessageDigest.isEqual(actual, expected);
    }

    @Override
    public String toString() {
        return "RequestDigestKeyring[redacted]";
    }
}
