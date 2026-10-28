package com.felix.aigatecore.governance;

import static com.felix.aigatecore.governance.GovernanceAccess.values;

import com.aigate.contracts.dto.GovernanceDTO;
import com.aigate.contracts.dto.PageDTO;
import com.felix.aigatecore.catalog.CatalogMapper;
import com.felix.aigatecore.identity.IdentityMapper;
import com.felix.aigatecore.identity.RequestContext;
import com.felix.aigatecore.runtime.RuntimeIdentityMapper;
import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** P6管理状态机；配置和规则发布后不可原地修改，审批与审计同库提交。 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class GovernanceService {
    private final GovernanceMapper mapper;
    private final GovernanceAccess access;
    private final IdentityMapper identities;
    private final RuntimeIdentityMapper runtime;
    private final CatalogMapper catalog;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 装配治理管理与既有组织/目录。
     *
     * @param mapper 治理存储
     * @param access 当前管理权限
     * @param identities 组织存储
     * @param runtime Agent/ACL存储
     * @param catalog 模型/部署存储
     * @param clock UTC时钟
     */
    public GovernanceService(GovernanceMapper mapper, GovernanceAccess access, IdentityMapper identities,
        RuntimeIdentityMapper runtime, CatalogMapper catalog, Clock clock) {
        this.mapper = mapper;
        this.access = access;
        this.identities = identities;
        this.runtime = runtime;
        this.catalog = catalog;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private GovernanceRows.Policy policy(boolean lock) {
        var value = lock ? mapper.lockPolicy(Map.of()) : mapper.policy(Map.of());
        if (value == null) {
            throw ApiException.missing();
        }
        return value;
    }

    private GovernanceRows.Revision revision(long id, long policy, boolean lock) {
        var value = lock ? mapper.lockRevision(values("id", id)) : mapper.revision(values("id", id));
        if (value == null || value.policyId() != policy) {
            throw ApiException.missing();
        }
        return value;
    }

    private RuleRegistry.Compiled checked(GovernanceRows.Revision revision) {
        var rules = mapper.rules(values("id", revision.id()));
        try {
            var compiled = RuleRegistry.stored(revision.configJson());
            RuleRegistry.verify(revision, rules, compiled);
            return compiled;
        } catch (RuntimeException failure) {
            throw ApiException.conflict("POLICY_INTEGRITY_FAILURE");
        }
    }

    private Instant future(Instant value) {
        int year = value.atZone(java.time.ZoneOffset.UTC).getYear();
        if (!value.isAfter(now()) || year < 1000 || year > 9999) {
            throw ApiException.invalid();
        }
        return value;
    }

    private String region(ValidatedBody body) {
        String value = body.rawText("regionCode", 32);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")) {
            throw ApiException.invalid();
        }
        return value;
    }

    private String approvedClass(ValidatedBody body) {
        String value = body.rawText("approvedClassMax", 16);
        if (!Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL").contains(value)) {
            throw ApiException.invalid();
        }
        return value;
    }

    private String choice(ValidatedBody body) {
        String value = body.rawText("decision", 16);
        if (!Set.of("APPROVE", "REJECT").contains(value)) {
            throw ApiException.invalid();
        }
        return value;
    }

    /**
     * Editor提交完整封闭配置；自由理由不入库，规则只存经过审批流程的受控参数。
     *
     * @param context 当前身份
     * @param body 完整草稿字段
     * @return 草稿身份及初始版本
     */
    public GovernanceDTO.RevisionCreated draft(RequestContext context, ValidatedBody body) {
        access.coordinate();
        long actor = access.role(context, "POLICY_EDITOR");
        String reason = body.rawText("reason", 512);
        if (CredentialSignals.contains(reason) || !body.has("baseRevision")) {
            throw ApiException.invalid();
        }
        Long base = body.isNull("baseRevision") ? null : body.number("baseRevision", false);
        var compiled = RuleRegistry.compile(body.array("classificationRules"), body.array("egressMatrix"),
            body.array("outputReviewRules"));
        var policy = mapper.lockPolicy(Map.of());
        if (policy == null) {
            var initial = values("now", now());
            mapper.insertPolicy(initial);
            policy = mapper.lockPolicy(Map.of());
        }
        if (!Objects.equals(base, policy.activeRevisionId())) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        var query = values("policy", policy.id(), "base", base, "actor", actor, "now", now(),
            "config", compiled.canonical(), "digest", compiled.digest());
        int number = mapper.nextRevision(query);
        query.put("revision", number);
        mapper.insertRevision(query);
        long id = ((Number) query.get("id")).longValue();
        for (var rule : compiled.rules()) {
            mapper.insertRule(
                values("revision", id, "code", rule.code(), "kind", rule.kind(), "severity", rule.severity(),
                    "classification", rule.detectedClass().name(), "exemptible", rule.exemptible(), "enabled",
                    rule.enabled(),
                    "order", rule.order(), "parameters", RuleRegistry.canonical(rule.parameters()), "now", now()));
        }
        access.audit(actor, "GOVERNANCE_DRAFT_CREATED", "GOVERNANCE_REVISION", id,
            List.of("config", "contentDigest", "baseRevision"), context);
        return new GovernanceDTO.RevisionCreated(id, number, 0, "DRAFT");
    }

    /**
     * 提交前再次校验配置与独立规则行，拒绝被损坏的草稿。
     *
     * @param context 当前身份
     * @param id 修订ID
     * @return 已提交状态
     */
    public GovernanceDTO.RevisionSubmitted submit(RequestContext context, long id) {
        access.coordinate();
        long actor = access.role(context, "POLICY_EDITOR");
        var root = policy(true);
        var revision = revision(id, root.id(), true);
        checked(revision);
        if (!"DRAFT".equals(revision.status())
            || mapper.submitRevision(values("id", id, "version", revision.version(), "now", now())) != 1) {
            throw ApiException.conflict("INVALID_STATE");
        }
        access.audit(actor, "GOVERNANCE_DRAFT_SUBMITTED", "GOVERNANCE_REVISION", id, List.of("status"), context);
        return new GovernanceDTO.RevisionSubmitted(id, "UNDER_REVIEW");
    }

    /**
     * 独立Approver按CAS审核；即使同时持Editor也不得自审。
     *
     * @param context 当前身份
     * @param id 修订ID
     * @param body 审核与期望版本
     * @return 状态及新版本
     */
    public GovernanceDTO.RevisionReviewed review(RequestContext context, long id, ValidatedBody body) {
        access.coordinate();
        long actor = access.role(context, "SECURITY_APPROVER");
        GovernanceAccess.reason(body);
        String decision = choice(body);
        var root = policy(true);
        var revision = revision(id, root.id(), true);
        if (actor == revision.createdBy()) {
            throw ApiException.forbidden();
        }
        if (body.number("expectedVersion", true) != revision.version() || !"UNDER_REVIEW".equals(revision.status())) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        checked(revision);
        String status = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        mapper.reviewRevision(values("id", id, "version", revision.version(), "status", status,
            "approver", "APPROVE".equals(decision) ? actor : null,
            "approvedAt", "APPROVE".equals(decision) ? now() : null, "now", now()));
        access.audit(actor, "GOVERNANCE_REVISION_REVIEWED", "GOVERNANCE_REVISION", id, List.of("status", "version"),
            context);
        return new GovernanceDTO.RevisionReviewed(id, status, revision.version() + 1);
    }

    /**
     * 父Policy版本与activeRevision双CAS，完整校验后原子切换指针及旧版状态。
     *
     * @param context 当前身份
     * @param id 已批准修订
     * @param body 双CAS字段
     * @return 当前策略身份
     */
    public GovernanceDTO.PolicyState publish(RequestContext context, long id, ValidatedBody body) {
        access.coordinate();
        long actor = access.role(context, "SECURITY_APPROVER");
        long version = body.number("expectedPolicyVersion", true);
        if (!body.has("expectedActiveRevisionId")) {
            throw ApiException.invalid();
        }
        Long active = body.isNull("expectedActiveRevisionId") ? null : body.number("expectedActiveRevisionId", false);
        var root = policy(true);
        var revision = revision(id, root.id(), true);
        if (actor == revision.createdBy()) {
            throw ApiException.forbidden();
        }
        if (version != root.version() || !Objects.equals(active, root.activeRevisionId())
            || !"APPROVED".equals(revision.status()) || revision.approvedBy() == null) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        checked(revision);
        Instant at = now();
        if (root.activeRevisionId() != null) {
            mapper.supersedeRevision(values("id", root.activeRevisionId(), "now", at));
        }
        mapper.publishRevision(values("id", id, "actor", actor, "now", at));
        if (mapper.activatePolicy(values("id", root.id(), "version", version, "active", active,
            "revision", id, "now", at)) != 1) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        access.audit(actor, "GOVERNANCE_POLICY_PUBLISHED", "GOVERNANCE_POLICY", root.id(),
            List.of("activeRevisionId", "version"), context);
        return new GovernanceDTO.PolicyState(id, revision.revisionNo(), "PUBLISHED", version + 1, at);
    }

    /**
     * 只回安全元数据；尚无发布指针返回NOT_CONFIGURED，不是运行时允许。
     *
     * @param context 当前管理身份
     * @return 当前策略或未配置状态
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GovernanceDTO.PolicyState active(RequestContext context) {
        access.viewer(context);
        var root = policy(false);
        if (root.activeRevisionId() == null) {
            return new GovernanceDTO.PolicyState(null, null, "NOT_CONFIGURED", root.version(), null);
        }
        var revision = revision(root.activeRevisionId(), root.id(), false);
        checked(revision);
        if (!"PUBLISHED".equals(revision.status())) {
            throw ApiException.conflict("POLICY_INTEGRITY_FAILURE");
        }
        return new GovernanceDTO.PolicyState(revision.id(), revision.revisionNo(), "PUBLISHED", root.version(),
            revision.publishedAt());
    }

    /**
     * 登记独立Agent/Provider/region数据批准，旧修订不再作为新请求的回退许可。
     *
     * @param context 当前身份
     * @param body 封闭批准字段
     * @return 发行批准的时间元数据
     */
    public GovernanceDTO.ApprovalCreated approveEgress(RequestContext context, ValidatedBody body) {
        access.coordinate();
        long actor = access.role(context, "SECURITY_APPROVER");
        GovernanceAccess.reason(body);
        long agent = body.number("agentId", false);
        long provider = body.number("providerId", false);
        String region = region(body);
        String classification = approvedClass(body);
        Instant from = body.instant("validFrom");
        Instant until = future(body.instant("validUntil"));
        if (!until.isAfter(from) || from.atZone(java.time.ZoneOffset.UTC).getYear() < 1000
            || runtime.selectAgent(agent) == null || catalog.selectProvider(provider) == null) {
            throw ApiException.invalid();
        }
        var query = values("agent", agent, "provider", provider, "region", region, "classification", classification,
            "from", from, "until", until, "actor", actor, "now", now());
        query.put("revision", mapper.nextApprovalRevision(query));
        mapper.retireApprovals(query);
        mapper.insertApproval(query);
        long id = ((Number) query.get("id")).longValue();
        access.audit(actor, "AGENT_EGRESS_APPROVED", "AGENT_EGRESS_APPROVAL", id,
            List.of("agentId", "providerId", "regionCode", "approvedClassMax", "validFrom", "validUntil"), context);
        return new GovernanceDTO.ApprovalCreated(id, "ACTIVE", from, until);
    }

    /**
     * 安全分页不返回秘密或完整配置。
     *
     * @param context 管理身份
     * @param input 单值筛选
     * @return 批准元数据分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageDTO<GovernanceDTO.Approval> approvals(RequestContext context, Map<String, String> input) {
        access.viewer(context);
        var query = GovernanceAccess.page(input, Set.of("agentId", "providerId", "status", "page", "size"));
        Instant at = now();
        query.put("at", at);
        for (String field : List.of("agentId", "providerId")) {
            if (input.containsKey(field)) {
                query.put(field.equals("agentId") ? "agent" : "provider",
                    ValidatedBody.pathId(input.get(field), false));
            }
        }
        if (input.containsKey("status")) {
            if (!Set.of("ACTIVE", "REVOKED", "EXPIRED").contains(input.get("status"))) {
                throw ApiException.invalid();
            }
            query.put("status", input.get("status"));
        }
        var items = mapper.listApprovals(query).stream().map(row -> new GovernanceDTO.Approval(row.id(), row.agentId(),
            row.providerId(), row.regionCode(), row.revision(), row.dataClassMax(),
            "ACTIVE".equals(row.status()) && !row.validUntil().isAfter(at) ? "EXPIRED" : row.status(),
            row.validFrom(), row.validUntil())).toList();
        return new PageDTO<>(items, (int) query.get("page"), (int) query.get("size"), mapper.countApprovals(query));
    }

    /**
     * 数据处理批准的hosting/region来自真实部署，不信任客户端额外声明。
     *
     * @param context 当前身份
     * @param deployment 部署ID
     * @param body 边界与版本
     * @return 安全批准元数据
     */
    public BoundaryResult boundary(RequestContext context, long deployment, ValidatedBody body) {
        access.coordinate();
        long actor = access.role(context, "SECURITY_APPROVER");
        GovernanceAccess.reason(body);
        var current = catalog.selectDeployment(deployment);
        if (current == null) {
            throw ApiException.missing();
        }
        String classification = approvedClass(body);
        String region = region(body);
        Instant until = future(body.instant("validUntil"));
        boolean processing = body.bool("dataProcessingApproved");
        if (!region.equals(current.regionCode()) || !body.has("expectedVersion")) {
            throw ApiException.invalid();
        }
        Long expected = body.isNull("expectedVersion") ? null : body.number("expectedVersion", true);
        var query = values("deployment", deployment, "classification", classification, "region", region,
            "hosting", current.hostingMode(), "status", processing ? "APPROVED" : "REVOKED", "until", until,
            "processing", processing, "actor", actor, "now", now());
        var previous = mapper.lockBoundary(query);
        boolean created = previous == null;
        if (created ? expected != null : expected == null || expected != previous.version()) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        if (created) {
            mapper.insertBoundary(query);
        } else {
            query.put("id", previous.id());
            query.put("version", previous.version());
            if (mapper.updateBoundary(query) != 1) {
                throw ApiException.conflict("VERSION_CONFLICT");
            }
        }
        var row = mapper.boundary(values("deployment", deployment));
        access.audit(actor, "DEPLOYMENT_DATA_BOUNDARY_CHANGED", "DEPLOYMENT_BOUNDARY", row.id(),
            List.of("approvedClassMax", "trustedRegion", "dataProcessingApproved", "version"), context);
        return new BoundaryResult(new GovernanceDTO.Boundary(deployment, row.approvedClassMax(), row.trustedRegion(),
            row.securityApprovalStatus(), row.approvalValidUntil(), row.version()), created);
    }

    /**
     * 边界写入状态由事务确定。
     *
     * @param boundary 响应
     * @param created 首次创建
     */
    public record BoundaryResult(GovernanceDTO.Boundary boundary, boolean created) {
    }

    private void exceptionRule(GovernanceRows.Revision revision, String rule) {
        var compiled = checked(revision);
        if (!RuleRegistry.MARKERS.equals(rule) || compiled.rules().stream().noneMatch(value -> value.code().equals(rule)
            && value.enabled() && value.exemptible())) {
            throw new ApiException(400, "RULE_NOT_EXEMPTIBLE", "The rule cannot be exempted");
        }
    }

    /**
     * 仅mgt_本人申请作用域/策略修订限定的误报例外，不接收自报employeeId。
     *
     * @param context 当前身份
     * @param body Agent/Model/Rule及截止时刻
     * @param key 申请者范围内的幂等键
     * @return 新申请元数据
     */
    public GovernanceDTO.ExceptionCreated requestException(RequestContext context, ValidatedBody body, String key) {
        access.coordinate();
        long employee = access.profile(context).employee().id();
        String operation = ValidatedBody.idempotency(key);
        String reason = GovernanceAccess.reason(body);
        long agent = body.number("agentId", false);
        String code = body.rawText("modelCode", 96);
        String rule = body.rawText("ruleCode", 64);
        Instant until = future(body.instant("validUntil"));
        var model = catalog.selectModelByCode(code);
        if (model == null || runtime.selectAgent(agent) == null) {
            throw ApiException.missing();
        }
        var employeeAcl = runtime.selectPermission(values("employeeId", employee, "agentId", agent));
        var modelAcl = catalog.selectPermission(values("agentId", agent, "modelId", model.id()));
        if (employeeAcl == null || !"GRANTED".equals(employeeAcl.status()) || modelAcl == null
            || !"GRANTED".equals(modelAcl.status())) {
            throw ApiException.forbidden();
        }
        var root = policy(true);
        if (root.activeRevisionId() == null) {
            throw ApiException.conflict("POLICY_NOT_CONFIGURED");
        }
        var revision = revision(root.activeRevisionId(), root.id(), false);
        exceptionRule(revision, rule);
        if (mapper.exceptionReceipt(values("employee", employee, "key", operation)) != null) {
            throw ApiException.conflict("EXCEPTION_ALREADY_REQUESTED");
        }
        var query = values("employee", employee, "agent", agent, "model", model.id(), "rule", rule,
            "reason", reason, "revision", revision.id(), "until", until, "key", operation, "now", now());
        mapper.insertException(query);
        long id = ((Number) query.get("id")).longValue();
        access.audit(employee, "GOVERNANCE_EXCEPTION_REQUESTED", "GOVERNANCE_EXCEPTION", id,
            List.of("agentId", "logicalModelId", "ruleCode", "validUntil", "reasonCode"), context);
        return new GovernanceDTO.ExceptionCreated(id, "PENDING", 0);
    }

    /**
     * 独立Approver审核当前仍适用的误报例外，拒绝自审和旧版本重放。
     *
     * @param context 当前身份
     * @param id 申请ID
     * @param body 审核和版本
     * @return 审核时刻与新版本
     */
    public GovernanceDTO.ExceptionReviewed reviewException(RequestContext context, long id, ValidatedBody body) {
        access.coordinate();
        long actor = access.role(context, "SECURITY_APPROVER");
        String reason = GovernanceAccess.reason(body);
        String decision = choice(body);
        var root = policy(true);
        var request = mapper.lockException(values("id", id));
        if (request == null) {
            throw ApiException.missing();
        }
        if (actor == request.requesterEmployeeId()) {
            throw ApiException.forbidden();
        }
        long version = body.number("expectedVersion", true);
        if (version != request.version() || !"PENDING".equals(request.status())) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        if ("APPROVE".equals(decision)) {
            if (!Objects.equals(root.activeRevisionId(), request.policyRevisionId())
                || request.validUntil() == null || !request.validUntil().isAfter(now())) {
                throw ApiException.conflict("INVALID_STATE");
            }
            exceptionRule(revision(request.policyRevisionId(), root.id(), false), request.ruleCode());
        }
        String status = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        Instant at = now();
        mapper.reviewException(values("id", id, "status", status, "version", version, "actor", actor,
            "reason", "REVIEW:" + reason, "now", at));
        access.audit(actor, "GOVERNANCE_EXCEPTION_REVIEWED", "GOVERNANCE_EXCEPTION", id,
            List.of("status", "version", "reviewedAt", "reviewerId"), context);
        return new GovernanceDTO.ExceptionReviewed(id, status, version + 1, at);
    }

    /**
     * 撤销旧批准后PREDISPATCH必须复核；CAS重复为409。
     *
     * @param context 当前身份
     * @param id 申请ID
     * @param body 原因和期望版本
     * @return 撤销状态
     */
    public GovernanceDTO.ExceptionCreated revokeException(RequestContext context, long id, ValidatedBody body) {
        access.coordinate();
        long actor = access.role(context, "SECURITY_APPROVER");
        String reason = GovernanceAccess.reason(body);
        policy(true);
        var request = mapper.lockException(values("id", id));
        if (request == null) {
            throw ApiException.missing();
        }
        long version = body.number("expectedVersion", true);
        if (version != request.version() || !"APPROVED".equals(request.status())) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        mapper.revokeException(values("id", id, "version", version, "reason",
            request.safeReasonDescription() + ";REVOKE:" + reason, "now", now()));
        access.audit(actor, "GOVERNANCE_EXCEPTION_REVOKED", "GOVERNANCE_EXCEPTION", id,
            List.of("status", "version"), context);
        return new GovernanceDTO.ExceptionCreated(id, "REVOKED", version + 1);
    }

    /**
     * 最近最多90日的安全审计元数据，不返回HMAC、正文、Marker或内部身份秘密。
     *
     * @param context 管理身份
     * @param input 单值筛选
     * @return 安全分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageDTO<GovernanceDTO.Decision> decisions(RequestContext context, Map<String, String> input) {
        access.viewer(context);
        var query = GovernanceAccess.page(input, Set.of("from", "to", "agentId", "decision", "page", "size"));
        Instant to = input.containsKey("to") ? ValidatedBody.instantValue(input.get("to")) : now();
        Instant from = input.containsKey("from")
            ? ValidatedBody.instantValue(input.get("from"))
            : to.minus(Duration.ofDays(90));
        if (to.isBefore(from) || Duration.between(from, to).compareTo(Duration.ofDays(90)) > 0) {
            throw ApiException.invalid();
        }
        query.put("from", from);
        query.put("to", to);
        if (input.containsKey("agentId")) {
            query.put("agent", ValidatedBody.pathId(input.get("agentId"), false));
        }
        if (input.containsKey("decision")) {
            if (!Set.of("ALLOW", "DENY", "REVIEW_REQUIRED").contains(input.get("decision"))) {
                throw ApiException.invalid();
            }
            query.put("decision", input.get("decision"));
        }
        var items = mapper.listDecisions(query).stream().map(row -> {
            var codes = json.readTree(row.matchedRuleCodesJson()).get("ruleCodes");
            var safe = new java.util.ArrayList<String>();
            codes.forEach(code -> safe.add(code.asString()));
            return new GovernanceDTO.Decision(row.decisionPhase(), row.policyRevisionId(), row.dataClass(),
                row.decision(), List.copyOf(safe), row.createdAt());
        }).toList();
        return new PageDTO<>(items, (int) query.get("page"), (int) query.get("size"), mapper.countDecisions(query));
    }
}
