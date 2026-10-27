package com.aigate.contracts.dto;

import java.time.Instant;
import java.util.List;

/**
 * 原周冻结账户查询；尚无账户时为空，不代表无限额度。
 *
 * @param employeeId 合同字段
 * @param at 合同字段
 * @param agentWeekly 合同字段
 * @param employeeWeekly 合同字段
 */
public record EmployeeQuotaDTO(long employeeId, Instant at, List<WeeklyAccount> agentWeekly,
    WeeklyAccount employeeWeekly) {

    /**
     * 固定周账户余额。
     *
     * @param agentId 合同字段
     * @param limitPoints 合同字段
     * @param usedPoints 合同字段
     * @param reservedPoints 合同字段
     * @param availablePoints 合同字段
     * @param periodStart 合同字段
     * @param periodEnd 合同字段
     */
    public record WeeklyAccount(Long agentId, String limitPoints, String usedPoints, String reservedPoints,
        String availablePoints, Instant periodStart, Instant periodEnd) {
    }
}
