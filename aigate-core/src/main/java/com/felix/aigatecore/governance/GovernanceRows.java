package com.felix.aigatecore.governance;

import java.time.Instant;

/** 治理持久化记录；不能直接返回HTTP或写日志。 */
public final class GovernanceRows {
    private GovernanceRows() {
    }

    /**
     * Policy安全契约或内部存储记录。
     *
     * @param id 合同字段
     * @param activeRevisionId 合同字段
     * @param version 合同字段
     */
    public record Policy(long id, Long activeRevisionId, long version) {
    }

    /**
     * Revision安全契约或内部存储记录。
     *
     * @param id 合同字段
     * @param policyId 合同字段
     * @param revisionNo 合同字段
     * @param status 合同字段
     * @param version 合同字段
     * @param baseRevisionId 合同字段
     * @param configJson 合同字段
     * @param contentDigest 合同字段
     * @param createdBy 合同字段
     * @param approvedBy 合同字段
     * @param publishedBy 合同字段
     * @param createdAt 合同字段
     * @param approvedAt 合同字段
     * @param publishedAt 合同字段
     * @param updatedAt 合同字段
     */
    public record Revision(long id, long policyId, int revisionNo, String status, long version, Long baseRevisionId,
        String configJson, byte[] contentDigest, long createdBy, Long approvedBy, Long publishedBy, Instant createdAt,
        Instant approvedAt, Instant publishedAt, Instant updatedAt) {
    }

    /**
     * Rule安全契约或内部存储记录。
     *
     * @param id 合同字段
     * @param revisionId 合同字段
     * @param ruleCode 合同字段
     * @param ruleKind 合同字段
     * @param severity 合同字段
     * @param detectedClass 合同字段
     * @param exemptible 合同字段
     * @param safeParameterJson 合同字段
     * @param enabled 合同字段
     * @param ruleOrder 合同字段
     */
    public record Rule(long id, long revisionId, String ruleCode, String ruleKind, String severity,
        String detectedClass, boolean exemptible, String safeParameterJson, boolean enabled, int ruleOrder) {
    }

    /**
     * Approval安全契约或内部存储记录。
     *
     * @param id 合同字段
     * @param agentId 合同字段
     * @param providerId 合同字段
     * @param regionCode 合同字段
     * @param revision 合同字段
     * @param dataClassMax 合同字段
     * @param validFrom 合同字段
     * @param validUntil 合同字段
     * @param status 合同字段
     * @param approvedBy 合同字段
     * @param createdAt 合同字段
     */
    public record Approval(long id, long agentId, long providerId, String regionCode, int revision, String dataClassMax,
        Instant validFrom, Instant validUntil, String status, long approvedBy, Instant createdAt) {
    }

    /**
     * Boundary安全契约或内部存储记录。
     *
     * @param id 合同字段
     * @param deploymentId 合同字段
     * @param approvedClassMax 合同字段
     * @param trustedRegion 合同字段
     * @param hostingMode 合同字段
     * @param securityApprovalStatus 合同字段
     * @param approvalValidUntil 合同字段
     * @param dataProcessingApproved 合同字段
     * @param approvedBy 合同字段
     * @param version 合同字段
     */
    public record Boundary(long id, long deploymentId, String approvedClassMax, String trustedRegion,
        String hostingMode, String securityApprovalStatus, Instant approvalValidUntil, boolean dataProcessingApproved,
        long approvedBy, long version) {
    }

    /**
     * ExceptionRequest安全契约或内部存储记录。
     *
     * @param id 合同字段
     * @param requesterEmployeeId 合同字段
     * @param employeeId 合同字段
     * @param agentId 合同字段
     * @param logicalModelId 合同字段
     * @param ruleCode 合同字段
     * @param reasonCode 合同字段
     * @param safeReasonDescription 合同字段
     * @param status 合同字段
     * @param version 合同字段
     * @param reviewerId 合同字段
     * @param policyRevisionId 合同字段
     * @param validUntil 合同字段
     * @param idempotencyKey 合同字段
     * @param createdAt 合同字段
     * @param reviewedAt 合同字段
     */
    public record ExceptionRequest(long id, long requesterEmployeeId, long employeeId, long agentId,
        long logicalModelId, String ruleCode, String reasonCode, String safeReasonDescription, String status,
        long version, Long reviewerId, long policyRevisionId, Instant validUntil, String idempotencyKey,
        Instant createdAt, Instant reviewedAt) {
    }

    /**
     * Decision安全契约或内部存储记录。
     *
     * @param id 合同字段
     * @param traceId 合同字段
     * @param decisionPhase 合同字段
     * @param decisionOrdinal 合同字段
     * @param policyRevisionId 合同字段
     * @param employeeId 合同字段
     * @param agentId 合同字段
     * @param logicalModelId 合同字段
     * @param deploymentId 合同字段
     * @param dataClass 合同字段
     * @param decision 合同字段
     * @param matchedRuleCodesJson 合同字段
     * @param inputHmacDigest 合同字段
     * @param scannerVersion 合同字段
     * @param createdAt 合同字段
     */
    public record Decision(long id, String traceId, String decisionPhase, int decisionOrdinal, long policyRevisionId,
        long employeeId, long agentId, long logicalModelId, Long deploymentId, String dataClass, String decision,
        String matchedRuleCodesJson, byte[] inputHmacDigest, String scannerVersion, Instant createdAt) {
    }
}
