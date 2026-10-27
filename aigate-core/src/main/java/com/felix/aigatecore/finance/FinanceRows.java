package com.felix.aigatecore.finance;

import java.math.BigDecimal;
import java.time.Instant;

/** 仅供账务持久层使用，不作为HTTP响应序列化。 */
public final class FinanceRows {
    private FinanceRows() {
    }

    /**
     * Policy数据库行，仅供资金事务内部使用。
     *
     * @param id 数据库列
     * @param scopeType 数据库列
     * @param scopeId 数据库列
     * @param revision 数据库列
     * @param weeklyLimitPoints 数据库列
     * @param effectiveFrom 数据库列
     * @param effectiveTo 数据库列
     * @param status 数据库列
     * @param createdBy 数据库列
     * @param createdAt 数据库列
     * @param updatedAt 数据库列
     */
    public record Policy(long id, String scopeType, long scopeId, int revision, BigDecimal weeklyLimitPoints,
        Instant effectiveFrom, Instant effectiveTo, String status, long createdBy, Instant createdAt,
        Instant updatedAt) {
    }

    /**
     * Bucket数据库行，仅供资金事务内部使用。
     *
     * @param id 数据库列
     * @param employeeId 数据库列
     * @param bucketKind 数据库列
     * @param agentScopeId 数据库列
     * @param periodStart 数据库列
     * @param periodEnd 数据库列
     * @param limitPoints 数据库列
     * @param usedPoints 数据库列
     * @param reservedPoints 数据库列
     * @param policyRevision 数据库列
     * @param version 数据库列
     */
    public record Bucket(long id, long employeeId, String bucketKind, long agentScopeId, Instant periodStart,
        Instant periodEnd, BigDecimal limitPoints, BigDecimal usedPoints, BigDecimal reservedPoints, int policyRevision,
        long version) {
    }

    /**
     * Budget数据库行，仅供资金事务内部使用。
     *
     * @param id 数据库列
     * @param currency 数据库列
     * @param periodStart 数据库列
     * @param periodEnd 数据库列
     * @param limitAmount 数据库列
     * @param settledAmount 数据库列
     * @param reservedAmount 数据库列
     * @param riskState 数据库列
     * @param version 数据库列
     */
    public record Budget(long id, String currency, Instant periodStart, Instant periodEnd, BigDecimal limitAmount,
        BigDecimal settledAmount, BigDecimal reservedAmount, String riskState, long version) {
    }

    /**
     * Partition数据库行，仅供资金事务内部使用。
     *
     * @param id 数据库列
     * @param budgetId 数据库列
     * @param partitionCode 数据库列
     * @param limitAmount 数据库列
     * @param settledAmount 数据库列
     * @param reservedAmount 数据库列
     * @param version 数据库列
     */
    public record Partition(long id, long budgetId, String partitionCode, BigDecimal limitAmount,
        BigDecimal settledAmount,
        BigDecimal reservedAmount, long version) {
    }

    /**
     * Operation数据库行，仅供资金事务内部使用。
     *
     * @param id 数据库列
     * @param operationKey 数据库列
     * @param employeeId 数据库列
     * @param agentId 数据库列
     * @param logicalModelId 数据库列
     * @param deploymentId 数据库列
     * @param serviceClass 数据库列
     * @param status 数据库列
     * @param pointsPriceVersionId 数据库列
     * @param providerPriceVersionId 数据库列
     * @param currency 数据库列
     * @param employeeTeamSnapshotId 数据库列
     * @param employeeWeekStart 数据库列
     * @param companyPeriodStart 数据库列
     * @param maxInputTokens 数据库列
     * @param maxOutputTokens 数据库列
     * @param maxPoints 数据库列
     * @param maxCurrencyAmount 数据库列
     * @param usageEvidenceDigest 数据库列
     * @param overrunPoints 数据库列
     * @param overrunCurrencyAmount 数据库列
     * @param createdAt 数据库列
     * @param updatedAt 数据库列
     */
    public record Operation(long id, String operationKey, long employeeId, long agentId, long logicalModelId,
        long deploymentId, String serviceClass, String status, long pointsPriceVersionId, Long providerPriceVersionId,
        String currency, long employeeTeamSnapshotId, Instant employeeWeekStart, Instant companyPeriodStart,
        long maxInputTokens, long maxOutputTokens, BigDecimal maxPoints, BigDecimal maxCurrencyAmount,
        byte[] usageEvidenceDigest, BigDecimal overrunPoints, BigDecimal overrunCurrencyAmount, Instant createdAt,
        Instant updatedAt) {
    }

    /**
     * Points数据库行，仅供资金事务内部使用。
     *
     * @param id 数据库列
     * @param financeOperationId 数据库列
     * @param reservedPoints 数据库列
     * @param settledPoints 数据库列
     * @param state 数据库列
     */
    public record Points(long id, long financeOperationId, BigDecimal reservedPoints, BigDecimal settledPoints,
        String state) {
    }

    /**
     * Allocation数据库行，仅供资金事务内部使用。
     *
     * @param id 数据库列
     * @param pointsReservationId 数据库列
     * @param sourceType 数据库列
     * @param sourceId 数据库列
     * @param priorityOrder 数据库列
     * @param reservedPoints 数据库列
     * @param settledPoints 数据库列
     * @param releasedPoints 数据库列
     */
    public record Allocation(long id, long pointsReservationId, String sourceType, long sourceId, int priorityOrder,
        BigDecimal reservedPoints, BigDecimal settledPoints, BigDecimal releasedPoints) {
    }

    /**
     * Spend数据库行，仅供资金事务内部使用。
     *
     * @param id 数据库列
     * @param financeOperationId 数据库列
     * @param budgetId 数据库列
     * @param partitionId 数据库列
     * @param currency 数据库列
     * @param reservedAmount 数据库列
     * @param settledAmount 数据库列
     * @param state 数据库列
     */
    public record Spend(long id, long financeOperationId, long budgetId, long partitionId, String currency,
        BigDecimal reservedAmount, BigDecimal settledAmount, String state) {
    }
}
