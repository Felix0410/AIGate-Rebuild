package com.felix.aigatecore.catalog;

import java.time.Instant;

/**
 * Catalog内部持久化记录，只能转换为安全DTO后响应，禁止直接序列化或记录日志。
 *
 * @param id 资源主键
 * @param modelCode 不可变逻辑模型编码
 * @param displayName 公开显示名称
 * @param modelFamily 固定的真实型号
 * @param immutableModelRevision 不可变模型修订
 * @param capabilityProfileJson 固定模型能力的封闭JSON
 * @param status 当前资源状态
 * @param activePointsPriceVersionId 当前已发布积分价指针，可空
 * @param activeRoutingRevisionId 后续路由发布指针，P3为空
 * @param version CAS并发版本
 * @param createdAt UTC创建时刻
 * @param updatedAt UTC最后更新时间
 * @author AIGate
 */
public record LogicalModelDO(long id, String modelCode, String displayName, String modelFamily,
    String immutableModelRevision, String capabilityProfileJson, String status, Long activePointsPriceVersionId,
    Long activeRoutingRevisionId, long version, Instant createdAt, Instant updatedAt) {
}
