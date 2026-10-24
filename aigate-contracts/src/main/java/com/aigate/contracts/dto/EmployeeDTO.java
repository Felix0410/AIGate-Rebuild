package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 员工安全视图，不携带运行凭证、管理密钥或内部鉴权状态。
 *
 * @param id 资源的数据库主键，必须符合当前接口的正整数约束
 * @param employeeCode 不可变员工编码
 * @param displayName 员工公开显示名称
 * @param primaryTeamId 员工当前归属团队主键
 * @param status 当前合同允许的状态值
 * @param version 资源当前非负版本号
 * @param createdAt 服务器生成的 UTC 创建时间
 * @param updatedAt 服务器生成的 UTC 更新时间
 * @author AIGate
 */
public record EmployeeDTO(long id, String employeeCode, String displayName, long primaryTeamId, String status,
    long version, Instant createdAt, Instant updatedAt) {
}
