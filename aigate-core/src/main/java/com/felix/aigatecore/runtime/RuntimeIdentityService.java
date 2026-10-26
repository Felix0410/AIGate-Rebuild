package com.felix.aigatecore.runtime;

import com.aigate.contracts.ApiContractConstants;
import com.aigate.contracts.dto.AgentDTO;
import com.aigate.contracts.dto.AgentEmployeePermissionDTO;
import com.aigate.contracts.dto.EmployeeDTO;
import com.aigate.contracts.dto.KeyMetadataDTO;
import com.aigate.contracts.dto.NewKeyDTO;
import com.aigate.contracts.dto.PageDTO;
import com.aigate.contracts.dto.RuntimeIdentityDTO;
import com.aigate.contracts.dto.TeamDTO;
import com.felix.aigatecore.identity.IdentityMapper;
import com.felix.aigatecore.identity.IdentityService;
import com.felix.aigatecore.identity.RequestContext;
import com.felix.aigatecore.security.CredentialCodec;
import com.felix.aigatecore.security.RuntimeSecuritySettings;
import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Agent、运行凭证和员工授权服务，不调用模型、资金或 Provider 模块。
 *
 * @author AIGate
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RuntimeIdentityService {

    /** 最短签发有效期，按服务端固定取样时间判断。 */
    private static final Duration MIN_KEY_LIFETIME = Duration.ofHours(1);

    /** 最长签发有效期，避免长期凭证脱离轮换管理。 */
    private static final Duration MAX_KEY_LIFETIME = Duration.ofDays(90);

    /** 轮换后旧凭证允许重叠认证的最长时间。 */
    private static final Duration MAX_ROTATION_GRACE = Duration.ofHours(24);

    /** 同一主体在当前时刻最多可认证两把凭证。 */
    private static final int MAX_EFFECTIVE_KEYS = 2;

    /** 只展示用于人工识别的短前缀，不能凭此前缀认证。 */
    private static final int KEY_PREFIX_LENGTH = 12;

    private final RuntimeIdentityMapper mapper;

    private final IdentityMapper identityMapper;

    private final IdentityService identityService;

    private final RuntimeSecuritySettings settings;

    private final CredentialCodec codec;

    private final Clock clock;

    private final SecureRandom random = new SecureRandom();

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 注入数据访问、管理认证、独立密钥配置及可替换时钟。
     *
     * @param mapper 运行身份数据访问
     * @param identityMapper 组织和审计数据访问
     * @param identityService 管理凭证及权限检查
     * @param settings 两类运行 Pepper
     * @param codec HMAC 工具
     * @param clock UTC 时钟
     */
    public RuntimeIdentityService(RuntimeIdentityMapper mapper, IdentityMapper identityMapper,
        IdentityService identityService, RuntimeSecuritySettings settings, CredentialCodec codec, Clock clock) {
        this.mapper = mapper;
        this.identityMapper = identityMapper;
        this.identityService = identityService;
        this.settings = settings;
        this.codec = codec;
        this.clock = clock;
    }

    /**
     * 取得可无损写入 DATETIME(6) 的 UTC 微秒时间。
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 与 P1 管理变更协调加锁，并在锁内复查当前全局管理员权限。
     */
    private long writeAdministrator(RequestContext context) {
        // 1、与 P1 使用相同的管理写锁，避免撤权与新签发之间出现陈旧授权。
        if (identityMapper.lockBootstrapControl() == null) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Identity state is unavailable");
        }

        // 2、拿到锁之后重新检查当前管理员，而非沿用进入过滤器时的身份。
        return identityService.requireGlobalAdministrator(context);
    }

    /**
     * 读取 Agent；写事务传入 lock=true，保证后续检查基于锁定行。
     */
    private AgentDTO agent(long id, boolean lock) {
        AgentDTO result = lock ? mapper.lockAgent(id) : mapper.selectAgent(id);
        if (result == null) {
            throw ApiException.missing();
        }
        return result;
    }

    /**
     * 读取员工；运行凭证变更必须先锁定该员工 Owner 行。
     */
    private EmployeeDTO employee(long id, boolean lock) {
        EmployeeDTO result = lock ? mapper.lockEmployee(id) : identityMapper.selectEmployeeById(id);
        if (result == null) {
            throw ApiException.missing();
        }
        return result;
    }

    /**
     * 检查团队存在且 ACTIVE，不允许新对象指向停用团队。
     */
    private void activeTeam(long id) {
        TeamDTO team = identityMapper.selectTeamById(id);
        if (team == null) {
            throw ApiException.missing();
        }
        if (!"ACTIVE".equals(team.status())) {
            throw ApiException.conflict("INVALID_STATE");
        }
    }

    /**
     * 固定 Owner→Key 的锁顺序，按操作需要检查主体及其团队状态。
     */
    private void lockOwner(boolean agentKey, long ownerId, boolean requireActive) {
        // Owner 行在凭证行之前加锁，所有签发、轮换和吊销使用同一顺序。
        String status;
        long teamId;
        if (agentKey) {
            AgentDTO owner = agent(ownerId, true);
            status = owner.status();
            teamId = owner.ownerTeamId();
        } else {
            EmployeeDTO owner = employee(ownerId, true);
            status = owner.status();
            teamId = owner.primaryTeamId();
        }
        if (requireActive) {
            if (!"ACTIVE".equals(status)) {
                throw ApiException.conflict("INVALID_STATE");
            }
            activeTeam(teamId);
        }
    }

    /**
     * 检查 JDBC 回填主键，回填异常时整体回滚，不能返回虚假成功。
     */
    private long generatedId(Map<String, Object> values) {
        Object value = values.get("id");
        if (!(value instanceof Number id) || id.longValue() <= 0) {
            throw new IllegalStateException("Invalid generated identity id");
        }
        return id.longValue();
    }

    /**
     * 在当前业务事务追加审计，只记录字段名，不序列化凭证或 Pepper。
     */
    private void audit(long actorId, String action, String targetType, long targetId, List<String> fields,
        RequestContext context) {
        identityMapper.insertAuditEvent(new HashMap<>(Map.of("actorType", "EMPLOYEE", "actorId", actorId,
            "action", action, "targetType", targetType, "targetId", targetId,
            "changedFields", json.writeValueAsString(fields), "traceId", context.requestId())));
    }

    /**
     * 校验列表参数白名单及分页范围，偏移量使用 long 避免乘法溢出。
     */
    private Map<String, Object> page(Map<String, String> input, Set<String> allowed) {
        if (!allowed.containsAll(input.keySet())) {
            throw ApiException.invalid();
        }
        long page = ValidatedBody.pathId(input.getOrDefault("page", "1"), false);
        long size = ValidatedBody.pathId(input.getOrDefault("size", "20"), false);
        if (page > Integer.MAX_VALUE || size > ApiContractConstants.MAX_PAGE_SIZE) {
            throw ApiException.invalid();
        }
        return new HashMap<>(Map.of("page", (int) page, "size", (int) size, "offset", (page - 1) * size));
    }

    /**
     * 将安全 DTO 列表与分页统计组装为统一响应。
     */
    private <T> PageDTO<T> result(List<T> rows, Map<String, Object> query, long total) {
        return new PageDTO<>(rows, (int) query.get("page"), (int) query.get("size"), total);
    }

    /**
     * 将合法枚举状态加入查询条件，拒绝未知状态值。
     */
    private void status(Map<String, String> input, Map<String, Object> query, Set<String> allowed) {
        if (input.containsKey("status")) {
            String status = input.get("status");
            if (!allowed.contains(status)) {
                throw ApiException.invalid();
            }
            query.put("status", status);
        }
    }

    /**
     * 创建 Agent，应用编码在创建后不可修改。
     *
     * @param context 当前管理请求
     * @param body 已检查的正文
     * @return 新 Agent
     */
    public AgentDTO createAgent(RequestContext context, ValidatedBody body) {
        // 1、检查管理员及 ACTIVE 归属团队。
        long actorId = writeAdministrator(context);
        long teamId = body.number("ownerTeamId", false);
        activeTeam(teamId);

        // 2、创建 Agent，团队与创建人使用真实外键。
        Map<String, Object> values = new HashMap<>(Map.of("applicationCode", body.code("applicationCode"),
            "displayName", body.text("displayName", 100), "ownerTeamId", teamId, "actorId", actorId, "now", now()));
        mapper.insertAgent(values);
        long id = generatedId(values);

        // 3、在同一事务记录创建审计，响应不包含运行代码或凭证。
        audit(actorId, "AGENT_CREATED", "AGENT", id, List.of("applicationCode", "displayName", "ownerTeamId"), context);
        return agent(id, false);
    }

    /**
     * 查询 Agent，只有全局管理员可以访问。
     *
     * @param context 当前管理请求
     * @param id Agent 主键
     * @return Agent 信息
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public AgentDTO getAgent(RequestContext context, long id) {
        identityService.requireGlobalAdministrator(context);
        return agent(id, false);
    }

    /**
     * 更新 Agent 名称、归属团队和状态。
     *
     * @param context 当前管理请求
     * @param id Agent 主键
     * @param body 已检查的 PATCH 正文
     * @return 更新后的 Agent
     */
    public AgentDTO updateAgent(RequestContext context, long id, ValidatedBody body) {
        // 1、认证后锁定 Agent，并检查期望版本和修改字段。
        long actorId = writeAdministrator(context);
        AgentDTO old = agent(id, true);
        long expected = body.number("expectedVersion", true);
        if (!body.has("displayName") && !body.has("ownerTeamId") && !body.has("status")) {
            throw ApiException.invalid();
        }
        if (expected != old.version()) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }

        // 2、未提供的字段保留原值，归属团队必须仍为 ACTIVE。
        String name = body.has("displayName") ? body.text("displayName", 100) : old.displayName();
        String state = body.has("status") ? body.status() : old.status();
        long teamId = body.has("ownerTeamId") ? body.number("ownerTeamId", false) : old.ownerTeamId();
        activeTeam(teamId);
        if (mapper.updateAgent(Map.of("id", id, "displayName", name, "status", state, "ownerTeamId", teamId,
            "expectedVersion", expected, "now", now())) != 1) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }

        // 3、只记录客户端实际提交的字段名，不因转团队隐式增加或删除员工授权。
        List<String> fields = new ArrayList<>();
        for (String field : List.of("displayName", "ownerTeamId", "status")) {
            if (body.has(field)) {
                fields.add(field);
            }
        }
        audit(actorId, "AGENT_UPDATED", "AGENT", id, fields, context);
        return agent(id, false);
    }

    /**
     * 按状态、归属团队分页查询 Agent。
     *
     * @param context 当前管理请求
     * @param input 查询参数
     * @return Agent 分页
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<AgentDTO> listAgents(RequestContext context, Map<String, String> input) {
        identityService.requireGlobalAdministrator(context);
        Map<String, Object> query = page(input, Set.of("page", "size", "status", "ownerTeamId"));
        status(input, query, Set.of("ACTIVE", "DISABLED"));
        if (input.containsKey("ownerTeamId")) {
            query.put("ownerTeamId", ValidatedBody.pathId(input.get("ownerTeamId"), false));
        }
        return result(mapper.listAgents(query), query, mapper.countAgents(query));
    }

    /**
     * 创建两类凭证共用的查询参数，表名只能由内部用途标志选择。
     */
    private Map<String, Object> keyQuery(boolean agentKey, long ownerId) {
        return new HashMap<>(Map.of("agentKey", agentKey, "ownerId", ownerId));
    }

    /**
     * 在已锁定 Owner 的事务内处理收据、旧凭证窗口和新凭证入库。
     */
    private NewKeyDTO issue(boolean agentKey, long ownerId, Long oldId, long actorId, String operationKey,
        Instant expiry) {
        Instant now = now();
        Map<String, Object> values = keyQuery(agentKey, ownerId);
        values.put("actorId", actorId);
        values.put("key", ValidatedBody.idempotency(operationKey));

        // 1、先检查永久签发记录；重放请求不重新校验已过去的首次签发时间窗口。
        RuntimeKeyDO receipt = mapper.selectReceipt(values);
        if (receipt != null) {
            if (receipt.ownerId() != ownerId || !receipt.expiresAt().equals(expiry)
                || !Objects.equals(receipt.parentId(), oldId)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT");
            }
            throw ApiException.conflict("KEY_ALREADY_ISSUED_NOT_REVEALABLE");
        }
        if (expiry.isBefore(now.plus(MIN_KEY_LIFETIME)) || expiry.isAfter(now.plus(MAX_KEY_LIFETIME))) {
            throw ApiException.invalid();
        }

        // 2、先锁 Owner 再锁旧凭证，检查归属与当前有效性。
        Instant grace = null;
        if (oldId != null) {
            values.put("id", oldId);
            RuntimeKeyDO old = mapper.lockKey(values);
            if (old == null || old.ownerId() != ownerId) {
                throw ApiException.missing();
            }
            if (!old.authenticatableAt(now)) {
                throw ApiException.conflict("INVALID_STATE");
            }
            grace = now.plus(MAX_ROTATION_GRACE);
            if (old.expiresAt().isBefore(grace)) {
                grace = old.expiresAt();
            }
            if (old.graceExpiresAt() != null && old.graceExpiresAt().isBefore(grace)) {
                grace = old.graceExpiresAt();
            }
        }
        values.put("now", now);
        if (mapper.countEffectiveKeys(values) >= MAX_EFFECTIVE_KEYS) {
            throw ApiException.conflict("KEY_LIMIT_EXCEEDED");
        }

        // 3、生成随机密钥，数据库只保存摘要和签发信息。
        byte[] bytes = new byte[ApiContractConstants.SECRET_RANDOM_BYTES];
        random.nextBytes(bytes);
        String token = (agentKey ? "agk_" : "empk_") + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String prefix = token.substring(0, KEY_PREFIX_LENGTH);
        var pepper = settings.current(agentKey);
        values.put("keyPrefix", prefix);
        values.put("digest", codec.digest(token, pepper));
        values.put("pepperKeyId", pepper.id());
        values.put("expiresAt", expiry);
        values.put("parentId", oldId);
        mapper.insertKey(values);
        long id = generatedId(values);

        // 4、新凭证和旧宽限期一起提交；已开始的宽限窗口不能被再次延长。
        if (oldId != null) {
            values.put("id", oldId);
            values.put("grace", grace);
            if (mapper.supersedeKey(values) != 1) {
                throw ApiException.conflict("INVALID_STATE");
            }
        }
        return new NewKeyDTO(id, token, prefix, now, expiry, grace);
    }

    /**
     * 签发或轮换运行凭证，完整密钥仅在首次成功时返回。
     *
     * @param context 当前管理请求
     * @param agentKey 是否为 Agent 凭证
     * @param ownerId 凭证所属主体
     * @param oldId 轮换来源，普通签发时为 null
     * @param body 仅含到期时间的正文
     * @param operationKey 物理签发幂等键
     * @return 首次签发的新密钥
     */
    public NewKeyDTO issueKey(RequestContext context, boolean agentKey, long ownerId, Long oldId,
        ValidatedBody body, String operationKey) {
        // 1、拿到管理写锁并认证管理员，再锁定当前 ACTIVE 主体。
        long actorId = writeAdministrator(context);
        lockOwner(agentKey, ownerId, true);

        // 2、处理一次性签发，签发事实与审计必须在同一事务提交。
        NewKeyDTO key = issue(agentKey, ownerId, oldId, actorId, operationKey, body.instant("expiresAt"));
        audit(actorId, oldId == null ? "RUNTIME_KEY_ISSUED" : "RUNTIME_KEY_ROTATED",
            agentKey ? "AGENT_API_KEY" : "EMPLOYEE_CREDENTIAL", key.credentialId(), List.of("expiresAt"), context);
        return key;
    }

    /**
     * 幂等吊销运行凭证，不删除原始签发记录。
     *
     * @param context 当前管理请求
     * @param agentKey 是否为 Agent 凭证
     * @param ownerId 凭证所属主体
     * @param keyId 凭证主键
     */
    public void revokeKey(RequestContext context, boolean agentKey, long ownerId, long keyId) {
        // 1、先锁主体再锁凭证，停用主体仍可清理历史密钥。
        long actorId = writeAdministrator(context);
        lockOwner(agentKey, ownerId, false);
        Map<String, Object> query = keyQuery(agentKey, ownerId);
        query.put("id", keyId);
        RuntimeKeyDO key = mapper.lockKey(query);
        if (key == null || key.ownerId() != ownerId) {
            throw ApiException.missing();
        }

        // 2、只对首次吊销写审计；重复请求保留原吊销时间且不追加成功事件。
        query.put("now", now());
        if (mapper.revokeKey(query) == 1) {
            audit(actorId, "RUNTIME_KEY_REVOKED", agentKey ? "AGENT_API_KEY" : "EMPLOYEE_CREDENTIAL", keyId,
                List.of("revokedAt"), context);
        }
    }

    /**
     * 查询运行凭证历史，不返回密钥和摘要。
     *
     * @param context 当前管理请求
     * @param agentKey 是否为 Agent 凭证
     * @param ownerId 凭证所属主体
     * @param input 分页参数
     * @return 凭证元数据分页
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<KeyMetadataDTO> listKeys(RequestContext context, boolean agentKey, long ownerId,
        Map<String, String> input) {
        identityService.requireGlobalAdministrator(context);
        if (agentKey) {
            agent(ownerId, false);
        } else {
            employee(ownerId, false);
        }
        Map<String, Object> query = page(input, Set.of("page", "size"));
        query.putAll(keyQuery(agentKey, ownerId));
        return result(mapper.listKeys(query), query, mapper.countKeys(query));
    }

    /**
     * 授予或撤销员工使用 Agent 的授权，重复操作不追加审计。
     *
     * @param context 当前管理请求
     * @param agentId Agent 主键
     * @param employeeId 员工主键
     * @param grant 是否授予
     * @return 授予时是否发生真实状态变化
     */
    public boolean changePermission(RequestContext context, long agentId, long employeeId, boolean grant) {
        // 1、复查管理员，按固定顺序锁 Agent 和员工。
        long actorId = writeAdministrator(context);
        AgentDTO target = agent(agentId, true);
        EmployeeDTO employee = employee(employeeId, true);
        if (grant) {
            if (!"ACTIVE".equals(target.status()) || !"ACTIVE".equals(employee.status())) {
                throw ApiException.conflict("INVALID_STATE");
            }
            activeTeam(target.ownerTeamId());
            activeTeam(employee.primaryTeamId());
        }

        // 2、同一对员工与 Agent 只保留一行，重复授予或撤销直接结束。
        Map<String, Object> values = new HashMap<>(Map.of("agentId", agentId, "employeeId", employeeId,
            "actorId", actorId, "now", now()));
        AgentEmployeePermissionDTO previous = mapper.selectPermission(values);
        if (grant
            ? previous != null && "GRANTED".equals(previous.status())
            : previous == null || "REVOKED".equals(previous.status())) {
            return false;
        }
        if (grant) {
            mapper.grantPermission(values);
        } else {
            mapper.revokePermission(values);
        }

        // 3、状态变化与审计使用同一事务，恢复授权只更新原关系并递增版本。
        audit(actorId, grant ? "AGENT_EMPLOYEE_GRANTED" : "AGENT_EMPLOYEE_REVOKED", "AGENT", agentId,
            List.of("employeeId", "status"), context);
        return true;
    }

    /**
     * 查询 Agent 的员工授权，包括已撤销关系。
     *
     * @param context 当前管理请求
     * @param agentId Agent 主键
     * @param input 分页和状态条件
     * @return 授权分页
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<AgentEmployeePermissionDTO> listPermissions(RequestContext context, long agentId,
        Map<String, String> input) {
        identityService.requireGlobalAdministrator(context);
        agent(agentId, false);
        Map<String, Object> query = page(input, Set.of("page", "size", "status"));
        status(input, query, Set.of("GRANTED", "REVOKED"));
        query.put("agentId", agentId);
        return result(mapper.listPermissions(query), query, mapper.countPermissions(query));
    }

    /**
     * 按指定用途验证前缀、HMAC 和时间窗口，失败统一为 401。
     */
    private RuntimeKeyDO authenticate(boolean agentKey, String token, Instant now) {
        if (token == null || !token.matches((agentKey ? "agk_" : "empk_") + "[A-Za-z0-9_-]{43}")) {
            throw ApiException.unauthenticated();
        }
        for (var pepper : settings.accepted(agentKey)) {
            RuntimeKeyDO key = mapper.selectByDigest(Map.of("agentKey", agentKey, "digest", codec.digest(token, pepper),
                "pepperKeyId", pepper.id()));
            if (key != null && key.authenticatableAt(now)) {
                return key;
            }
        }
        throw ApiException.unauthenticated();
    }

    /**
     * 通过两类运行 Key 及当前 Owner、团队、ACL 验证身份，仅供内网调用。
     *
     * @param agentAuthorization Agent 的 Bearer 凭证
     * @param employeeKey 员工运行凭证
     * @return 最小身份响应，不代表已获模型调用或出站许可
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public RuntimeIdentityDTO verify(String agentAuthorization, String employeeKey) {
        // 1、分别使用独立 Pepper 检查两类前缀、摘要和时间窗口。
        Instant now = now();
        if (agentAuthorization == null || !agentAuthorization.startsWith("Bearer ")) {
            throw ApiException.unauthenticated();
        }
        RuntimeKeyDO agentKey = authenticate(true, agentAuthorization.substring(7), now);
        RuntimeKeyDO credential = authenticate(false, employeeKey, now);

        // 2、查询当前 Agent、员工及双方归属团队，任一失效都拒绝认证。
        AgentDTO agent = mapper.selectAgent(agentKey.ownerId());
        EmployeeDTO employee = identityMapper.selectEmployeeById(credential.ownerId());
        if (agent == null || employee == null || !"ACTIVE".equals(agent.status())
            || !"ACTIVE".equals(employee.status())) {
            throw ApiException.unauthenticated();
        }
        TeamDTO agentTeam = identityMapper.selectTeamById(agent.ownerTeamId());
        TeamDTO employeeTeam = identityMapper.selectTeamById(employee.primaryTeamId());
        if (agentTeam == null || employeeTeam == null || !"ACTIVE".equals(agentTeam.status())
            || !"ACTIVE".equals(employeeTeam.status())) {
            throw ApiException.unauthenticated();
        }

        // 3、只接受数据库中当前 GRANTED 关系，同团队或管理角色都不能代替显式授权。
        AgentEmployeePermissionDTO permission = mapper.selectPermission(Map.of("agentId", agent.id(),
            "employeeId", employee.id()));
        if (permission == null || !"GRANTED".equals(permission.status())) {
            throw ApiException.forbidden();
        }
        return new RuntimeIdentityDTO(agent.id(), agent.applicationCode(), employee.id(), agentKey.id(),
            credential.id(),
            permission.version(), now);
    }
}
