package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 团队已发行额度与剩余可发行额度，周期日期按公司时区显示。
 *
 * @param teamId 合同字段
 * @param periodStart 合同字段
 * @param periodEnd 合同字段
 * @param limitPoints 合同字段
 * @param issuedPoints 合同字段
 * @param reservedIssuePoints 合同字段
 * @param availablePoints 合同字段
 * @param status 合同字段
 * @param version 合同字段
 * @param updatedAt 合同字段
 */
public record TeamPoolDTO(long teamId, String periodStart, String periodEnd, String limitPoints, String issuedPoints,
    String reservedIssuePoints, String availablePoints, String status, long version, Instant updatedAt) {
}
