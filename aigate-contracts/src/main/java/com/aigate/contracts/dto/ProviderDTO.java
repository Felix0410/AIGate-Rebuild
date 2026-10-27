package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 供应商安全元数据，不包含端点策略或秘密。
 *
 * @param id 供应商主键
 * @param code 不可变编码
 * @param name 显示名称
 * @param hostingMode 托管模式
 * @param status 当前状态
 * @param version 并发版本
 * @param createdAt UTC创建时间
 * @param updatedAt UTC更新时间
 * @author AIGate
 */
public record ProviderDTO(long id, String code, String name, String hostingMode, String status, long version,
    Instant createdAt, Instant updatedAt) {
}
