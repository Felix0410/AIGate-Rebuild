package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * Agent与逻辑模型的显式授权及版本历史。
 *
 * @param agentId Agent主键
 * @param modelId 逻辑模型主键
 * @param status GRANTED或REVOKED
 * @param version 授权版本
 * @param grantedAt 最近授予时间
 * @param revokedAt 最近撤销时间，可空
 * @author AIGate
 */
public record AgentModelPermissionDTO(long agentId, long modelId, String status, long version, Instant grantedAt,
    Instant revokedAt) {
}
