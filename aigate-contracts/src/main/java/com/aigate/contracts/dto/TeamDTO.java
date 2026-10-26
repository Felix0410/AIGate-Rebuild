package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 团队响应数据，与数据库实体分离，仅包含管理接口允许公开的字段。
 *
 * <p>
 * version 为更新时的并发控制依据；停用操作还需检查团队下是否存在有效员工。
 * </p>
 *
 * @param id 资源的数据库主键，必须符合当前接口的正整数约束
 * @param name 团队名称
 * @param status 当前接口允许的状态值
 * @param version 资源当前非负版本号
 * @param createdAt 服务器生成的 UTC 创建时间
 * @param updatedAt 服务器生成的 UTC 更新时间
 * @author AIGate
 */
public record TeamDTO(long id, String name, String status, long version, Instant createdAt, Instant updatedAt) {
}
