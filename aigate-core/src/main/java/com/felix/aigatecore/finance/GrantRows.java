package com.felix.aigatecore.finance;

import java.math.BigDecimal;
import java.time.Instant;

/** P5持久化记录，仅作内部资金事实，不直接响应HTTP。 */
public final class GrantRows {
    private GrantRows() {
    }

    /**
     * Pool数据库行。
     *
     * @param id 数据库列
     * @param teamId 数据库列
     * @param periodStart 数据库列
     * @param periodEnd 数据库列
     * @param limitPoints 数据库列
     * @param issuedPoints 数据库列
     * @param reservedIssuePoints 数据库列
     * @param status 数据库列
     * @param version 数据库列
     * @param createdBy 数据库列
     * @param createdAt 数据库列
     * @param updatedAt 数据库列
     */
    public record Pool(long id, long teamId, Instant periodStart, Instant periodEnd, BigDecimal limitPoints,
        BigDecimal issuedPoints, BigDecimal reservedIssuePoints, String status, long version, long createdBy,
        Instant createdAt, Instant updatedAt) {
    }

    /**
     * Grant数据库行。
     *
     * @param id 数据库列
     * @param issuerTeamId 数据库列
     * @param recipientEmployeeId 数据库列
     * @param teamPoolId 数据库列
     * @param grantOperationKey 数据库列
     * @param issuedPoints 数据库列
     * @param usedPoints 数据库列
     * @param reservedPoints 数据库列
     * @param expiresAt 数据库列
     * @param issuedAt 数据库列
     * @param status 数据库列
     * @param issuedBy 数据库列
     * @param issuedReasonCode 数据库列
     * @param revokedAt 数据库列
     * @param revokedBy 数据库列
     * @param revokeReasonCode 数据库列
     * @param version 数据库列
     * @param createdAt 数据库列
     * @param updatedAt 数据库列
     */
    public record Grant(long id, long issuerTeamId, long recipientEmployeeId, long teamPoolId, String grantOperationKey,
        BigDecimal issuedPoints, BigDecimal usedPoints, BigDecimal reservedPoints, Instant expiresAt, Instant issuedAt,
        String status, long issuedBy, String issuedReasonCode, Instant revokedAt, Long revokedBy,
        String revokeReasonCode, long version, Instant createdAt, Instant updatedAt) {
    }

    /**
     * Receipt数据库行。
     *
     * @param id 数据库列
     * @param issueOperationKey 数据库列
     * @param teamPoolId 数据库列
     * @param teamGrantId 数据库列
     * @param actorEmployeeId 数据库列
     * @param recipientEmployeeId 数据库列
     * @param amountPoints 数据库列
     * @param operationDigest 数据库列
     * @param createdAt 数据库列
     */
    public record Receipt(long id, String issueOperationKey, long teamPoolId, long teamGrantId, long actorEmployeeId,
        long recipientEmployeeId, BigDecimal amountPoints, byte[] operationDigest, Instant createdAt) {
    }
}
