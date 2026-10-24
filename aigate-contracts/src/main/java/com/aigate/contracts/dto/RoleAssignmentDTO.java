package com.aigate.contracts.dto;

/**
 * 当前有效的角色与作用域视图，不暴露角色关系的数据库内部主键。
 *
 * @param roleCode 合同允许的角色编码
 * @param scopeType GLOBAL 或 TEAM 作用域类型
 * @param scopeId 授权范围主键；GLOBAL 固定为 0
 * @author AIGate
 */
public record RoleAssignmentDTO(String roleCode, String scopeType, long scopeId) {
}
