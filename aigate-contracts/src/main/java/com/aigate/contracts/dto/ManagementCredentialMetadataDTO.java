package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 凭证列表中的记录，包含短前缀和有效期，不包含完整密钥或摘要。
 *
 * <p>
 * revokedAt 为 null 只表示尚未吊销；是否可认证还取决于到期时间及员工、团队当前状态。
 * </p>
 *
 * @param id 资源的数据库主键，必须符合当前接口的正整数约束
 * @param keyPrefix 可公开的短凭证前缀
 * @param expiresAt UTC 到期时间
 * @param revokedAt UTC 吊销时间；未吊销时为 null
 * @param createdAt 服务器生成的 UTC 创建时间
 * @author AIGate
 */
public record ManagementCredentialMetadataDTO(long id, String keyPrefix, Instant expiresAt, Instant revokedAt,
    Instant createdAt) {
}
