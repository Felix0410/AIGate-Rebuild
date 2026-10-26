package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 员工使用 Agent 的显式授权，不根据归属团队自动授予。
 *
 * @param employeeId 员工主键
 * @param agentId Agent 主键
 * @param status 当前状态
 * @param version 当前版本号
 * @param grantedAt 最近一次授权时间
 * @param revokedAt 吊销或撤销时间，可为 null
 * @author AIGate
 */
public record AgentEmployeePermissionDTO(long employeeId, long agentId, String status, long version, Instant grantedAt,
    Instant revokedAt) {
}
