package com.felix.aigatecore.identity;

import java.time.Instant;

/**
 * 管理凭证的内部持久化元数据，供鉴权和幂等判断使用，不直接作为 HTTP 响应。
 *
 * @param id 资源的数据库主键，必须符合当前接口的正整数约束
 * @param employeeId 目标员工主键
 * @param keyPrefix 可公开的短凭证前缀
 * @param pepperKeyId 内部 HMAC 密钥版本标识，不包含密钥材料
 * @param expiresAt UTC 到期时间
 * @param revokedAt UTC 吊销时间；未吊销时为 null
 * @param createdAt 服务器生成的 UTC 创建时间
 * @param issuedActorType 首次签发者类型
 * @param issuedActorId 首次签发者主键
 * @param issueIdempotencyKey 首次签发操作的物理唯一幂等键
 * @author AIGate
 */
public record ManagementCredentialDO(long id, long employeeId, String keyPrefix, String pepperKeyId, Instant expiresAt,
    Instant revokedAt, Instant createdAt, String issuedActorType, long issuedActorId, String issueIdempotencyKey) {
}
