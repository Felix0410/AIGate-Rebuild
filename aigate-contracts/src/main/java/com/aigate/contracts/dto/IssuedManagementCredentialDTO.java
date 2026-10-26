package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 首次签发凭证的响应，包含完整密钥；不能持久化或记录密钥明文。
 *
 * <p>
 * keyPrefix 仅用于识别密钥，不能用于认证；key 才是完整凭证，不能进入日志或持久化元数据。
 * </p>
 *
 * @param credentialId 目标管理凭证主键
 * @param key 本次首次签发的密钥明文，禁止记录或通过后续查询再次展示
 * @param keyPrefix 可公开的短凭证前缀
 * @param expiresAt UTC 到期时间
 * @author AIGate
 */
public record IssuedManagementCredentialDTO(long credentialId, String key, String keyPrefix, Instant expiresAt) {
}
