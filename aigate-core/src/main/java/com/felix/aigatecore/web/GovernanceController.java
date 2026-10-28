package com.felix.aigatecore.web;

import static com.felix.aigatecore.web.ManagementRequestParameters.context;
import static com.felix.aigatecore.web.ManagementRequestParameters.query;
import static com.felix.aigatecore.web.ManagementRequestParameters.requireNoBody;

import com.aigate.contracts.dto.GovernanceDTO;
import com.aigate.contracts.dto.PageDTO;
import com.felix.aigatecore.governance.GovernanceService;
import com.felix.aigatecore.identity.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** P6十二个精确管理方法；没有公网Scanner或模拟Provider出站端点。 */
@RestController
public class GovernanceController {
    private final GovernanceService service;

    /**
     * 装配安全治理管理服务。
     *
     * @param service 当前权限和治理事务
     */
    public GovernanceController(GovernanceService service) {
        this.service = service;
    }

    private long id(String value) {
        return ValidatedBody.pathId(value, false);
    }

    /** 只为草稿的baseRevision接受null。 */
    @PostMapping(path = "/api/v1/governance/policies/drafts", consumes = "application/json")
    ResponseEntity<GovernanceDTO.RevisionCreated> draft(@RequestBody byte[] bytes, HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.draft(context(request), ValidatedBody.parseAllowingNull(bytes,
            Set.of("baseRevision"), "baseRevision", "classificationRules", "egressMatrix", "outputReviewRules",
            "reason")));
    }

    /** 提交无正文，只接受当前Editor。 */
    @PostMapping("/api/v1/governance/policies/{revisionId}/submit")
    GovernanceDTO.RevisionSubmitted submit(@PathVariable String revisionId, HttpServletRequest request) {
        requireNoBody(request);
        return service.submit(context(request), id(revisionId));
    }

    /** 独立安全角色审核，不以平台管理员代替。 */
    @PostMapping(path = "/api/v1/governance/policies/{revisionId}/review", consumes = "application/json")
    GovernanceDTO.RevisionReviewed review(@PathVariable String revisionId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return service.review(context(request), id(revisionId),
            ValidatedBody.parse(bytes, "decision", "reasonCode", "expectedVersion"));
    }

    /** 父版本和active指针双CAS。 */
    @PostMapping(path = "/api/v1/governance/policies/{revisionId}/publish", consumes = "application/json")
    GovernanceDTO.PolicyState publish(@PathVariable String revisionId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return service.publish(context(request), id(revisionId), ValidatedBody.parseAllowingNull(bytes,
            Set.of("expectedActiveRevisionId"), "expectedPolicyVersion", "expectedActiveRevisionId"));
    }

    /** 当前策略只返回安全身份和版本。 */
    @GetMapping("/api/v1/governance/policies/active")
    GovernanceDTO.PolicyState active(HttpServletRequest request) {
        requireNoBody(request);
        return service.active(context(request));
    }

    /** 受信Agent/Provider/region批准。 */
    @PostMapping(path = "/api/v1/governance/agent-egress-approvals", consumes = "application/json")
    ResponseEntity<GovernanceDTO.ApprovalCreated> approveEgress(@RequestBody byte[] bytes, HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.approveEgress(context(request), ValidatedBody.parse(bytes,
            "agentId", "providerId", "regionCode", "approvedClassMax", "validFrom", "validUntil", "reasonCode")));
    }

    /** 安全批准分页。 */
    @GetMapping("/api/v1/governance/agent-egress-approvals")
    PageDTO<GovernanceDTO.Approval> approvals(HttpServletRequest request) {
        return service.approvals(RequestContext.from(request), query(request));
    }

    /** 带版本的数据处理边界，部署hosting来自数据库。 */
    @PutMapping(path = "/api/v1/governance/deployment-boundaries/{deploymentId}", consumes = "application/json")
    ResponseEntity<GovernanceDTO.Boundary> boundary(@PathVariable String deploymentId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        var result = service.boundary(context(request), id(deploymentId), ValidatedBody.parseAllowingNull(bytes,
            Set.of("expectedVersion"), "approvedClassMax", "regionCode", "validUntil", "dataProcessingApproved",
            "expectedVersion", "reasonCode"));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.boundary());
    }

    /** 例外申请严格以mgt_本人为主体。 */
    @PostMapping(path = "/api/v1/governance/exceptions", consumes = "application/json")
    ResponseEntity<GovernanceDTO.ExceptionCreated> requestException(@RequestBody byte[] bytes,
        HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Idempotency-Key"));
        if (headers.size() != 1) {
            throw ApiException.invalid();
        }
        return ResponseEntity.status(201).body(service.requestException(context(request), ValidatedBody.parse(bytes,
            "agentId", "modelCode", "ruleCode", "reasonCode", "validUntil"), headers.getFirst()));
    }

    /** 例外审核不得自审、复用旧版本或突破硬拒绝。 */
    @PostMapping(path = "/api/v1/governance/exceptions/{requestId}/review", consumes = "application/json")
    GovernanceDTO.ExceptionReviewed reviewException(@PathVariable String requestId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return service.reviewException(context(request), id(requestId),
            ValidatedBody.parse(bytes, "decision", "reasonCode", "expectedVersion"));
    }

    /** 例外撤销使下一次最终治理判定失效。 */
    @PostMapping(path = "/api/v1/governance/exceptions/{requestId}/revoke", consumes = "application/json")
    GovernanceDTO.ExceptionCreated revokeException(@PathVariable String requestId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return service.revokeException(context(request), id(requestId),
            ValidatedBody.parse(bytes, "reasonCode", "expectedVersion"));
    }

    /** 最多90日安全审计，不回敏感内容。 */
    @GetMapping("/api/v1/governance/decisions")
    PageDTO<GovernanceDTO.Decision> decisions(HttpServletRequest request) {
        return service.decisions(RequestContext.from(request), query(request));
    }
}
