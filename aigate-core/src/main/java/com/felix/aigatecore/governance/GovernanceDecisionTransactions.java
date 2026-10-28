package com.felix.aigatecore.governance;

import static com.felix.aigatecore.governance.GovernanceAccess.values;

import com.felix.aigatecore.catalog.CatalogMapper;
import com.felix.aigatecore.identity.IdentityMapper;
import com.felix.aigatecore.runtime.RuntimeIdentityMapper;
import com.felix.aigatecore.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 短事务治理最终门禁。共享身份协调锁→共享Policy锁，发布使用相同起点的独占锁。 可加入P7调用方的既有资金事务；此处没有Invocation、Permit或Provider HTTP。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class GovernanceDecisionTransactions {
    private final GovernanceMapper mapper;
    private final IdentityMapper identities;
    private final RuntimeIdentityMapper runtime;
    private final CatalogMapper catalog;
    private final GovernanceSettings settings;
    private final GovernanceAccess access;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 装配治理事实与当前身份、目录状态。
     *
     * @param mapper 治理存储
     * @param identities 当前组织
     * @param runtime 当前Agent/ACL
     * @param catalog 当前模型/部署
     * @param settings 审计密钥与Scanner版本
     * @param access 安全审计
     * @param clock 当前时间
     */
    public GovernanceDecisionTransactions(GovernanceMapper mapper, IdentityMapper identities,
        RuntimeIdentityMapper runtime, CatalogMapper catalog, GovernanceSettings settings, GovernanceAccess access,
        Clock clock) {
        this.mapper = mapper;
        this.identities = identities;
        this.runtime = runtime;
        this.catalog = catalog;
        this.settings = settings;
        this.access = access;
        this.clock = clock;
    }

    record Snapshot(GovernanceRows.Policy policy, GovernanceRows.Revision revision, RuleRegistry.Compiled compiled,
        String failure) {
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Snapshot snapshot() {
        var root = mapper.policy(Map.of());
        if (root == null || root.activeRevisionId() == null) {
            return new Snapshot(root, null, null, "POLICY_NOT_CONFIGURED");
        }
        var revision = mapper.revision(values("id", root.activeRevisionId()));
        try {
            if (revision == null || revision.policyId() != root.id() || !"PUBLISHED".equals(revision.status())) {
                throw RuleRegistry.invalid();
            }
            var compiled = RuleRegistry.stored(revision.configJson());
            RuleRegistry.verify(revision, mapper.rules(values("id", revision.id())), compiled);
            return new Snapshot(root, revision, compiled, null);
        } catch (RuntimeException failure) {
            return new Snapshot(root, revision, null, "POLICY_INTEGRITY_FAILURE");
        }
    }

    private void shared() {
        if (mapper.shareIdentity(Map.of()) == null) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Identity state unavailable");
        }
    }

    private boolean subjects(GovernanceEvaluator.Context context) {
        var employee = identities.selectEmployeeById(context.employee());
        var agent = runtime.selectAgent(context.agent());
        var model = catalog.selectModel(context.model());
        var deployment = catalog.selectDeployment(context.deployment());
        if (employee == null || agent == null || model == null || deployment == null
            || !"ACTIVE".equals(employee.status()) || !"ACTIVE".equals(agent.status())
            || !"ACTIVE".equals(model.status()) || !"ACTIVE".equals(deployment.status())
            || deployment.logicalModelId() != model.id()) {
            return false;
        }
        var primary = identities.selectTeamById(employee.primaryTeamId());
        var owner = identities.selectTeamById(agent.ownerTeamId());
        var provider = catalog.selectProvider(deployment.providerId());
        var employeeAcl = runtime.selectPermission(values("employeeId", employee.id(), "agentId", agent.id()));
        var modelAcl = catalog.selectPermission(values("agentId", agent.id(), "modelId", model.id()));
        return primary != null && owner != null && provider != null && "ACTIVE".equals(primary.status())
            && "ACTIVE".equals(owner.status()) && "ACTIVE".equals(provider.status())
            && provider.hostingMode().equals(deployment.hostingMode())
            && employeeAcl != null && "GRANTED".equals(employeeAcl.status())
            && modelAcl != null && "GRANTED".equals(modelAcl.status());
    }

    private String boundary(GovernanceEvaluator.Context context, RuleRegistry.Compiled policy,
        RuleRegistry.DataClass classification, boolean stream) {
        var deployment = catalog.selectDeployment(context.deployment());
        if (classification == RuleRegistry.DataClass.RESTRICTED
            || classification == RuleRegistry.DataClass.CONFIDENTIAL
                && (stream || !"SELF_HOSTED".equals(deployment.hostingMode()))) {
            return "HARD_EGRESS_DENY";
        }
        var cell = policy.egress().stream().filter(value -> value.classification() == classification
            && value.hosting().equals(deployment.hostingMode())).findFirst().orElseThrow();
        if ("DENY".equals(cell.decision())) {
            return "EGRESS_MATRIX_DENY";
        }
        boolean thirdParty = "THIRD_PARTY_API".equals(deployment.hostingMode());
        if (cell.agentApproval() || thirdParty) {
            var approvals = mapper.liveApprovals(values("agent", context.agent(), "provider", deployment.providerId(),
                "region", deployment.regionCode(), "at", now()));
            if (approvals.isEmpty()
                || RuleRegistry.classification(approvals.getFirst().dataClassMax()).ordinal() < classification
                    .ordinal()) {
                return "AGENT_EGRESS_NOT_APPROVED";
            }
        }
        if (cell.deploymentBoundary() || thirdParty || classification == RuleRegistry.DataClass.CONFIDENTIAL) {
            var value = mapper.boundary(values("deployment", deployment.id()));
            if (value == null || !"APPROVED".equals(value.securityApprovalStatus()) || !value.dataProcessingApproved()
                || !value.approvalValidUntil().isAfter(now()) || !value.trustedRegion().equals(deployment.regionCode())
                || !value.hostingMode().equals(deployment.hostingMode())
                || RuleRegistry.classification(value.approvedClassMax()).ordinal() < classification.ordinal()) {
                return "DEPLOYMENT_BOUNDARY_NOT_APPROVED";
            }
        }
        return null;
    }

    private GovernanceEvaluator.Verdict record(GovernanceEvaluator.Context context, GovernanceRows.Policy root,
        GovernanceRows.Revision evaluated, RuleRegistry.DataClass classification, String decision, String reason,
        List<String> codes, byte[] digest) {
        Long revision = evaluated == null ? null : evaluated.id();
        if (revision == null && root != null) {
            revision = root.activeRevisionId();
        }
        if (revision != null) {
            // 既有JSON列承载安全RuleCode列表与原因码；不含正文或命中片段。
            String matched = json.writeValueAsString(Map.of("ruleCodes", codes, "reasonCode", reason));
            mapper.insertDecision(
                values("trace", context.traceId(), "phase", context.phase(), "ordinal", context.ordinal(),
                    "revision", revision, "employee", context.employee(), "agent", context.agent(), "model",
                    context.model(),
                    "deployment", context.deployment(), "classification", classification.name(), "decision", decision,
                    "codes", matched, "digest", digest, "scanner", settings.scannerVersion(), "now", now()));
        } else {
            // V6审计FK不允许伪造缺失Policy；未配置拒绝记入已有安全管理审计。
            access.audit(context.employee(), "GOVERNANCE_DENIED_NO_POLICY", "GOVERNANCE_POLICY", 0,
                List.of("policyNotConfigured"), new com.felix.aigatecore.identity.RequestContext(null, null,
                    context.traceId()));
        }
        return new GovernanceEvaluator.Verdict(decision, reason, classification.name(), revision, codes,
            "REVIEW_REQUIRED".equals(decision));
    }

    GovernanceEvaluator.Verdict complete(GovernanceEvaluator.Prepared prepared) {
        var context = prepared.context();
        shared();
        var root = mapper.sharePolicy(Map.of());
        if (!subjects(context)) {
            return new GovernanceEvaluator.Verdict("DENY", "SUBJECT_NOT_AUTHORIZED", "INTERNAL", null, List.of(),
                false);
        }
        var snapshot = prepared.snapshot();
        var scan = prepared.scan();
        String failure = snapshot.failure();
        if (root == null || root.activeRevisionId() == null) {
            failure = "POLICY_NOT_CONFIGURED";
        } else if (snapshot.policy() == null || root.version() != snapshot.policy().version()
            || !root.activeRevisionId().equals(snapshot.policy().activeRevisionId())) {
            failure = "POLICY_REVISION_CHANGED";
        } else if (scan.failure() != null) {
            failure = scan.failure();
        }
        if (failure != null) {
            return record(context, root, snapshot.revision(), scan.floor(), "DENY", failure, List.of(),
                prepared.digest());
        }
        var classification = scan.floor();
        boolean exception = scan.markerClasses().size() == 1
            && scan.markerClasses().getFirst() != RuleRegistry.DataClass.RESTRICTED
            && !mapper.liveExceptions(values("employee", context.employee(), "agent", context.agent(), "model",
                context.model(), "revision", snapshot.revision().id(), "at", now())).isEmpty();
        if (!exception) {
            for (var hit : scan.markerClasses()) {
                classification = classification.max(hit);
            }
        }
        if (scan.secret()) {
            classification = RuleRegistry.DataClass.RESTRICTED;
        }
        if (scan.secret() || classification == RuleRegistry.DataClass.RESTRICTED) {
            return record(context, root, snapshot.revision(), classification, "DENY", "HARD_SECRET_OR_RESTRICTED",
                scan.codes(), prepared.digest());
        }
        String denied = boundary(context, snapshot.compiled(), classification, scan.stream());
        if (denied != null) {
            return record(context, root, snapshot.revision(), classification, "DENY", denied, scan.codes(),
                prepared.digest());
        }
        boolean output = classification == RuleRegistry.DataClass.CONFIDENTIAL;
        return record(context, root, snapshot.revision(), classification, output ? "REVIEW_REQUIRED" : "ALLOW",
            output ? "CONFIDENTIAL_BUFFERED_REVIEW_REQUIRED" : "GOVERNANCE_APPROVED", scan.codes(), prepared.digest());
    }

    GovernanceEvaluator.Verdict completeOutput(GovernanceEvaluator.Context context, Snapshot snapshot, String failure) {
        shared();
        var root = mapper.sharePolicy(Map.of());
        if (!subjects(context)) {
            return new GovernanceEvaluator.Verdict("DENY", "SUBJECT_NOT_AUTHORIZED", "CONFIDENTIAL", null,
                List.of(), false);
        }
        if (root == null || root.activeRevisionId() == null || snapshot.policy() == null
            || root.version() != snapshot.policy().version()) {
            failure = "POLICY_REVISION_CHANGED";
        }
        var prior = snapshot.revision() == null
            ? null
            : mapper.inputReview(values("trace", context.traceId(),
                "employee", context.employee(), "agent", context.agent(), "model", context.model(),
                "deployment", context.deployment(), "revision", snapshot.revision().id()));
        if (prior == null) {
            failure = "OUTPUT_REVIEW_CONTRACT_MISSING";
        }
        if (failure == null) {
            failure = boundary(context, snapshot.compiled(), RuleRegistry.DataClass.CONFIDENTIAL, false);
        }
        byte[] digest = prior == null ? settings.digest(new byte[0]) : prior.inputHmacDigest();
        return record(context, root, snapshot.revision(), RuleRegistry.DataClass.CONFIDENTIAL,
            failure == null ? "ALLOW" : "DENY", failure == null ? "OUTPUT_REVIEW_APPROVED" : failure,
            List.of(RuleRegistry.OUTPUT), digest);
    }
}
