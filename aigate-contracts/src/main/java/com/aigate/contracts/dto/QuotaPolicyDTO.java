package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 周额度策略安全投影。
 *
 * @param id 合同字段
 * @param scopeType 合同字段
 * @param scopeId 合同字段
 * @param revision 合同字段
 * @param weeklyLimitPoints 合同字段
 * @param effectiveFrom 合同字段
 * @param status 合同字段
 * @param createdAt 合同字段
 */
public record QuotaPolicyDTO(long id, String scopeType, long scopeId, int revision, String weeklyLimitPoints,
    Instant effectiveFrom, String status, Instant createdAt) {
}
