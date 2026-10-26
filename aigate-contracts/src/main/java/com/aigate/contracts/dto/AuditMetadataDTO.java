package com.aigate.contracts.dto;

import java.time.Instant;
import java.util.List;

/**
 * 审计查询结果，包含操作者、动作、目标和修改字段名，不包含字段值。
 *
 * <p>
 * changedFields 仅用于描述修改了哪些字段，不包含修改前后的值；traceId 用于关联请求而非认证。
 * </p>
 *
 * @param id 资源的数据库主键，必须符合当前接口的正整数约束
 * @param actorType 受信操作者类型
 * @param actorId 签发者或审计操作者主键；BOOTSTRAP 固定为 0
 * @param action 固定业务审计动作
 * @param targetType 接口约定的审计目标类型
 * @param targetId 审计目标主键
 * @param changedFields 仅包含变更字段名的列表
 * @param traceId 已规范化的诊断请求 ID
 * @param occurredAt UTC 审计发生时间
 * @author AIGate
 */
public record AuditMetadataDTO(long id, String actorType, long actorId, String action, String targetType, long targetId,
    List<String> changedFields, String traceId, Instant occurredAt) {
}
