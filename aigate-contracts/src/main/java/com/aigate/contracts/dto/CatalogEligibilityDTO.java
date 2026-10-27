package com.aigate.contracts.dto;

import java.time.Instant;
import java.util.List;

/**
 * 双身份、双ACL及当前目录条件的检查结果，不授予真实出站许可。
 *
 * @param modelId 模型主键
 * @param modelCode 模型编码
 * @param agentId 当前Agent
 * @param employeeId 当前员工
 * @param pointsPriceVersionId 当前积分价，可空
 * @param eligible 是否至少有一个目录候选
 * @param deployments 按主键排序的安全候选
 * @param checkedAt UTC检查时刻
 * @author AIGate
 */
public record CatalogEligibilityDTO(long modelId, String modelCode, long agentId, long employeeId,
    Long pointsPriceVersionId, boolean eligible, List<EligibleDeployment> deployments, Instant checkedAt) {

    /**
     * 内部目录候选的最小信息，不返回供应商端点和凭证。
     *
     * @param deploymentId 部署主键
     * @param hostingMode 托管模式
     * @param costModel 计费模式
     * @param capabilityRevision 批准适配版本
     * @param purchasePriceVersionId 第三方当前采购价，本地为空
     * @param currency 第三方当前币种，本地为空
     * @author AIGate
     */
    public record EligibleDeployment(long deploymentId, String hostingMode, String costModel, String capabilityRevision,
        Long purchasePriceVersionId, String currency) {
    }
}
