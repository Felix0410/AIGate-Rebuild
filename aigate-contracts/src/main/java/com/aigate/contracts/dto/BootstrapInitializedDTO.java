package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 企业首次初始化的响应，管理员凭证只允许在本次成功响应中展示一次。
 *
 * @param team 团队安全视图
 * @param employee 员工安全视图
 * @param managementCredential 仅本次首次响应可展示的管理凭证
 * @param initializedAt 服务器记录的 UTC 首次初始化时间
 * @author AIGate
 */
public record BootstrapInitializedDTO(TeamDTO team, EmployeeDTO employee,
    IssuedManagementCredentialDTO managementCredential, Instant initializedAt) {
}
