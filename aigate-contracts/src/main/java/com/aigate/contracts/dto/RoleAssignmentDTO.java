package com.aigate.contracts.dto;

/**
 * 角色响应数据，包含角色编码、作用域类型和作用域 ID。
 *
 * <p>
 * GLOBAL 对应 scopeId=0，TEAM 对应正数团队主键；合法角色与作用域组合由服务层校验。
 * </p>
 *
 * @param roleCode 接口允许的角色编码
 * @param scopeType GLOBAL 或 TEAM 作用域类型
 * @param scopeId 授权范围主键；GLOBAL 固定为 0
 * @author AIGate
 */
public record RoleAssignmentDTO(String roleCode, String scopeType, long scopeId) {
}
