package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 逻辑模型身份、固定能力与当前价格指针。
 *
 * @param id 模型主键
 * @param modelCode 不可变模型编码
 * @param displayName 显示名称
 * @param modelFamily 固定型号
 * @param immutableModelRevision 固定模型修订
 * @param capabilityProfile 固定能力
 * @param status 当前状态
 * @param activePointsPriceVersionId 当前积分价格，可空
 * @param activeRoutingRevisionId 后续路由版本，P3为空
 * @param version 并发版本
 * @param createdAt UTC创建时间
 * @param updatedAt UTC更新时间
 * @author AIGate
 */
public record LogicalModelDTO(long id, String modelCode, String displayName, String modelFamily,
    String immutableModelRevision, CapabilityProfile capabilityProfile, String status, Long activePointsPriceVersionId,
    Long activeRoutingRevisionId, long version, Instant createdAt, Instant updatedAt) {

    /**
     * 封闭的模型能力 v1，不能代替真实计费上界证明。
     *
     * @param schemaVersion 固定为1
     * @param chatCompletions 固定支持文本聊天
     * @param streaming 是否支持流式
     * @param toolCalling 固定不支持工具编排
     * @param maxInputTokens 输入上限
     * @param maxOutputTokens 输出上限
     * @param maxTotalTokens 总上下文上限
     * @author AIGate
     */
    public record CapabilityProfile(int schemaVersion, boolean chatCompletions, boolean streaming, boolean toolCalling,
        int maxInputTokens, int maxOutputTokens, int maxTotalTokens) {
    }
}
