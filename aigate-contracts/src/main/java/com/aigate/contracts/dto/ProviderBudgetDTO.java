package com.aigate.contracts.dto;

/**
 * 原币种月采购预算，不混合币种。
 *
 * @param currency 合同字段
 * @param month 合同字段
 * @param limitAmount 合同字段
 * @param normalLimitAmount 合同字段
 * @param emergencyLimitAmount 合同字段
 * @param settledAmount 合同字段
 * @param reservedAmount 合同字段
 * @param riskState 合同字段
 * @param version 合同字段
 */
public record ProviderBudgetDTO(String currency, String month, String limitAmount, String normalLimitAmount,
    String emergencyLimitAmount, String settledAmount, String reservedAmount, String riskState, long version) {
}
