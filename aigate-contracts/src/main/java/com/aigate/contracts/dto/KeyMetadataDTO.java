package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 运行凭证元数据，不包含完整密钥、摘要或 Pepper。
 *
 * @param id 资源主键
 * @param keyPrefix 可公开的短密钥前缀
 * @param validFrom 凭证开始生效时间
 * @param expiresAt 凭证自然到期时间
 * @param supersededAt 首次被轮换替代的时间，可为 null
 * @param graceExpiresAt 旧密钥宽限期截止时间，可为 null
 * @param revokedAt 吊销或撤销时间，可为 null
 * @param createdAt UTC 创建时间
 * @author AIGate
 */
public record KeyMetadataDTO(long id, String keyPrefix, Instant validFrom, Instant expiresAt, Instant supersededAt,
    Instant graceExpiresAt, Instant revokedAt, Instant createdAt) {
}
