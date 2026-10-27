package com.felix.aigatecore.finance;

import static com.felix.aigatecore.finance.FinanceManagementService.values;

import com.aigate.contracts.dto.GrantDTO;
import com.aigate.contracts.dto.ManagementProfileDTO;
import com.aigate.contracts.dto.PageDTO;
import com.aigate.contracts.dto.TeamPoolDTO;
import com.felix.aigatecore.identity.IdentityMapper;
import com.felix.aigatecore.identity.IdentityService;
import com.felix.aigatecore.identity.RequestContext;
import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * P5团队池配置、跨Team发行、永久收据和撤销；身份、Pool、Grant、账本、Outbox与审计同库提交。 管理写先锁身份协调行，再锁Pool/Grant；消费事务使用共享身份锁，撤销不穿越既有预留。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class GrantService {
    private final GrantMapper mapper;
    private final IdentityMapper identities;
    private final IdentityService management;
    private final FinancePeriods periods;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 装配P5当前身份权威、存储及公司周期。
     *
     * @param mapper P5固定SQL存储
     * @param identities 组织与审计存储
     * @param management 当前管理身份验证
     * @param periods 公司时区周期
     * @param clock 可替换时钟
     */
    public GrantService(GrantMapper mapper, IdentityMapper identities, IdentityService management,
        FinancePeriods periods, Clock clock) {
        this.mapper = mapper;
        this.identities = identities;
        this.management = management;
        this.periods = periods;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private void coordinate() {
        if (identities.lockBootstrapControl() == null) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Identity state is unavailable");
        }
    }

    private boolean administrator(ManagementProfileDTO profile) {
        return profile.roles().stream().anyMatch(role -> "PLATFORM_ADMIN".equals(role.roleCode())
            && "GLOBAL".equals(role.scopeType()) && role.scopeId() == 0);
    }

    private long teamActor(RequestContext context, long teamId, boolean activeRequired) {
        var profile = management.getCurrentProfile(context);
        boolean admin = administrator(profile);
        boolean manager = profile.roles().stream().anyMatch(role -> "TEAM_MANAGER".equals(role.roleCode())
            && "TEAM".equals(role.scopeType()) && role.scopeId() == teamId);
        // 授权先于目标查询，不利用404/total让外部身份探查其它Team。
        if (!admin && !manager) {
            throw ApiException.forbidden();
        }
        var team = identities.selectTeamById(teamId);
        if (team == null) {
            throw ApiException.missing();
        }
        if (!"ACTIVE".equals(team.status()) && (activeRequired || !admin)) {
            throw ApiException.forbidden();
        }
        return profile.employee().id();
    }

    private void audit(long actor, String action, String type, long id, List<String> fields, RequestContext context) {
        identities.insertAuditEvent(values("actorType", "EMPLOYEE", "actorId", actor, "action", action,
            "targetType", type, "targetId", id, "changedFields", json.writeValueAsString(fields),
            "traceId", context.requestId()));
    }

    private String reason(ValidatedBody body) {
        String reason = body.rawText("reasonCode", 64);
        if (!reason.matches("[A-Z][A-Z0-9_-]{1,63}")) {
            throw ApiException.invalid();
        }
        return reason;
    }

    private TeamPoolDTO dto(GrantRows.Pool pool) {
        return new TeamPoolDTO(pool.teamId(), periods.dateName(pool.periodStart()), periods.dateName(pool.periodEnd()),
            FinanceAmounts.text(pool.limitPoints(), 6), FinanceAmounts.text(pool.issuedPoints(), 6),
            FinanceAmounts.text(pool.reservedIssuePoints(), 6), FinanceAmounts.text(pool.limitPoints()
                .subtract(pool.issuedPoints()).subtract(pool.reservedIssuePoints()), 6),
            pool.status(), pool.version(), pool.updatedAt());
    }

    private GrantDTO dto(GrantRows.Grant grant, Instant at) {
        String status = "ACTIVE".equals(grant.status()) && !grant.expiresAt().isAfter(at) ? "EXPIRED" : grant.status();
        BigDecimal available = "ACTIVE".equals(status)
            ? grant.issuedPoints().subtract(grant.usedPoints()).subtract(grant.reservedPoints())
            : BigDecimal.ZERO;
        return new GrantDTO(grant.id(), grant.issuerTeamId(), grant.recipientEmployeeId(),
            FinanceAmounts.text(grant.issuedPoints(), 6), FinanceAmounts.text(grant.usedPoints(), 6),
            FinanceAmounts.text(grant.reservedPoints(), 6), FinanceAmounts.text(available, 6), grant.expiresAt(),
            grant.issuedAt(), status, grant.version());
    }

    /**
     * Admin配置池；已发行额度不因剩余Grant到期或撤销回冲。
     *
     * @param context 当前身份
     * @param team 发行Team
     * @param start 严格本地日期
     * @param body 固定精度金额、periodEnd及创建null哨兵
     * @return 创建或更新结果
     */
    public PoolResult putPool(RequestContext context, long team, String start, ValidatedBody body) {
        coordinate();
        long actor = management.requireGlobalAdministrator(context);
        teamActor(context, team, true);
        Instant from = periods.date(start);
        Instant end = periods.date(body.rawText("periodEnd", 10));
        BigDecimal limit = FinanceAmounts.parse(body.rawText("limitPoints", 21), 6);
        if (!end.isAfter(from) || !body.has("expectedVersion")) {
            throw ApiException.invalid();
        }
        Long expected = body.isNull("expectedVersion") ? null : body.number("expectedVersion", true);
        var query = values("team", team, "start", from, "end", end, "limit", limit, "actor", actor, "now", now());
        var previous = mapper.lockPool(query);
        boolean created = previous == null;
        if (created ? expected != null : expected == null || expected != previous.version()) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        query.put("exclude", created ? 0 : previous.id());
        if (mapper.overlap(query) != null) {
            // POST Grant没有Pool ID；不允许同一发行时刻匹配多个Pool而任意选账户。
            throw ApiException.conflict("POOL_PERIOD_OVERLAP");
        }
        if (created) {
            mapper.insertPool(query);
        } else {
            if (limit.compareTo(previous.issuedPoints().add(previous.reservedIssuePoints())) < 0) {
                throw ApiException.conflict("INVALID_STATE");
            }
            query.put("id", previous.id());
            query.put("version", previous.version());
            if (mapper.updatePool(query) != 1) {
                throw ApiException.conflict("VERSION_CONFLICT");
            }
        }
        long id = ((Number) query.get("id")).longValue();
        audit(actor, created ? "TEAM_POOL_CREATED" : "TEAM_POOL_UPDATED", "TEAM_POOL", id,
            List.of("limitPoints", "periodEnd"), context);
        return new PoolResult(dto(mapper.pool(query)), created);
    }

    /**
     * 池配置的HTTP结果。
     *
     * @param pool 安全余额投影
     * @param created 是否首次创建
     */
    public record PoolResult(TeamPoolDTO pool, boolean created) {
    }

    /**
     * 仅Admin或该Team当前Manager可读取指定周期。
     *
     * @param context 当前身份
     * @param team 发行Team
     * @param input 单值periodStart
     * @return 真实池余额
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TeamPoolDTO pool(RequestContext context, long team, Map<String, String> input) {
        teamActor(context, team, false);
        if (!input.keySet().equals(Set.of("periodStart"))) {
            throw ApiException.invalid();
        }
        var pool = mapper.pool(values("team", team, "start", periods.date(input.get("periodStart"))));
        if (pool == null) {
            throw ApiException.missing();
        }
        return dto(pool);
    }

    private byte[] digest(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    /**
     * Pool发行、Grant、永久Receipt、Ledger、Outbox、审计同事务；重复请求明确409。
     *
     * @param context 当前身份
     * @param team 真实发行Team
     * @param body 受控跨Team接收者、定点正金额、到期与原因
     * @param operationKey 全局唯一操作键
     * @return 新发行Grant
     */
    public GrantDTO issue(RequestContext context, long team, ValidatedBody body, String operationKey) {
        coordinate();
        long actor = teamActor(context, team, true);
        String key = ValidatedBody.idempotency(operationKey);
        long recipient = body.number("recipientEmployeeId", false);
        BigDecimal amount = FinanceAmounts.parse(body.rawText("points", 21), 6);
        Instant expires = body.instant("expiresAt");
        String reason = reason(body);
        if (amount.signum() <= 0 || expires.atZone(java.time.ZoneOffset.UTC).getYear() > 9999
            || expires.atZone(java.time.ZoneOffset.UTC).getYear() < 1000) {
            throw ApiException.invalid();
        }
        var request = new TreeMap<String, Object>();
        request.put("issuerTeamId", team);
        request.put("recipientEmployeeId", recipient);
        request.put("points", FinanceAmounts.text(amount, 6));
        request.put("expiresAt", expires.toString());
        request.put("reasonCode", reason);
        byte[] digest = digest(json.writeValueAsString(request));
        var previous = mapper.receipt(values("key", key));
        if (previous != null) {
            if (previous.actorEmployeeId() == actor && MessageDigest.isEqual(digest, previous.operationDigest())) {
                // 不增加error字段；仅向原已授权请求者返回其自身安全Grant ID。
                throw new ApiException(409, "GRANT_ALREADY_ISSUED", "Grant already issued; grantId="
                    + previous.teamGrantId());
            }
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT");
        }
        Instant at = now();
        if (!expires.isAfter(at)) {
            throw ApiException.invalid();
        }
        var employee = identities.selectEmployeeById(recipient);
        if (employee == null) {
            throw ApiException.missing();
        }
        var recipientTeam = identities.selectTeamById(employee.primaryTeamId());
        if (!"ACTIVE".equals(employee.status()) || recipientTeam == null || !"ACTIVE".equals(recipientTeam.status())) {
            throw ApiException.conflict("INVALID_STATE");
        }
        var pools = mapper.currentPool(values("team", team, "at", at));
        if (pools.size() != 1) {
            throw ApiException.conflict("TEAM_POOL_UNAVAILABLE");
        }
        var pool = pools.getFirst();
        var query = values("team", team, "recipient", recipient, "pool", pool.id(), "key", key, "amount", amount,
            "expires", expires, "now", at, "actor", actor, "reason", reason, "digest", digest, "id", pool.id());
        if (mapper.issuePool(query) != 1) {
            throw ApiException.conflict("INSUFFICIENT_TEAM_POOL");
        }
        mapper.insertGrant(query);
        long grant = ((Number) query.get("id")).longValue();
        query.put("grant", grant);
        mapper.insertReceipt(query);
        String businessKey = "GRANT:" + grant + ":ISSUED:1";
        query.put("businessKey", businessKey);
        mapper.insertIssueLedger(query);
        String payload = payload(grant, team, recipient, amount, at);
        query.put("eventId", UUID.randomUUID().toString());
        query.put("payload", payload);
        query.put("payloadDigest", HexFormat.of().formatHex(digest(payload)));
        mapper.insertIssueOutbox(query);
        audit(actor, "TEAM_GRANT_ISSUED", "TEAM_GRANT", grant,
            List.of("issuerTeamId", "recipientEmployeeId", "issuedPoints", "expiresAt", "issuedReasonCode"), context);
        return dto(mapper.grant(values("id", grant)), at);
    }

    private String payload(long grant, long team, long recipient, BigDecimal amount, Instant at) {
        var payload = new TreeMap<String, Object>();
        for (String key : List.of("employeeId", "usageTeamSnapshotId", "agentId", "logicalModelId", "deploymentId",
            "providerId", "resourcePoolId", "serviceClass", "fundingMode", "currency", "currencyAmount", "tokenCount",
            "pointsPriceVersionId", "purchasePriceVersionId", "supersedesEventId", "correctionReasonCode")) {
            payload.put(key, null);
        }
        payload.put("sourceBusinessId", Long.toString(grant));
        payload.put("sourceRevision", 1);
        payload.put("metricType", "TEAM_GRANT_ISSUED_POINTS");
        payload.put("businessDate", periods.dateName(at));
        payload.put("issuerTeamId", team);
        payload.put("recipientEmployeeId", recipient);
        payload.put("unit", "POINTS");
        payload.put("pointsAmount", FinanceAmounts.text(amount, 6));
        payload.put("isCorrection", false);
        return json.writeValueAsString(payload);
    }

    /**
     * 撤销仅阻止新消费；旧预留、到期账和TeamPool发行事实保持。
     *
     * @param context 当前身份
     * @param team 发行Team
     * @param grant Grant ID
     * @param body 审计原因
     */
    public void revoke(RequestContext context, long team, long grant, ValidatedBody body) {
        coordinate();
        long actor = teamActor(context, team, false);
        String reason = reason(body);
        var value = mapper.lockGrant(values("id", grant));
        if (value == null || value.issuerTeamId() != team) {
            throw ApiException.missing();
        }
        if ("REVOKED_FOR_NEW_USE".equals(value.status())) {
            return;
        }
        mapper.revokeGrant(values("id", grant, "actor", actor, "reason", reason, "now", now()));
        audit(actor, "TEAM_GRANT_REVOKED", "TEAM_GRANT", grant,
            List.of("status", "revokedAt", "revokedBy", "revokeReasonCode"), context);
    }

    private Map<String, Object> page(Map<String, String> input, Instant at) {
        if (!Set.of("status", "page", "size").containsAll(input.keySet())) {
            throw ApiException.invalid();
        }
        long page = ValidatedBody.pathId(input.getOrDefault("page", "1"), false);
        long size = ValidatedBody.pathId(input.getOrDefault("size", "20"), false);
        if (page > Integer.MAX_VALUE || size > 100) {
            throw ApiException.invalid();
        }
        var query = values("page", (int) page, "size", (int) size, "offset", (page - 1) * size, "at", at);
        if (input.containsKey("status")) {
            if (!Set.of("ACTIVE", "EXPIRED", "REVOKED_FOR_NEW_USE").contains(input.get("status"))) {
                throw ApiException.invalid();
            }
            query.put("status", input.get("status"));
        }
        return query;
    }

    private PageDTO<GrantDTO> list(Map<String, Object> query, Instant at) {
        return new PageDTO<>(mapper.listGrants(query).stream().map(grant -> dto(grant, at)).toList(),
            (int) query.get("page"), (int) query.get("size"), mapper.countGrants(query));
    }

    /**
     * 发行Team范围过滤在SQL LIMIT与COUNT之前，不因接收者转Team变化。
     *
     * @param context 当前身份
     * @param team 发行Team
     * @param input 单值分页条件
     * @return 本Team发行分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageDTO<GrantDTO> issued(RequestContext context, long team, Map<String, String> input) {
        teamActor(context, team, false);
        Instant at = now();
        var query = page(input, at);
        query.put("team", team);
        return list(query, at);
    }

    /**
     * 领取列表始终来自真实mgt_本人，不接受外部employeeId。
     *
     * @param context 当前管理身份
     * @param input 严格分页条件
     * @return 本人领取Grant
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageDTO<GrantDTO> received(RequestContext context, Map<String, String> input) {
        long employee = management.getCurrentProfile(context).employee().id();
        Instant at = now();
        var query = page(input, at);
        query.put("recipient", employee);
        return list(query, at);
    }
}
