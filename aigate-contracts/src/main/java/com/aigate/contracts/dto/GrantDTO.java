package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * Grant安全元数据；到期或撤销后availablePoints为零，发行事实保留。
 *
 * @param grantId 合同字段
 * @param issuerTeamId 合同字段
 * @param recipientEmployeeId 合同字段
 * @param issuedPoints 合同字段
 * @param usedPoints 合同字段
 * @param reservedPoints 合同字段
 * @param availablePoints 合同字段
 * @param expiresAt 合同字段
 * @param issuedAt 合同字段
 * @param status 合同字段
 * @param version 合同字段
 */
public record GrantDTO(long grantId, long issuerTeamId, long recipientEmployeeId, String issuedPoints,
    String usedPoints, String reservedPoints, String availablePoints, Instant expiresAt, Instant issuedAt,
    String status, long version) {
}
