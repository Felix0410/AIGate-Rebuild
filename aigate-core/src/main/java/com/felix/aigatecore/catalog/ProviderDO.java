package com.felix.aigatecore.catalog;

import java.time.Instant;

/**
 * Catalog内部持久化记录，只能转换为安全DTO后响应，禁止直接序列化或记录日志。
 *
 * @param id 资源主键
 * @param code 不可变供应商编码
 * @param name 供应商显示名称
 * @param hostingMode THIRD_PARTY_API或SELF_HOSTED
 * @param status 当前资源状态
 * @param baseUrlHostPolicy 封闭且规范化的端点策略JSON
 * @param version CAS并发版本
 * @param createdAt UTC创建时刻
 * @param updatedAt UTC最后更新时间
 * @author AIGate
 */
public record ProviderDO(long id, String code, String name, String hostingMode, String status, String baseUrlHostPolicy,
    long version, Instant createdAt, Instant updatedAt) {
}
