package com.felix.aigatecore.catalog;

import java.time.Instant;

/**
 * Catalog内部持久化记录，只能转换为安全DTO后响应，禁止直接序列化或记录日志。
 *
 * @param id 资源主键
 * @param deploymentId 所属部署外键
 * @param credentialKind BEARER/API_KEY_HEADER/NONE
 * @param encryptedSecret 带认证标签的密文，NONE为空
 * @param iv 随机12字节GCM IV，NONE为空
 * @param encryptionKeyId 密文主密钥版本，NONE为空
 * @param credentialRevision 正整数凭证修订
 * @param issuedByEmployeeId 当前认证签发者主键
 * @param issueIdempotencyKey 该签发者命名空间内的永久收据键
 * @param issueRequestHmac 绑定原请求的HMAC-SHA256，不是普通秘密摘要
 * @param issueHmacKeyId 收据签名Pepper版本
 * @param status 当前资源状态
 * @param createdAt UTC创建时刻
 * @param rotatedAt 替代已有当前凭证的时刻，可空
 * @param revokedAt 实际吊销时刻，可空
 * @author AIGate
 */
public record ProviderCredentialDO(long id, long deploymentId, String credentialKind, byte[] encryptedSecret, byte[] iv,
    String encryptionKeyId, int credentialRevision, long issuedByEmployeeId, String issueIdempotencyKey,
    byte[] issueRequestHmac, String issueHmacKeyId, String status, Instant createdAt, Instant rotatedAt,
    Instant revokedAt) {
}
