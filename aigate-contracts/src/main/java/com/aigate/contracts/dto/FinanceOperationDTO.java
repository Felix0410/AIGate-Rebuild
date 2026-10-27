package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 管理员账务诊断，未知结算金额为null。
 *
 * @param operationKey 合同字段
 * @param status 合同字段
 * @param employeeId 合同字段
 * @param agentId 合同字段
 * @param logicalModelId 合同字段
 * @param serviceClass 合同字段
 * @param pointsPriceVersionId 合同字段
 * @param providerPriceVersionId 合同字段
 * @param currency 合同字段
 * @param maxPoints 合同字段
 * @param maxCurrencyAmount 合同字段
 * @param pointsReserved 合同字段
 * @param pointsSettled 合同字段
 * @param providerReserved 合同字段
 * @param providerSettled 合同字段
 * @param usageEvidencePresent 合同字段
 * @param createdAt 合同字段
 * @param updatedAt 合同字段
 */
public record FinanceOperationDTO(String operationKey, String status, long employeeId, long agentId,
    long logicalModelId, String serviceClass, long pointsPriceVersionId, Long providerPriceVersionId, String currency,
    String maxPoints, String maxCurrencyAmount, String pointsReserved, String pointsSettled, String providerReserved,
    String providerSettled, boolean usageEvidencePresent, Instant createdAt, Instant updatedAt) {
}
