package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 部署安全元数据，不返回实际端点、适配证据全文或秘密。
 *
 * @param id 部署主键
 * @param logicalModelId 逻辑模型主键
 * @param providerId 供应商主键
 * @param deploymentCode 不可变部署编码
 * @param remoteModelName 供应商模型别名
 * @param hostingMode 托管模式
 * @param regionCode 登记地区
 * @param capabilityRevision 适配版本
 * @param costModel 计费模式
 * @param status 当前状态
 * @param routingPriority 路由优先级
 * @param routingWeight 路由权重
 * @param maxConcurrent 并发上限
 * @param capacityPoolId 未来容量池，P3为空
 * @param activePurchasePriceVersionId 当前采购价，可空
 * @param hasActiveCredential 当前凭证是否可用
 * @param hasApprovedAdapterCompatibility 当前配置是否命中有效批准
 * @param version 并发版本
 * @param createdAt UTC创建时间
 * @param updatedAt UTC更新时间
 * @author AIGate
 */
public record DeploymentDTO(long id, long logicalModelId, long providerId, String deploymentCode,
    String remoteModelName, String hostingMode, String regionCode, String capabilityRevision, String costModel,
    String status, int routingPriority, int routingWeight, int maxConcurrent, Long capacityPoolId,
    Long activePurchasePriceVersionId, boolean hasActiveCredential, boolean hasApprovedAdapterCompatibility,
    long version, Instant createdAt, Instant updatedAt) {
}
