package com.felix.aigatecore.catalog;

import java.time.Instant;

/**
 * Catalog内部持久化记录，只能转换为安全DTO后响应，禁止直接序列化或记录日志。
 *
 * @param id 资源主键
 * @param logicalModelId 真实逻辑模型外键
 * @param providerId 真实供应商外键
 * @param deploymentCode 不可变部署编码
 * @param endpointUri 实际HTTPS端点，只供Core内部校验
 * @param remoteModelName 该Provider实际模型别名
 * @param hostingMode THIRD_PARTY_API或SELF_HOSTED
 * @param regionCode 登记的受控地区
 * @param capabilityRevision 固定适配证据修订
 * @param adapterCompatibilityJson 服务端绑定deploymentId的候选配置JSON
 * @param adapterEvidenceRef 非秘密适配证据引用，不等于批准
 * @param costModel PER_TOKEN_API或INTERNAL_RESOURCE
 * @param status 当前资源状态
 * @param routingPriority 路由优先级元数据
 * @param routingWeight 1至100的路由权重
 * @param maxConcurrent 正整数并发上限
 * @param capacityPoolId P7才允许绑定的容量池，P3为空
 * @param activePurchasePriceVersionId 当前已发布采购价指针，可空
 * @param activeCredentialId 当前Provider凭证指针，可空
 * @param version CAS并发版本
 * @param createdAt UTC创建时刻
 * @param updatedAt UTC最后更新时间
 * @author AIGate
 */
public record DeploymentDO(long id, long logicalModelId, long providerId, String deploymentCode, String endpointUri,
    String remoteModelName, String hostingMode, String regionCode, String capabilityRevision,
    String adapterCompatibilityJson, String adapterEvidenceRef, String costModel, String status, int routingPriority,
    int routingWeight, int maxConcurrent, Long capacityPoolId, Long activePurchasePriceVersionId,
    Long activeCredentialId, long version, Instant createdAt, Instant updatedAt) {
}
