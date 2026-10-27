package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 供应商凭证安全元数据，任何响应都不包含秘密或密文。
 *
 * @param id 凭证主键
 * @param deploymentId 所属部署
 * @param credentialKind 鉴权类型
 * @param credentialRevision 凭证修订
 * @param status 凭证状态
 * @param createdAt 创建时间
 * @param rotatedAt 轮换时间，可空
 * @param revokedAt 吊销时间，可空
 * @author AIGate
 */
public record ProviderCredentialMetadataDTO(long id, long deploymentId, String credentialKind, int credentialRevision,
    String status, Instant createdAt, Instant rotatedAt, Instant revokedAt) {
}
