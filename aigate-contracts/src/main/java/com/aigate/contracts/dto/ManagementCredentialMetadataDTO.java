package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 管理凭证元数据；已签发密钥的明文和摘要均不得出现在此视图中。
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
