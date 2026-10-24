package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 仅限首次成功签发响应使用的管理凭证视图，禁止持久化或记录其明文密钥。
 *
 * @param credentialId 目标管理凭证主键
 * @param key 字段名或当前操作的受控幂等标识；凭证明文只允许在首次签发响应使用
 * @param keyPrefix 可公开的短凭证前缀
 * @param expiresAt UTC 到期时间
 * @author AIGate
 */
public record IssuedManagementCredentialDTO(long credentialId, String key, String keyPrefix, Instant expiresAt) {
}
