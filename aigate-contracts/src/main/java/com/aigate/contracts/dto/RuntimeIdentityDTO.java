package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * Core 验证双运行凭证和当前授权后返回的最小身份信息。
 *
 * @param agentId Agent 主键
 * @param applicationCode 不可变应用编码
 * @param employeeId 员工主键
 * @param agentKeyId 本次认证使用的 Agent 凭证主键
 * @param employeeCredentialId 本次认证使用的员工运行凭证主键
 * @param permissionVersion 当前员工与 Agent 授权版本
 * @param verifiedAt 本次 UTC 验证时间
 * @author AIGate
 */
public record RuntimeIdentityDTO(long agentId, String applicationCode, long employeeId, long agentKeyId,
    long employeeCredentialId, long permissionVersion, Instant verifiedAt) {
}
