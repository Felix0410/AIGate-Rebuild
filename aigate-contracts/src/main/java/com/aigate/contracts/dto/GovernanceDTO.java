package com.aigate.contracts.dto;

import java.time.Instant;

/** P6安全治理响应，不返回配置、命中片段或HMAC。 */
public final class GovernanceDTO {
    private GovernanceDTO() {
    }

    /**
     * RevisionCreated安全契约或内部存储记录。
     *
     * @param revisionId 合同字段
     * @param revision 合同字段
     * @param version 合同字段
     * @param status 合同字段
     */
    public record RevisionCreated(long revisionId, int revision, long version, String status) {
    }

    /**
     * RevisionSubmitted安全契约或内部存储记录。
     *
     * @param revisionId 合同字段
     * @param status 合同字段
     */
    public record RevisionSubmitted(long revisionId, String status) {
    }

    /**
     * RevisionReviewed安全契约或内部存储记录。
     *
     * @param revisionId 合同字段
     * @param status 合同字段
     * @param version 合同字段
     */
    public record RevisionReviewed(long revisionId, String status, long version) {
    }

    /**
     * PolicyState安全契约或内部存储记录。
     *
     * @param revisionId 合同字段
     * @param revision 合同字段
     * @param status 合同字段
     * @param policyVersion 合同字段
     * @param publishedAt 合同字段
     */
    public record PolicyState(Long revisionId, Integer revision, String status, long policyVersion,
        Instant publishedAt) {
    }

    /**
     * ApprovalCreated安全契约或内部存储记录。
     *
     * @param approvalId 合同字段
     * @param status 合同字段
     * @param validFrom 合同字段
     * @param validUntil 合同字段
     */
    public record ApprovalCreated(long approvalId, String status, Instant validFrom, Instant validUntil) {
    }

    /**
     * Approval安全契约或内部存储记录。
     *
     * @param approvalId 合同字段
     * @param agentId 合同字段
     * @param providerId 合同字段
     * @param regionCode 合同字段
     * @param revision 合同字段
     * @param approvedClassMax 合同字段
     * @param status 合同字段
     * @param validFrom 合同字段
     * @param validUntil 合同字段
     */
    public record Approval(long approvalId, long agentId, long providerId, String regionCode, int revision,
        String approvedClassMax, String status, Instant validFrom, Instant validUntil) {
    }

    /**
     * Boundary安全契约或内部存储记录。
     *
     * @param deploymentId 合同字段
     * @param approvedClassMax 合同字段
     * @param regionCode 合同字段
     * @param securityApprovalStatus 合同字段
     * @param validUntil 合同字段
     * @param version 合同字段
     */
    public record Boundary(long deploymentId, String approvedClassMax, String regionCode, String securityApprovalStatus,
        Instant validUntil, long version) {
    }

    /**
     * ExceptionCreated安全契约或内部存储记录。
     *
     * @param requestId 合同字段
     * @param status 合同字段
     * @param version 合同字段
     */
    public record ExceptionCreated(long requestId, String status, long version) {
    }

    /**
     * ExceptionReviewed安全契约或内部存储记录。
     *
     * @param requestId 合同字段
     * @param status 合同字段
     * @param version 合同字段
     * @param reviewedAt 合同字段
     */
    public record ExceptionReviewed(long requestId, String status, long version, Instant reviewedAt) {
    }

    /**
     * Decision安全契约或内部存储记录。
     *
     * @param phase 合同字段
     * @param revision 合同字段
     * @param dataClass 合同字段
     * @param decision 合同字段
     * @param safeRuleCodes 合同字段
     * @param time 合同字段
     */
    public record Decision(String phase, long revision, String dataClass, String decision,
        java.util.List<String> safeRuleCodes, Instant time) {
    }
}
