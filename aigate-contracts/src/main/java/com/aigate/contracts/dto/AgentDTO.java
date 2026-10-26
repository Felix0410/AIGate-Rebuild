package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * Agent 响应，包含应用编码、显示名称、归属团队、状态和版本。
 *
 * @param id 资源主键
 * @param applicationCode 不可变应用编码
 * @param displayName 显示名称
 * @param ownerTeamId 归属团队主键
 * @param status 当前状态
 * @param version 当前版本号
 * @param createdAt UTC 创建时间
 * @param updatedAt UTC 更新时间
 * @author AIGate
 */
public record AgentDTO(long id, String applicationCode, String displayName, long ownerTeamId, String status,
    long version, Instant createdAt, Instant updatedAt) {
}
