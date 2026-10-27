package com.felix.aigatecore.finance;

import com.aigate.contracts.dto.EmployeeQuotaDTO;
import com.aigate.contracts.dto.FinanceOperationDTO;
import com.aigate.contracts.dto.PageDTO;
import com.aigate.contracts.dto.ProviderBudgetDTO;
import com.aigate.contracts.dto.QuotaPolicyDTO;
import com.felix.aigatecore.identity.IdentityMapper;
import com.felix.aigatecore.identity.IdentityService;
import com.felix.aigatecore.identity.RequestContext;
import com.felix.aigatecore.runtime.RuntimeIdentityMapper;
import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** P4管理员策略、原币种预算与只读账务诊断；不提供HTTP扣费入口。 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FinanceManagementService {
    private final FinanceMapper mapper;
    private final IdentityMapper identities;
    private final IdentityService management;
    private final RuntimeIdentityMapper runtime;
    private final FinancePeriods periods;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 装配资金管理与当前身份权威。
     *
     * @param mapper P4存储
     * @param identities 身份与审计存储
     * @param management 当前管理身份
     * @param runtime Agent存储
     * @param periods 公司周期
     * @param clock UTC时钟
     */
    public FinanceManagementService(FinanceMapper mapper, IdentityMapper identities, IdentityService management,
        RuntimeIdentityMapper runtime, FinancePeriods periods, Clock clock) {
        this.mapper = mapper;
        this.identities = identities;
        this.management = management;
        this.runtime = runtime;
        this.periods = periods;
        this.clock = clock;
    }

    static Map<String, Object> values(Object... pairs) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private long administrator(RequestContext context) {
        if (identities.lockBootstrapControl() == null) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Identity state is unavailable");
        }
        return management.requireGlobalAdministrator(context);
    }

    private void audit(long actor, String action, String type, long id, List<String> fields, RequestContext context) {
        identities.insertAuditEvent(values("actorType", "EMPLOYEE", "actorId", actor, "action", action,
            "targetType", type, "targetId", id, "changedFields", json.writeValueAsString(fields),
            "traceId", context.requestId()));
    }

    static String currency(String value) {
        if (value == null || !value.matches("[A-Z]{3}")) {
            throw ApiException.invalid();
        }
        return value;
    }

    static String operationKey(String value) {
        // URL解码由HTTP容器进行一次；不在服务中二次解码或允许路径分隔符。
        if (value == null || !value.matches("[A-Za-z0-9_:-]{8,128}")) {
            throw ApiException.invalid();
        }
        return value;
    }

    private QuotaPolicyDTO dto(FinanceRows.Policy value) {
        return new QuotaPolicyDTO(value.id(), value.scopeType(), value.scopeId(), value.revision(),
            FinanceAmounts.text(value.weeklyLimitPoints(), 6), value.effectiveFrom(), value.status(),
            value.createdAt());
    }

    private ProviderBudgetDTO dto(FinanceRows.Budget value) {
        var parts = mapper.partitions(values("id", value.id()));
        if (parts.size() != 2) {
            throw new IllegalStateException("Invalid provider budget partitions");
        }
        var normal = parts.stream().filter(x -> "NORMAL".equals(x.partitionCode())).findFirst().orElseThrow();
        var emergency = parts.stream().filter(x -> "EMERGENCY".equals(x.partitionCode())).findFirst().orElseThrow();
        return new ProviderBudgetDTO(value.currency(), periods.monthName(value.periodStart()),
            FinanceAmounts.text(value.limitAmount(), 8), FinanceAmounts.text(normal.limitAmount(), 8),
            FinanceAmounts.text(emergency.limitAmount(), 8), FinanceAmounts.text(value.settledAmount(), 8),
            FinanceAmounts.text(value.reservedAmount(), 8), value.riskState(), value.version());
    }

    /**
     * 创建修订草稿；scope锁先于版本分配，不回写任何周桶。
     *
     * @param context 当前身份
     * @param body 严格正文
     * @return 草稿元数据
     */
    public QuotaPolicyDTO createPolicy(RequestContext context, ValidatedBody body) {
        long actor = administrator(context);
        String type = body.rawText("scopeType", 24);
        long scope = body.number("scopeId", true);
        if (!("EMPLOYEE_WEEK".equals(type) && scope == 0)
            && !("AGENT_WEEK".equals(type) && scope > 0)) {
            throw ApiException.invalid();
        }
        if (scope > 0 && runtime.selectAgent(scope) == null) {
            throw ApiException.missing();
        }
        Instant effective = body.instant("effectiveFrom");
        var query = values("scopeType", type, "scopeId", scope, "limit",
            FinanceAmounts.parse(body.rawText("weeklyLimitPoints", 21), 6), "effectiveFrom", effective,
            "actor", actor, "now", now());
        query.put("revision", mapper.nextRevision(query));
        mapper.insertPolicy(query);
        long id = ((Number) query.get("id")).longValue();
        audit(actor, "QUOTA_POLICY_CREATED", "QUOTA_POLICY", id,
            List.of("scopeType", "scopeId", "weeklyLimitPoints", "effectiveFrom"), context);
        return dto(mapper.findPolicy(values("id", id)));
    }

    /**
     * 发布时校验scope当前修订和有效期，历史桶保持冻结限额。
     *
     * @param context 当前身份
     * @param id 策略ID
     * @param body 期望草稿修订
     * @return 已发布策略
     */
    public QuotaPolicyDTO publishPolicy(RequestContext context, long id, ValidatedBody body) {
        long actor = administrator(context);
        var policy = mapper.lockPolicy(values("id", id));
        if (policy == null) {
            throw ApiException.missing();
        }
        if (body.number("expectedRevision", false) != policy.revision() || !"DRAFT".equals(policy.status())) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        var query = values("scopeType", policy.scopeType(), "scopeId", policy.scopeId(), "id", id,
            "revision", policy.revision(), "effectiveFrom", policy.effectiveFrom(), "now", now());
        var previous = mapper.latestActivePolicy(query);
        if (previous != null && (previous.revision() >= policy.revision()
            || !policy.effectiveFrom().isAfter(previous.effectiveFrom()))) {
            throw ApiException.conflict("INVALID_STATE");
        }
        mapper.retirePolicies(query);
        if (mapper.publishPolicy(query) != 1) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        audit(actor, "QUOTA_POLICY_PUBLISHED", "QUOTA_POLICY", id, List.of("status"), context);
        return dto(mapper.findPolicy(values("id", id)));
    }

    private Map<String, Object> page(Map<String, String> input, Set<String> allowed) {
        if (!allowed.containsAll(input.keySet())) {
            throw ApiException.invalid();
        }
        long page = ValidatedBody.pathId(input.getOrDefault("page", "1"), false);
        long size = ValidatedBody.pathId(input.getOrDefault("size", "20"), false);
        if (page > Integer.MAX_VALUE || size > 100) {
            throw ApiException.invalid();
        }
        return values("page", (int) page, "size", (int) size, "offset", (page - 1) * size);
    }

    /**
     * 按当前管理员身份分页读取策略。
     *
     * @param context 当前身份
     * @param input 单值查询条件
     * @return 稳定分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageDTO<QuotaPolicyDTO> listPolicies(RequestContext context, Map<String, String> input) {
        management.requireGlobalAdministrator(context);
        var query = page(input, Set.of("scopeType", "scopeId", "status", "page", "size"));
        if (input.containsKey("scopeType")) {
            if (!Set.of("AGENT_WEEK", "EMPLOYEE_WEEK").contains(input.get("scopeType"))) {
                throw ApiException.invalid();
            }
            query.put("scopeType", input.get("scopeType"));
        }
        if (input.containsKey("status")) {
            if (!Set.of("DRAFT", "ACTIVE", "RETIRED").contains(input.get("status"))) {
                throw ApiException.invalid();
            }
            query.put("status", input.get("status"));
        }
        if (input.containsKey("scopeId")) {
            query.put("scopeId", ValidatedBody.pathId(input.get("scopeId"), true));
        }
        return new PageDTO<>(mapper.listPolicies(query).stream().map(this::dto).toList(),
            (int) query.get("page"), (int) query.get("size"), mapper.countPolicies(query));
    }

    /**
     * 同一父锁下创建或CAS更新总预算及两个分区；FROZEN不由此接口解除。
     *
     * @param context 当前身份
     * @param currency 原币种
     * @param month 严格自然月
     * @param body 固定精度金额与创建哨兵
     * @return 更新结果及首次创建标记
     */
    public BudgetResult putBudget(RequestContext context, String currency, String month, ValidatedBody body) {
        long actor = administrator(context);
        var period = periods.month(month);
        BigDecimal total = FinanceAmounts.parse(body.rawText("limitAmount", 21), 8);
        BigDecimal normal = FinanceAmounts.parse(body.rawText("normalLimitAmount", 21), 8);
        BigDecimal emergency = FinanceAmounts.parse(body.rawText("emergencyLimitAmount", 21), 8);
        if (normal.add(emergency).compareTo(total) > 0 || !body.has("expectedVersion")) {
            throw ApiException.invalid();
        }
        var query = values("currency", currency(currency), "start", period.start(), "end", period.end(),
            "limit", total, "now", now());
        var previous = mapper.lockBudget(query);
        boolean created = previous == null;
        if (created) {
            if (!body.isNull("expectedVersion")) {
                throw ApiException.conflict("VERSION_CONFLICT");
            }
            mapper.insertBudget(query);
            long id = ((Number) query.get("id")).longValue();
            mapper.insertPartition(values("id", id, "code", "NORMAL", "limit", normal, "now", now()));
            mapper.insertPartition(values("id", id, "code", "EMERGENCY", "limit", emergency, "now", now()));
        } else {
            if (body.isNull("expectedVersion") || body.number("expectedVersion", true) != previous.version()) {
                throw ApiException.conflict("VERSION_CONFLICT");
            }
            if (total.compareTo(previous.settledAmount().add(previous.reservedAmount())) < 0) {
                throw ApiException.conflict("INVALID_STATE");
            }
            var parts = mapper.lockPartitions(values("id", previous.id()));
            if (parts.size() != 2) {
                throw new IllegalStateException("Invalid provider budget partitions");
            }
            for (var part : parts) {
                BigDecimal limit = "NORMAL".equals(part.partitionCode()) ? normal : emergency;
                if (limit.compareTo(part.settledAmount().add(part.reservedAmount())) < 0) {
                    throw ApiException.conflict("INVALID_STATE");
                }
                mapper.updatePartitionLimit(values("id", part.id(), "limit", limit, "now", now()));
            }
            query.put("id", previous.id());
            query.put("version", previous.version());
            if (mapper.updateBudgetLimit(query) != 1) {
                throw ApiException.conflict("VERSION_CONFLICT");
            }
        }
        long id = ((Number) query.get("id")).longValue();
        audit(actor, created ? "PROVIDER_BUDGET_CREATED" : "PROVIDER_BUDGET_UPDATED", "PROVIDER_BUDGET", id,
            List.of("limitAmount", "normalLimitAmount", "emergencyLimitAmount"), context);
        return new BudgetResult(dto(mapper.budget(query)), created);
    }

    /**
     * 预算写入结果，不允许Controller重新猜测创建状态。
     *
     * @param budget 安全响应
     * @param created 是否首次创建
     */
    public record BudgetResult(ProviderBudgetDTO budget, boolean created) {
    }

    /**
     * 查询指定原币种月份；不存在返回404。
     *
     * @param context 当前身份
     * @param currency 原币种
     * @param month 月份
     * @return 预算元数据
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProviderBudgetDTO getBudget(RequestContext context, String currency, String month) {
        management.requireGlobalAdministrator(context);
        var budget = mapper.budget(values("currency", currency(currency), "start", periods.month(month).start()));
        if (budget == null) {
            throw ApiException.missing();
        }
        return dto(budget);
    }

    /**
     * 各币种独立显示，不做隐含汇率加总。
     *
     * @param context 当前身份
     * @param input 单值查询
     * @return 预算分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageDTO<ProviderBudgetDTO> listBudgets(RequestContext context, Map<String, String> input) {
        management.requireGlobalAdministrator(context);
        var query = page(input, Set.of("currency", "month", "page", "size"));
        if (input.containsKey("currency")) {
            query.put("currency", currency(input.get("currency")));
        }
        if (input.containsKey("month")) {
            query.put("start", periods.month(input.get("month")).start());
        }
        return new PageDTO<>(mapper.listBudgets(query).stream().map(this::dto).toList(),
            (int) query.get("page"), (int) query.get("size"), mapper.countBudgets(query));
    }

    /**
     * 查询原周账；没有冻结桶时显示null/空列表，不虚构无限或零限额账户。
     *
     * @param context 当前身份
     * @param employee 员工ID
     * @param input 允许at查询
     * @return 原周账户
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EmployeeQuotaDTO quotas(RequestContext context, long employee, Map<String, String> input) {
        management.requireGlobalAdministrator(context);
        if (!Set.of("at").containsAll(input.keySet())) {
            throw ApiException.invalid();
        }
        if (identities.selectEmployeeById(employee) == null) {
            throw ApiException.missing();
        }
        Instant at = input.containsKey("at") ? ValidatedBody.instantValue(input.get("at")) : now();
        var accounts = mapper.quotaAccounts(values("employee", employee, "start", periods.week(at).start()));
        var agent = accounts.stream().filter(x -> "AGENT_WEEK".equals(x.bucketKind())).map(this::account).toList();
        var personal = accounts.stream().filter(x -> "EMPLOYEE_WEEK".equals(x.bucketKind()))
            .map(this::account).findFirst().orElse(null);
        return new EmployeeQuotaDTO(employee, at, agent, personal);
    }

    private EmployeeQuotaDTO.WeeklyAccount account(FinanceRows.Bucket row) {
        return new EmployeeQuotaDTO.WeeklyAccount("AGENT_WEEK".equals(row.bucketKind()) ? row.agentScopeId() : null,
            FinanceAmounts.text(row.limitPoints(), 6), FinanceAmounts.text(row.usedPoints(), 6),
            FinanceAmounts.text(row.reservedPoints(), 6),
            FinanceAmounts.text(row.limitPoints().subtract(row.usedPoints()).subtract(row.reservedPoints()), 6),
            row.periodStart(), row.periodEnd());
    }

    /**
     * 只读安全投影；不返回用量证据明文或摘要。
     *
     * @param context 管理身份
     * @param key 严格操作键
     * @return 账务状态与独立金额
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FinanceOperationDTO operation(RequestContext context, String key) {
        management.requireGlobalAdministrator(context);
        var operation = mapper.operation(values("key", operationKey(key)));
        if (operation == null) {
            throw ApiException.missing();
        }
        return operationDto(mapper, operation);
    }

    static FinanceOperationDTO operationDto(FinanceMapper mapper, FinanceRows.Operation row) {
        var points = mapper.points(values("id", row.id()));
        var spend = mapper.spend(values("id", row.id()));
        boolean pending = Set.of("RESERVED", "PENDING_RECONCILIATION").contains(row.status());
        return new FinanceOperationDTO(row.operationKey(), row.status(), row.employeeId(), row.agentId(),
            row.logicalModelId(), row.serviceClass(), row.pointsPriceVersionId(), row.providerPriceVersionId(),
            row.currency(), FinanceAmounts.text(row.maxPoints(), 6), FinanceAmounts.text(row.maxCurrencyAmount(), 8),
            FinanceAmounts.text(pending ? points.reservedPoints() : BigDecimal.ZERO, 6),
            pending ? null : FinanceAmounts.text(points.settledPoints(), 6),
            spend == null ? null : FinanceAmounts.text(pending ? spend.reservedAmount() : BigDecimal.ZERO, 8),
            pending || spend == null ? null : FinanceAmounts.text(spend.settledAmount(), 8),
            row.usageEvidenceDigest() != null, row.createdAt(), row.updatedAt());
    }
}
