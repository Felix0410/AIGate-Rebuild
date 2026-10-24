package com.aigate.contracts.dto;

import java.util.List;

/**
 * 当前管理身份视图，员工及角色必须来自 Core 当前的持久化事实。
 *
 * @param employee 员工安全视图
 * @param roles 当前未撤销角色视图
 * @author AIGate
 */
public record ManagementProfileDTO(EmployeeDTO employee, List<RoleAssignmentDTO> roles) {
}
