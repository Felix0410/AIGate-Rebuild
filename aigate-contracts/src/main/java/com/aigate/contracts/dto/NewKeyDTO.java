package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 运行凭证首次签发响应，完整密钥只能在本次 201 响应中展示。
 *
 * @param credentialId 新凭证主键
 * @param key 本次首次签发的完整密钥，禁止记录日志
 * @param keyPrefix 可公开的短密钥前缀
 * @param validFrom 凭证开始生效时间
 * @param expiresAt 凭证自然到期时间
 * @param oldCredentialGraceExpiresAt 旧凭证宽限期截止时间；普通签发时为 null
 * @author AIGate
 */
public record NewKeyDTO(long credentialId, String key, String keyPrefix, Instant validFrom, Instant expiresAt,
    Instant oldCredentialGraceExpiresAt) {
}
