package com.felix.aigatecore.finance;

import static com.felix.aigatecore.finance.FinanceManagementService.values;

import com.aigate.contracts.dto.FinanceOperationDTO;
import com.felix.aigatecore.catalog.CatalogMapper;
import com.felix.aigatecore.catalog.CatalogPriceDO;
import com.felix.aigatecore.identity.IdentityMapper;
import com.felix.aigatecore.runtime.RuntimeIdentityMapper;
import com.felix.aigatecore.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * P4/P5仅供finance-test Profile内调用的Fake账务门面，没有HTTP映射或Provider客户端。 锁序：身份协调共享锁 → 员工/操作 → 原币种预算父/分区 → 周桶主键升序 → Grant主键升序。
 * 管理写先拿身份协调独占锁，与资金操作互斥；数据库事务中没有外部I/O。
 */
@Service
@Profile("finance-test")
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FakeFinanceFacade {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final FinanceMapper mapper;
    private final GrantMapper grants;
    private final IdentityMapper identities;
    private final RuntimeIdentityMapper runtime;
    private final CatalogMapper catalog;
    private final FinancePeriods periods;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 装配测试Profile的真实MySQL账务事务。
     *
     * @param mapper 资金存储
     * @param grants Grant存储
     * @param identities 身份权威
     * @param runtime Agent与ACL存储
     * @param catalog 冻结价格来源
     * @param periods 公司周期
     * @param clock UTC时钟
     */
    public FakeFinanceFacade(FinanceMapper mapper, GrantMapper grants, IdentityMapper identities,
        RuntimeIdentityMapper runtime,
        CatalogMapper catalog, FinancePeriods periods, Clock clock) {
        this.mapper = mapper;
        this.grants = grants;
        this.identities = identities;
        this.runtime = runtime;
        this.catalog = catalog;
        this.periods = periods;
        this.clock = clock;
    }

    record Request(String operationKey, long employee, long agent, long model, long deployment,
        long maxInput, long maxOutput, boolean inputOutputBoundProven) {
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private void shareIdentity() {
        if (mapper.shareIdentityControl(Map.of()) == null) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Identity state is unavailable");
        }
    }

    private CatalogPriceDO price(boolean points, long owner, Long id, Instant at) {
        if (id == null) {
            throw ApiException.conflict("PRICE_UNAVAILABLE");
        }
        var result = catalog.selectPriceById(values("points", points, "id", id));
        if (result == null || result.ownerId() != owner || !"PUBLISHED".equals(result.status())
            || result.effectiveFrom() == null || result.effectiveFrom().isAfter(at)) {
            throw ApiException.conflict("PRICE_UNAVAILABLE");
        }
        return result;
    }

    private FinanceRows.Bucket bucket(long employee, String kind, long scope, Instant at) {
        var period = periods.week(at);
        var query = values("employee", employee, "kind", kind, "scope", scope,
            "start", period.start(), "end", period.end(), "now", at);
        var existing = mapper.bucket(query);
        if (existing != null) {
            return existing;
        }
        var policy = mapper.activePolicy(values("scopeType", kind, "scopeId", scope, "at", at));
        if (policy == null) {
            throw ApiException.conflict("QUOTA_POLICY_UNAVAILABLE");
        }
        query.put("limit", policy.weeklyLimitPoints());
        query.put("revision", policy.revision());
        mapper.insertBucket(query);
        return mapper.bucket(query);
    }

    private BigDecimal available(FinanceRows.Bucket bucket) {
        return bucket.limitPoints().subtract(bucket.usedPoints()).subtract(bucket.reservedPoints());
    }

    FinanceOperationDTO reserve(Request request) {
        shareIdentity();
        Instant at = now();
        FinanceManagementService.operationKey(request.operationKey());
        if (!request.inputOutputBoundProven() || request.maxInput() < 0 || request.maxOutput() <= 0) {
            throw new ApiException(422, "BILLING_BOUND_UNVERIFIABLE", "Trusted input/output bound is required");
        }
        if (mapper.lockEmployee(values("id", request.employee())) == null) {
            throw ApiException.forbidden();
        }
        var employee = identities.selectEmployeeById(request.employee());
        var agent = runtime.selectAgent(request.agent());
        var model = catalog.selectModel(request.model());
        var deployment = catalog.selectDeployment(request.deployment());
        if (agent == null || model == null || deployment == null || !"ACTIVE".equals(agent.status())
            || !"ACTIVE".equals(model.status()) || !"ACTIVE".equals(deployment.status())
            || deployment.logicalModelId() != request.model()) {
            throw ApiException.conflict("INVALID_STATE");
        }
        var team = identities.selectTeamById(employee.primaryTeamId());
        var ownerTeam = identities.selectTeamById(agent.ownerTeamId());
        var provider = catalog.selectProvider(deployment.providerId());
        if (team == null || ownerTeam == null || provider == null || !"ACTIVE".equals(team.status())
            || !"ACTIVE".equals(ownerTeam.status()) || !"ACTIVE".equals(provider.status())) {
            throw ApiException.forbidden();
        }
        var acl = runtime.selectPermission(values("employeeId", request.employee(), "agentId", request.agent()));
        var modelAcl = catalog.selectPermission(values("agentId", request.agent(), "modelId", request.model()));
        if (acl == null || !"GRANTED".equals(acl.status()) || modelAcl == null
            || !"GRANTED".equals(modelAcl.status())) {
            throw ApiException.forbidden();
        }
        if (mapper.operation(values("key", request.operationKey())) != null) {
            throw ApiException.conflict("OPERATION_ALREADY_EXISTS");
        }
        var pointsPrice = price(true, model.id(), model.activePointsPriceVersionId(), at);
        BigDecimal maxPoints = FinanceAmounts.charge(request.maxInput(), request.maxOutput(), pointsPrice.inputPrice(),
            pointsPrice.outputPrice(), 6, RoundingMode.CEILING);
        boolean paid = "THIRD_PARTY_API".equals(deployment.hostingMode());
        var purchase = paid ? price(false, deployment.id(), deployment.activePurchasePriceVersionId(), at) : null;
        BigDecimal maxAmount = paid
            ? FinanceAmounts.charge(request.maxInput(), request.maxOutput(),
                purchase.inputPrice(), purchase.outputPrice(), 8, RoundingMode.CEILING)
            : null;
        var operation = values("key", request.operationKey(), "employee", employee.id(), "agent", agent.id(),
            "model", model.id(), "deployment", deployment.id(), "pointsPrice", pointsPrice.id(), "purchasePrice",
            paid ? purchase.id() : null, "currency", paid ? purchase.currency() : null, "team",
            employee.primaryTeamId(),
            "week", periods.week(at).start(), "month", periods.month(at).start(), "input", request.maxInput(),
            "output", request.maxOutput(), "points", maxPoints, "amount", maxAmount, "now", at);
        // 永久唯一operationKey先落库；唯一冲突使后续所有账户写入回滚。
        mapper.insertOperation(operation);
        long operationId = ((Number) operation.get("id")).longValue();
        if (paid) {
            var budget = mapper.lockBudget(values("currency", purchase.currency(), "start", periods.month(at).start()));
            if (budget == null || !"ACTIVE".equals(budget.riskState())) {
                throw ApiException.conflict("PROVIDER_BUDGET_UNAVAILABLE");
            }
            var parts = mapper.lockPartitions(values("id", budget.id()));
            var normal = parts.stream().filter(x -> "NORMAL".equals(x.partitionCode())).findFirst().orElseThrow();
            if (budget.limitAmount().subtract(budget.settledAmount()).subtract(budget.reservedAmount())
                .compareTo(maxAmount) < 0
                || normal.limitAmount().subtract(normal.settledAmount())
                    .subtract(normal.reservedAmount()).compareTo(maxAmount) < 0) {
                throw ApiException.conflict("INSUFFICIENT_PROVIDER_BUDGET");
            }
            mapper.changeBudget(values("id", budget.id(), "used", ZERO, "reserved", maxAmount,
                "risk", "ACTIVE", "now", at));
            mapper.changePartition(values("id", normal.id(), "used", ZERO, "reserved", maxAmount, "now", at));
            mapper.insertSpend(values("operation", operationId, "budget", budget.id(), "partition", normal.id(),
                "currency", purchase.currency(), "amount", maxAmount, "now", at));
            ledger(operationId, "RESERVE", "PROVIDER_BUDGET", budget.id(), "CURRENCY", purchase.currency(),
                maxAmount, at);
        }
        // 同一员工行锁保护惰性创建；创建后以主键升序锁桶，结算使用相同顺序。
        var candidates = List.of(bucket(employee.id(), "AGENT_WEEK", agent.id(), at),
            bucket(employee.id(), "EMPLOYEE_WEEK", 0, at));
        Map<Long, FinanceRows.Bucket> lockedBuckets = new java.util.HashMap<>();
        candidates.stream().map(FinanceRows.Bucket::id).sorted()
            .forEach(id -> lockedBuckets.put(id, mapper.lockBucketId(values("id", id))));
        var buckets = candidates.stream().map(candidate -> lockedBuckets.get(candidate.id())).toList();
        BigDecimal total = buckets.stream().map(this::available).reduce(ZERO, BigDecimal::add);
        // Grant的锁按ID升序，消费按FEFO；同一接收者由员工行锁串行化新预留。
        var candidatesGrants = total.compareTo(maxPoints) < 0
            ? grants.eligibleGrants(values("employee", employee.id(), "at", at)).stream()
                .sorted(java.util.Comparator.comparing(GrantRows.Grant::expiresAt)
                    .thenComparingLong(GrantRows.Grant::id))
                .toList()
            : List.<GrantRows.Grant>of();
        total = total.add(candidatesGrants.stream().map(grant -> grant.issuedPoints()
            .subtract(grant.usedPoints()).subtract(grant.reservedPoints())).reduce(ZERO, BigDecimal::add));
        if (total.compareTo(maxPoints) < 0) {
            throw ApiException.conflict("INSUFFICIENT_POINTS");
        }
        var reservation = values("operation", operationId, "points", maxPoints, "now", at);
        mapper.insertPoints(reservation);
        long reservationId = ((Number) reservation.get("id")).longValue();
        BigDecimal remaining = maxPoints;
        for (int priority = 0; priority < buckets.size(); priority++) {
            var account = buckets.get(priority);
            BigDecimal take = remaining.min(available(account));
            mapper.changeBucket(values("id", account.id(), "used", ZERO, "reserved", take, "now", at));
            mapper.insertAllocation(values("reservation", reservationId, "kind", account.bucketKind(),
                "source", account.id(), "priority", priority, "points", take, "now", at));
            ledger(operationId, "RESERVE", account.bucketKind(), account.id(), "POINTS", null, take, at);
            remaining = remaining.subtract(take);
        }
        int priority = buckets.size();
        for (var grant : candidatesGrants) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal take = remaining.min(grant.issuedPoints().subtract(grant.usedPoints())
                .subtract(grant.reservedPoints()));
            if (take.signum() == 0) {
                continue;
            }
            grants.changeGrant(values("id", grant.id(), "used", ZERO, "reserved", take, "now", at));
            mapper.insertAllocation(values("reservation", reservationId, "kind", "GRANT", "source", grant.id(),
                "priority", priority++, "points", take, "now", at));
            ledger(operationId, "RESERVE", "GRANT", grant.id(), "POINTS", null, take, at);
            remaining = remaining.subtract(take);
        }
        return FinanceManagementService.operationDto(mapper, mapper.operation(values("key", request.operationKey())));
    }

    private FinanceRows.Operation lock(String key) {
        shareIdentity();
        var operation = mapper.lockOperation(values("key", FinanceManagementService.operationKey(key)));
        if (operation == null) {
            throw ApiException.missing();
        }
        return operation;
    }

    FinanceOperationDTO unknown(String key) {
        var operation = lock(key);
        if ("PENDING_RECONCILIATION".equals(operation.status())) {
            return FinanceManagementService.operationDto(mapper, operation);
        }
        if (!"RESERVED".equals(operation.status())) {
            throw ApiException.conflict("INVALID_STATE");
        }
        var points = mapper.points(values("id", operation.id()));
        var spend = mapper.spend(values("id", operation.id()));
        mapper.finishPoints(values("id", points.id(), "state", "PENDING_RECONCILIATION", "used", ZERO, "now", now()));
        if (spend != null) {
            mapper.finishSpend(values("id", spend.id(), "state", "PENDING_RECONCILIATION", "used", ZERO, "now", now()));
        }
        mapper.finishOperation(values("id", operation.id(), "status", "PENDING_RECONCILIATION", "digest", null,
            "overrunPoints", ZERO, "overrunAmount", ZERO, "now", now()));
        return FinanceManagementService.operationDto(mapper, mapper.operation(values("key", key)));
    }

    FinanceOperationDTO settle(String key, long input, long output) {
        return finish(key, input, output, false);
    }

    FinanceOperationDTO releaseProvenNotSent(String key) {
        return finish(key, 0, 0, true);
    }

    private FinanceOperationDTO finish(String key, long input, long output, boolean release) {
        if (input < 0 || output < 0) {
            throw ApiException.invalid();
        }
        var operation = lock(key);
        byte[] digest = hash("AIGATE:P4:FAKE:" + release + ":" + input + ":" + output);
        if (operation.usageEvidenceDigest() != null) {
            if (!MessageDigest.isEqual(digest, operation.usageEvidenceDigest())) {
                throw ApiException.conflict("REPORT_EVIDENCE_CONFLICT");
            }
            return FinanceManagementService.operationDto(mapper, operation);
        }
        if (!List.of("RESERVED", "PENDING_RECONCILIATION").contains(operation.status())
            || (release && !"RESERVED".equals(operation.status()))) {
            throw ApiException.conflict("INVALID_STATE");
        }
        // 仅使用operation冻结的不可变价格ID；价格指针/当前周/当前Team变动均不影响原账。
        var pointPrice = price(true, operation.logicalModelId(), operation.pointsPriceVersionId(),
            operation.createdAt());
        BigDecimal actualPoints = release
            ? ZERO
            : FinanceAmounts.charge(input, output, pointPrice.inputPrice(),
                pointPrice.outputPrice(), 6, RoundingMode.HALF_UP);
        var spend = mapper.spend(values("id", operation.id()));
        BigDecimal actualAmount = null;
        if (spend != null) {
            var purchase = price(false, operation.deploymentId(), operation.providerPriceVersionId(),
                operation.createdAt());
            actualAmount = release
                ? ZERO
                : FinanceAmounts.charge(input, output, purchase.inputPrice(),
                    purchase.outputPrice(), 8, RoundingMode.HALF_UP);
        }
        BigDecimal excessPoints = actualPoints.subtract(operation.maxPoints()).max(ZERO);
        BigDecimal excessAmount = actualAmount == null
            ? ZERO
            : actualAmount.subtract(operation.maxCurrencyAmount()).max(ZERO);
        boolean overrun = excessPoints.signum() > 0 || excessAmount.signum() > 0
            || input > operation.maxInputTokens() || output > operation.maxOutputTokens();
        String state = release ? "RELEASED" : overrun ? "OVERRUN" : "SETTLED";
        Instant at = now();
        if (spend != null) {
            var budget = mapper.lockBudgetId(values("id", spend.budgetId()));
            mapper.lockPartitions(values("id", budget.id()));
            mapper.changeBudget(values("id", budget.id(), "used", actualAmount,
                "reserved", spend.reservedAmount().negate(), "risk", overrun ? "FROZEN" : budget.riskState(), "now",
                at));
            mapper.changePartition(values("id", spend.partitionId(), "used", actualAmount,
                "reserved", spend.reservedAmount().negate(), "now", at));
            mapper.finishSpend(values("id", spend.id(), "used", actualAmount, "state", state, "now", at));
            ledger(operation.id(), "UNRESERVE", "PROVIDER_BUDGET", budget.id(), "CURRENCY", spend.currency(),
                spend.reservedAmount().negate(), at);
            ledger(operation.id(), "SETTLE", "PROVIDER_BUDGET", budget.id(), "CURRENCY", spend.currency(),
                actualAmount, at);
        }
        var points = mapper.points(values("id", operation.id()));
        var allocations = mapper.allocations(values("id", points.id()));
        // 周桶先锁，Grant后锁，各表内按ID升序；到期/撤销不能阻止原预留的合法结算。
        for (var allocation : allocations.stream().filter(x -> !"GRANT".equals(x.sourceType()))
            .sorted(java.util.Comparator.comparingLong(FinanceRows.Allocation::sourceId)).toList()) {
            if (mapper.lockBucketId(values("id", allocation.sourceId())) == null) {
                throw new IllegalStateException("Missing reserved quota account");
            }
        }
        for (var allocation : allocations.stream().filter(x -> "GRANT".equals(x.sourceType()))
            .sorted(java.util.Comparator.comparingLong(FinanceRows.Allocation::sourceId)).toList()) {
            if (grants.lockGrant(values("id", allocation.sourceId())) == null) {
                throw new IllegalStateException("Missing reserved Grant");
            }
        }
        BigDecimal remaining = actualPoints.min(points.reservedPoints());
        for (var allocation : allocations) {
            BigDecimal used = remaining.min(allocation.reservedPoints());
            BigDecimal released = allocation.reservedPoints().subtract(used);
            var accountChange = values("id", allocation.sourceId(), "used", used,
                "reserved", allocation.reservedPoints().negate(), "now", at);
            if ("GRANT".equals(allocation.sourceType())) {
                grants.changeGrant(accountChange);
            } else {
                mapper.changeBucket(accountChange);
            }
            mapper.finishAllocation(values("id", allocation.id(), "used", used, "released", released, "now", at));
            ledger(operation.id(), "UNRESERVE", allocation.sourceType(), allocation.sourceId(), "POINTS", null,
                allocation.reservedPoints().negate(), at);
            ledger(operation.id(), "SETTLE", allocation.sourceType(), allocation.sourceId(), "POINTS", null, used, at);
            remaining = remaining.subtract(used);
        }
        mapper.finishPoints(values("id", points.id(), "used", actualPoints.min(points.reservedPoints()),
            "state", state, "now", at));
        mapper.finishOperation(values("id", operation.id(), "status", state, "digest", digest,
            "overrunPoints", excessPoints, "overrunAmount", excessAmount, "now", at));
        if (!release) {
            // P4专用测试事件不会伪装成P7的真实消费/采购事实，未来relay必须排除此类型。
            var payload = new TreeMap<String, Object>();
            payload.put("operationId", operation.id());
            payload.put("testOnly", true);
            payload.put("status", state);
            payload.put("points", FinanceAmounts.text(actualPoints, 6));
            payload.put("currency", operation.currency());
            payload.put("providerAmount", FinanceAmounts.text(actualAmount, 8));
            String canonical = json.writeValueAsString(payload);
            mapper.insertOutbox(values("eventId", UUID.randomUUID().toString(), "key", "P4:TEST:" + operation.id(),
                "operation", operation.id(), "payload", canonical, "digest", HexFormat.of().formatHex(hash(canonical)),
                "now", at));
        }
        return FinanceManagementService.operationDto(mapper, mapper.operation(values("key", key)));
    }

    private void ledger(long operation, String event, String sourceType, long source, String unit, String currency,
        BigDecimal delta, Instant at) {
        mapper.insertLedger(values("operation", operation, "event", event, "sourceType", sourceType, "source", source,
            "unit", unit, "currency", currency, "delta", delta, "now", at,
            "key", "P4:" + operation + ":" + event + ":" + sourceType + ":" + source));
    }

    private byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
