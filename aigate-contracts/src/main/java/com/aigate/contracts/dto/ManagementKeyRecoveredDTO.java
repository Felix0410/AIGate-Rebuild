package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 受控恢复管理凭证的响应；恢复动作不创建员工或增加角色。
 *
 * <p>
 * recoveredAt 来自新凭证的持久化创建时间，不能据此推断员工或角色被重新创建。
 * </p>
 *
 * @param employeeId 目标员工主键
 * @param managementCredential 仅本次首次响应可展示的管理凭证
 * @param recoveredAt 服务器记录的 UTC 凭证恢复时间
 * @author AIGate
 */
public record ManagementKeyRecoveredDTO(long employeeId, IssuedManagementCredentialDTO managementCredential,
    Instant recoveredAt) {
}
