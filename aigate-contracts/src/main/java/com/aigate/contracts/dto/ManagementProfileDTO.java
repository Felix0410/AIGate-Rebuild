package com.aigate.contracts.dto;

import java.util.List;

/**
 * 当前管理身份的响应，包含员工信息和数据库中未撤销的角色。
 *
 * <p>
 * 角色是否具备某项权限仍需结合员工、团队及作用域当前状态判定，不能仅按角色名称判断。
 * </p>
 *
 * @param employee 员工响应数据
 * @param roles 当前未撤销的角色列表
 * @author AIGate
 */
public record ManagementProfileDTO(EmployeeDTO employee, List<RoleAssignmentDTO> roles) {
}
