package com.felix.aigatecore.identity;

import com.aigate.contracts.ApiContractConstants;
import com.aigate.contracts.dto.AuditMetadataDTO;
import com.aigate.contracts.dto.BootstrapInitializedDTO;
import com.aigate.contracts.dto.EmployeeDTO;
import com.aigate.contracts.dto.IssuedManagementCredentialDTO;
import com.aigate.contracts.dto.ManagementCredentialMetadataDTO;
import com.aigate.contracts.dto.ManagementKeyRecoveredDTO;
import com.aigate.contracts.dto.ManagementProfileDTO;
import com.aigate.contracts.dto.PageDTO;
import com.aigate.contracts.dto.RoleAssignmentDTO;
import com.aigate.contracts.dto.TeamDTO;
import com.felix.aigatecore.security.CredentialCodec;
import com.felix.aigatecore.security.SecuritySettings;
import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * P1 身份领域服务，负责组织、角色、管理凭证和审计的事务边界。所有写操作先锁定单例行，再复查当前授权，避免并发初始化、撤权竞态及最后管理员被移除。
 *
 * @author AIGate
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class IdentityService {

    private final IdentityMapper identityMapper;

    private final SecuritySettings securitySettings;

    private final CredentialCodec credentialCodec;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * 当前事务中重新验证的管理身份快照，不接受客户端声明的员工、团队或角色。
     *
     * @param employee 员工安全视图
     * @param admin 当前测试首次初始化获得的管理员身份
     * @param teams 当前事务计算的可见团队主键
     * @param roles 当前未撤销角色视图
     * @author AIGate
     */
    private record Actor(EmployeeDTO employee, boolean admin, List<Long> teams, List<RoleAssignmentDTO> roles) {
    }

    /**
     * 创建IdentityService，显式注入当前组件依赖或不可变状态。
     *
     * @param identityMapper 身份模块数据库访问入口
     * @param securitySettings 安全配置与凭证轮换策略
     * @param credentialCodec 管理凭证随机数与摘要工具
     */
    public IdentityService(IdentityMapper identityMapper, SecuritySettings securitySettings,
        CredentialCodec credentialCodec) {
        this.identityMapper = identityMapper;
        this.securitySettings = securitySettings;
        this.credentialCodec = credentialCodec;
    }

    // 所有管理写操作先锁定已有初始化单例，再复查授权，统一锁顺序并防止撤权竞态。
    /**
     * 在当前事务内锁定身份单例行，建立所有 P1 管理写操作一致的锁顺序。
     *
     * @return 按固定字段构造的内部数据参数或查询结果
     */
    private Map<String, Object> lockIdentityWriteState() {
        Map<String, Object> state = identityMapper.lockBootstrapControl();
        if (state == null || state.isEmpty()) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Identity state is unavailable");
        }
        return state;
    }

    /**
     * 在当前事务中重新读取管理凭证、员工、团队和角色，避免沿用过滤器中的陈旧授权结论。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @return 当前事务中重新认证并验证权限的身份快照
     */
    private Actor authenticateActor(RequestContext requestContext) {
        String header = requestContext.authorization();
        if (header == null || !header.matches("Bearer mgt_[A-Za-z0-9_-]{43}")) {
            throw ApiException.unauthenticated();
        }
        String token = header.substring(7);
        ManagementCredentialDO credential = null;
        for (var pepper : securitySettings.acceptedPeppers()) {
            credential = identityMapper.selectCredentialByDigest(credentialCodec.digest(token, pepper), pepper.id());
            if (credential != null) {
                break;
            }
        }
        if (credential == null || credential.revokedAt() != null || !Instant.now().isBefore(credential.expiresAt())) {
            throw ApiException.unauthenticated();
        }
        EmployeeDTO employee = identityMapper.selectEmployeeById(credential.employeeId());
        if (employee == null) {
            throw ApiException.unauthenticated();
        }
        TeamDTO team = identityMapper.selectTeamById(employee.primaryTeamId());
        if (!employee.status().equals("ACTIVE") || team == null || !team.status().equals("ACTIVE")) {
            throw ApiException.forbidden();
        }
        List<RoleAssignmentDTO> roles = identityMapper.listRoleAssignments(employee.id());
        boolean admin = roles.stream().anyMatch(role -> role.roleCode().equals("PLATFORM_ADMIN")
            && role.scopeType().equals("GLOBAL") && role.scopeId() == 0);
        return new Actor(employee, admin, identityMapper.listVisibleTeamIds(employee.id()), roles);
    }

    /**
     * 验证当前身份具备有效的全局平台管理员角色。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @return 当前事务中重新认证并验证权限的身份快照
     */
    private Actor requireAdministrator(RequestContext requestContext) {
        Actor actor = authenticateActor(requestContext);
        if (!actor.admin()) {
            throw ApiException.forbidden();
        }
        return actor;
    }

    /**
     * 读取必需的团队记录，不存在时转换为业务 404。
     *
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @return 当前可见团队的安全视图
     */
    private TeamDTO getRequiredTeam(long id) {
        TeamDTO value = identityMapper.selectTeamById(id);
        if (value == null) {
            throw ApiException.missing();
        }
        return value;
    }

    /**
     * 读取必需的员工记录，不存在时转换为业务 404。
     *
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @return 当前可见员工的安全视图
     */
    private EmployeeDTO getRequiredEmployee(long id) {
        EmployeeDTO value = identityMapper.selectEmployeeById(id);
        if (value == null) {
            throw ApiException.missing();
        }
        return value;
    }

    /**
     * 确保归属团队当前处于 ACTIVE 状态。
     *
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     */
    private void requireActiveTeam(long id) {
        if (!getRequiredTeam(id).status().equals("ACTIVE")) {
            throw ApiException.conflict("INVALID_STATE");
        }
    }

    /**
     * 读取数据库生成的主键，并拒绝超出应用 Long 正整数范围的结果。
     *
     * @param values 固定白名单字段组成的 SQL 写入参数，不得包含明文密钥
     * @return 按当前合同校验或计算后的数值
     */
    private long getGeneratedId(Map<String, Object> values) {
        Object id = values.get("id");
        if (!(id instanceof Number number) || number.longValue() <= 0) {
            throw new IllegalStateException("Generated id is outside the supported range");
        }
        return number.longValue();
    }

    /**
     * 在当前业务事务中追加审计，只记录被修改的字段名，不记录密钥或字段值。
     *
     * @param actorType 受信操作者类型
     * @param actorId 签发者或审计操作者主键；BOOTSTRAP 固定为 0
     * @param action 固定业务审计动作
     * @param target 固定审计目标类型
     * @param targetId 审计目标主键
     * @param fields 当前操作的字段名白名单或安全审计字段名列表
     * @param traceId 已规范化的诊断请求 ID
     */
    private void appendAuditEvent(String actorType, long actorId, String action, String target, long targetId,
        List<String> fields, String traceId) {
        identityMapper.insertAuditEvent(
            new HashMap<>(Map.of("actorType", actorType, "actorId", actorId, "action", action, "targetType", target,
                "targetId", targetId, "changedFields", jsonMapper.writeValueAsString(fields), "traceId", traceId)));
    }

    /**
     * 在当前业务事务中追加审计，只记录被修改的字段名，不记录密钥或字段值。
     *
     * @param actor 当前事务中验证的管理身份
     * @param action 固定业务审计动作
     * @param target 固定审计目标类型
     * @param targetId 审计目标主键
     * @param fields 当前操作的字段名白名单或安全审计字段名列表
     * @param requestContext 已通过服务身份校验的管理请求上下文
     */
    private void appendAuditEvent(Actor actor, String action, String target, long targetId, List<String> fields,
        RequestContext requestContext) {
        appendAuditEvent("EMPLOYEE", actor.employee().id(), action, target, targetId, fields,
            requestContext.requestId());
    }

    /**
     * 校验角色与作用域组合，并构造固定字段的数据访问参数。
     *
     * @param employeeId 目标员工主键
     * @param role 目标角色编码
     * @param scope 目标作用域类型
     * @param scopeId 授权范围主键；GLOBAL 固定为 0
     * @return 按固定字段构造的内部数据参数或查询结果
     */
    private Map<String, Object> createRoleParameters(long employeeId, String role, String scope, long scopeId) {
        if (!Set.of("PLATFORM_ADMIN", "TEAM_MANAGER", "FINOPS_READER").contains(role)
            || !(scope.equals("GLOBAL") && scopeId == 0 && !role.equals("TEAM_MANAGER")
                || scope.equals("TEAM") && scopeId > 0 && !role.equals("PLATFORM_ADMIN"))) {
            throw ApiException.invalid();
        }
        return new HashMap<>(
            Map.of("employeeId", employeeId, "roleCode", role, "scopeType", scope, "scopeId", scopeId));
    }

    /**
     * 根据员工、归属团队和当前角色判断管理员是否实际有效。
     *
     * @param employee 员工安全视图
     * @return 满足方法所述条件时为 true，否则为 false
     */
    private boolean isEffectiveGlobalAdministrator(EmployeeDTO employee) {
        return employee.status().equals("ACTIVE") && getRequiredTeam(employee.primaryTeamId()).status().equals("ACTIVE")
            && identityMapper.listRoleAssignments(employee.id()).stream()
                .anyMatch(role -> role.roleCode().equals("PLATFORM_ADMIN") && role.scopeType().equals("GLOBAL")
                    && role.scopeId() == 0);
    }

    /**
     * 在持有身份写锁时保护最后一名有效全局管理员，防止权限永久丢失。
     *
     * @param employee 员工安全视图
     */
    private void ensureAdministratorRemains(EmployeeDTO employee) {
        if (isEffectiveGlobalAdministrator(employee) && identityMapper.countActiveGlobalAdministrators() <= 1) {
            throw ApiException.conflict("LAST_ADMIN_REQUIRED");
        }
    }

    /**
     * 使用服务端当前时间校验管理凭证必须在一小时至九十天后到期。
     *
     * @param expiry 服务器校验的凭证到期时间
     */
    private void validateCredentialExpiry(Instant expiry) {
        Instant now = Instant.now();
        if (expiry.isBefore(now.plus(Duration.ofHours(1))) || expiry.isAfter(now.plus(Duration.ofDays(90)))) {
            throw ApiException.invalid();
        }
    }

    /**
     * 按签发者和幂等键保证只创建一次凭证；已提交的密钥永远不再次返回明文。
     *
     * @param owner 管理凭证所属员工主键
     * @param actorType 受信操作者类型
     * @param actorId 签发者或审计操作者主键；BOOTSTRAP 固定为 0
     * @param key 字段名或当前操作的受控幂等标识；凭证明文只允许在首次签发响应使用
     * @param expiry 服务器校验的凭证到期时间
     * @return 仅本次首次响应可展示的凭证及元数据
     */
    private IssuedManagementCredentialDTO issueCredentialOnce(long owner, String actorType, long actorId, String key,
        Instant expiry) {
        ManagementCredentialDO previous = identityMapper.selectIssuedCredential(actorType, actorId, key);
        if (previous != null) {
            if (previous.employeeId() != owner || !previous.expiresAt().equals(expiry)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT");
            }
            throw ApiException.conflict(actorType.equals("BOOTSTRAP")
                ? "IDEMPOTENCY_REPLAY_NOT_REVEALABLE"
                : "KEY_ALREADY_ISSUED_NOT_REVEALABLE");
        }
        validateCredentialExpiry(expiry);
        String token = credentialCodec.newManagementKey();
        String prefix = token.substring(0, ApiContractConstants.MANAGEMENT_KEY_PREFIX_LENGTH);
        var pepper = securitySettings.currentPepper();
        Map<String, Object> values = new HashMap<>(Map.of("employeeId", owner, "keyPrefix", prefix, "digest",
            credentialCodec.digest(token, pepper), "pepperKeyId", pepper.id(), "expiresAt", expiry, "actorType",
            actorType, "actorId", actorId, "key", key));
        identityMapper.insertCredential(values);
        return new IssuedManagementCredentialDTO(getGeneratedId(values), token, prefix, expiry);
    }

    /**
     * 在单个事务中首次创建团队、管理员、角色、凭证和审计，并更新初始化单例。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param body 当前接口字段白名单下的已校验正文
     * @param operationKey 客户端业务幂等键，不作为授权凭证
     * @return 首次创建的团队、员工及一次性管理凭证
     */
    public BootstrapInitializedDTO initialize(RequestContext requestContext, ValidatedBody body, String operationKey) {
        securitySettings.requireBootstrap("INIT", requestContext.authorization(), requestContext.clientAddress());
        String key = ValidatedBody.idempotency(operationKey);
        Map<String, Object> state = lockIdentityWriteState();
        // 优先遵守初始化接口合同：一旦初始化成功，任何后续 INIT 都不得再次签发凭证。
        if (state.get("initializedAt") != null) {
            throw ApiException.conflict("BOOTSTRAP_ALREADY_INITIALIZED");
        }
        Instant expiry = body.instant("managementKeyExpiresAt");
        validateCredentialExpiry(expiry);
        Map<String, Object> teamValues = new HashMap<>(Map.of("name", body.text("teamName", 80)));
        identityMapper.insertTeam(teamValues);
        long teamId = getGeneratedId(teamValues);
        Map<String, Object> employeeValues = new HashMap<>(Map.of("employeeCode", body.code("employeeCode"),
            "displayName", body.text("displayName", 100), "primaryTeamId", teamId));
        identityMapper.insertEmployee(employeeValues);
        long employeeId = getGeneratedId(employeeValues);
        identityMapper.grantRole(createRoleParameters(employeeId, "PLATFORM_ADMIN", "GLOBAL", 0));
        IssuedManagementCredentialDTO credential = issueCredentialOnce(employeeId, "BOOTSTRAP", 0, key, expiry);
        if (identityMapper.initializeBootstrapControl(employeeId) != 1) {
            throw ApiException.conflict("BOOTSTRAP_ALREADY_INITIALIZED");
        }
        appendAuditEvent("BOOTSTRAP", 0, "BOOTSTRAP_INITIALIZE", "MANAGEMENT_CREDENTIAL", credential.credentialId(),
            List.of("teamName", "employeeCode", "displayName", "managementKeyExpiresAt"), requestContext.requestId());
        state = identityMapper.lockBootstrapControl();
        return new BootstrapInitializedDTO(getRequiredTeam(teamId), getRequiredEmployee(employeeId), credential,
            convertDatabaseTimestamp(state.get("initializedAt")));
    }

    /**
     * 通过受控运维模式为已有有效管理员恢复凭证，不增加任何角色。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param body 当前接口字段白名单下的已校验正文
     * @param operationKey 客户端业务幂等键，不作为授权凭证
     * @return 恢复目标及一次性管理凭证
     */
    public ManagementKeyRecoveredDTO recover(RequestContext requestContext, ValidatedBody body, String operationKey) {
        securitySettings.requireBootstrap("RECOVERY", requestContext.authorization(), requestContext.clientAddress());
        if (!body.text("reasonCode", 32).equals("ADMIN_KEY_LOST")) {
            throw ApiException.invalid();
        }
        String key = ValidatedBody.idempotency(operationKey);
        if (lockIdentityWriteState().get("initializedAt") == null) {
            throw ApiException.conflict("BOOTSTRAP_NOT_INITIALIZED");
        }
        EmployeeDTO target = identityMapper.selectEmployeeByCode(body.code("targetEmployeeCode"));
        if (target == null || !isEffectiveGlobalAdministrator(target)) {
            throw ApiException.forbidden();
        }
        IssuedManagementCredentialDTO credential = issueCredentialOnce(target.id(), "BOOTSTRAP", 0, key,
            body.instant("managementKeyExpiresAt"));
        appendAuditEvent("BOOTSTRAP", 0, "BOOTSTRAP_RECOVER", "MANAGEMENT_CREDENTIAL", credential.credentialId(),
            List.of("managementKeyExpiresAt", "reasonCode"), requestContext.requestId());
        return new ManagementKeyRecoveredDTO(target.id(), credential,
            identityMapper.selectCredentialById(credential.credentialId()).createdAt());
    }

    /**
     * 创建团队，并在同一事务中记录审计。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param body 当前接口字段白名单下的已校验正文
     * @return 当前可见团队的安全视图
     */
    public TeamDTO createTeam(RequestContext requestContext, ValidatedBody body) {
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);
        Map<String, Object> values = new HashMap<>(Map.of("name", body.text("name", 80)));
        identityMapper.insertTeam(values);
        long id = getGeneratedId(values);
        appendAuditEvent(actor, "TEAM_CREATED", "TEAM", id, List.of("name"), requestContext);
        return getRequiredTeam(id);
    }

    /**
     * 查询当前身份有权查看的单个团队。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @return 当前可见团队的安全视图
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public TeamDTO getTeam(RequestContext requestContext, long id) {
        Actor actor = authenticateActor(requestContext);
        if (!actor.admin() && !actor.teams().contains(id)) {
            throw ApiException.forbidden();
        }
        return getRequiredTeam(id);
    }

    /**
     * 使用 expectedVersion 更新团队；仍有 ACTIVE 员工时禁止禁用。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @param body 当前接口字段白名单下的已校验正文
     * @return 当前可见团队的安全视图
     */
    public TeamDTO updateTeam(RequestContext requestContext, long id, ValidatedBody body) {
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);
        TeamDTO before = getRequiredTeam(id);
        long expected = body.number("expectedVersion", true);
        if (body.has("name") == false && body.has("status") == false) {
            throw ApiException.invalid();
        }
        String name = body.has("name") ? body.text("name", 80) : before.name();
        String status = body.has("status") ? body.status() : before.status();
        if (before.version() != expected) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        if (status.equals("DISABLED") && identityMapper.countActiveEmployeesByTeamId(id) > 0) {
            throw ApiException.conflict("ACTIVE_EMPLOYEES_EXIST");
        }
        if (identityMapper
            .updateTeam(Map.of("id", id, "name", name, "status", status, "expectedVersion", expected)) != 1) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        List<String> fields = new ArrayList<>();
        if (body.has("name")) {
            fields.add("name");
        }
        if (body.has("status")) {
            fields.add("status");
        }
        appendAuditEvent(actor, "TEAM_UPDATED", "TEAM", id, fields, requestContext);
        return getRequiredTeam(id);
    }

    /**
     * 在 ACTIVE 团队下创建员工，并追加同事务审计。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param body 当前接口字段白名单下的已校验正文
     * @return 当前可见员工的安全视图
     */
    public EmployeeDTO createEmployee(RequestContext requestContext, ValidatedBody body) {
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);
        long teamId = body.number("primaryTeamId", false);
        requireActiveTeam(teamId);
        Map<String, Object> values = new HashMap<>(Map.of("employeeCode", body.code("employeeCode"), "displayName",
            body.text("displayName", 100), "primaryTeamId", teamId));
        identityMapper.insertEmployee(values);
        long id = getGeneratedId(values);
        appendAuditEvent(actor, "EMPLOYEE_CREATED", "EMPLOYEE", id,
            List.of("employeeCode", "displayName", "primaryTeamId"), requestContext);
        return getRequiredEmployee(id);
    }

    /**
     * 查询员工，并校验其归属团队是否在当前身份的可见范围内。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @return 当前可见员工的安全视图
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public EmployeeDTO getEmployee(RequestContext requestContext, long id) {
        Actor actor = authenticateActor(requestContext);
        EmployeeDTO target = getRequiredEmployee(id);
        if (!actor.admin() && !actor.teams().contains(target.primaryTeamId())) {
            throw ApiException.forbidden();
        }
        return target;
    }

    /**
     * 按期望版本更新员工资料及状态，禁用前保护最后有效管理员。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @param body 当前接口字段白名单下的已校验正文
     * @return 当前可见员工的安全视图
     */
    public EmployeeDTO updateEmployee(RequestContext requestContext, long id, ValidatedBody body) {
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);
        EmployeeDTO before = getRequiredEmployee(id);
        long expected = body.number("expectedVersion", true);
        if (!body.has("displayName") && !body.has("primaryTeamId") && !body.has("status")) {
            throw ApiException.invalid();
        }
        if (before.version() != expected) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        String name = body.has("displayName") ? body.text("displayName", 100) : before.displayName();
        String status = body.has("status") ? body.status() : before.status();
        long teamId = body.has("primaryTeamId") ? body.number("primaryTeamId", false) : before.primaryTeamId();
        requireActiveTeam(teamId);
        if (status.equals("DISABLED")) {
            ensureAdministratorRemains(before);
        }
        if (identityMapper.updateEmployee(Map.of("id", id, "displayName", name, "primaryTeamId", teamId, "status",
            status, "expectedVersion", expected)) != 1) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        List<String> fields = new ArrayList<>();
        for (String field : List.of("displayName", "primaryTeamId", "status")) {
            if (body.has(field)) {
                fields.add(field);
            }
        }
        appendAuditEvent(actor, "EMPLOYEE_UPDATED", "EMPLOYEE", id, fields, requestContext);
        return getRequiredEmployee(id);
    }

    /**
     * 幂等授予角色；只有真实新增或恢复关系时才追加审计。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param employeeId 目标员工主键
     * @param role 目标角色编码
     * @param scope 目标作用域类型
     * @param scopeId 授权范围主键；GLOBAL 固定为 0
     * @return 满足方法所述条件时为 true，否则为 false
     */
    public boolean grantRole(RequestContext requestContext, long employeeId, String role, String scope, long scopeId) {
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);
        getRequiredEmployee(employeeId);
        Map<String, Object> values = createRoleParameters(employeeId, role, scope, scopeId);
        if (scope.equals("TEAM")) {
            requireActiveTeam(scopeId);
        }
        if (identityMapper.countActiveRoleAssignments(values) > 0) {
            return false;
        }
        identityMapper.grantRole(values);
        appendAuditEvent(actor, "ROLE_GRANTED", "EMPLOYEE", employeeId, List.of("roleCode", "scopeType", "scopeId"),
            requestContext);
        return true;
    }

    /**
     * 幂等撤销角色；禁止撤销最后有效全局管理员。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param employeeId 目标员工主键
     * @param role 目标角色编码
     * @param scope 目标作用域类型
     * @param scopeId 授权范围主键；GLOBAL 固定为 0
     */
    public void revokeRole(RequestContext requestContext, long employeeId, String role, String scope, long scopeId) {
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);
        EmployeeDTO target = getRequiredEmployee(employeeId);
        Map<String, Object> values = createRoleParameters(employeeId, role, scope, scopeId);
        if (identityMapper.countActiveRoleAssignments(values) == 0) {
            return;
        }
        if (role.equals("PLATFORM_ADMIN")) {
            ensureAdministratorRemains(target);
        }
        identityMapper.revokeRole(values);
        appendAuditEvent(actor, "ROLE_REVOKED", "EMPLOYEE", employeeId, List.of("roleCode", "scopeType", "scopeId"),
            requestContext);
    }

    /**
     * 验证管理员及目标员工当前状态后签发一次性展示的管理密钥。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param employeeId 目标员工主键
     * @param body 当前接口字段白名单下的已校验正文
     * @param operationKey 客户端业务幂等键，不作为授权凭证
     * @return 仅本次首次响应可展示的凭证及元数据
     */
    public IssuedManagementCredentialDTO issueCredential(RequestContext requestContext, long employeeId,
        ValidatedBody body, String operationKey) {
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);
        EmployeeDTO target = getRequiredEmployee(employeeId);
        if (!target.status().equals("ACTIVE")) {
            throw ApiException.conflict("INVALID_STATE");
        }
        requireActiveTeam(target.primaryTeamId());
        IssuedManagementCredentialDTO issued = issueCredentialOnce(employeeId, "EMPLOYEE", actor.employee().id(),
            ValidatedBody.idempotency(operationKey), body.instant("expiresAt"));
        appendAuditEvent(actor, "MANAGEMENT_CREDENTIAL_ISSUED", "MANAGEMENT_CREDENTIAL", issued.credentialId(),
            List.of("expiresAt"), requestContext);
        return issued;
    }

    /**
     * 核对凭证归属后吊销；重复吊销不重复追加成功审计。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param employeeId 目标员工主键
     * @param credentialId 目标管理凭证主键
     */
    public void revokeCredential(RequestContext requestContext, long employeeId, long credentialId) {
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);
        getRequiredEmployee(employeeId);
        ManagementCredentialDO credential = identityMapper.selectCredentialById(credentialId);
        if (credential == null || credential.employeeId() != employeeId) {
            throw ApiException.missing();
        }
        if (identityMapper.revokeCredential(credentialId) == 1) {
            appendAuditEvent(actor, "MANAGEMENT_CREDENTIAL_REVOKED", "MANAGEMENT_CREDENTIAL", credentialId,
                List.of("revokedAt"), requestContext);
        }
    }

    /**
     * 读取当前有效员工及其当前角色的安全视图。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @return 当前有效员工及其角色视图
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ManagementProfileDTO getCurrentProfile(RequestContext requestContext) {
        Actor actor = authenticateActor(requestContext);
        return new ManagementProfileDTO(actor.employee(), actor.roles());
    }

    /**
     * 校验查询字段白名单与分页边界，使用 Long 计算偏移量，避免整数乘法溢出。
     *
     * @param input 客户端输入的候选 JSON 或查询参数
     * @param allowed 当前操作允许出现的字段名白名单
     * @return 按固定字段构造的内部数据参数或查询结果
     */
    private Map<String, Object> createPageQuery(Map<String, String> input, Set<String> allowed) {
        if (!allowed.containsAll(input.keySet())) {
            throw ApiException.invalid();
        }
        int page = (int) parseBoundedPositiveNumber(input.getOrDefault("page", "1"), Integer.MAX_VALUE);
        int size = (int) parseBoundedPositiveNumber(
            input.getOrDefault("size", Integer.toString(ApiContractConstants.DEFAULT_PAGE_SIZE)),
            ApiContractConstants.MAX_PAGE_SIZE);
        Map<String, Object> query = new HashMap<>();
        query.put("page", page);
        query.put("size", size);
        query.put("offset", ((long) page - 1) * size);
        return query;
    }

    /**
     * 校验受上界约束的正整数查询参数。
     *
     * @param value 待校验或转换的受控输入
     * @param max 允许的最大 Unicode 字符数或数值上界
     * @return 按当前合同校验或计算后的数值
     */
    private long parseBoundedPositiveNumber(String value, long max) {
        long number = ValidatedBody.pathId(value, false);
        if (number > max) {
            throw ApiException.invalid();
        }
        return number;
    }

    /**
     * 仅接受现有合同允许的状态过滤条件。
     *
     * @param input 客户端输入的候选 JSON 或查询参数
     * @param query 已应用白名单和权限范围的查询参数
     */
    private void applyStatusFilter(Map<String, String> input, Map<String, Object> query) {
        if (input.containsKey("status")) {
            if (!Set.of("ACTIVE", "DISABLED").contains(input.get("status"))) {
                throw ApiException.invalid();
            }
            query.put("status", input.get("status"));
        }
    }

    /**
     * 将当前可见团队写入 SQL 条件，确保过滤发生在 COUNT 和分页之前。
     *
     * @param actor 当前事务中验证的管理身份
     * @param query 已应用白名单和权限范围的查询参数
     */
    private void applyPermissionFilter(Actor actor, Map<String, Object> query) {
        if (!actor.admin() && actor.teams().isEmpty()) {
            throw ApiException.forbidden();
        }
        query.put("admin", actor.admin());
        query.put("visibleTeams", actor.teams());
    }

    /**
     * 按当前权限范围查询团队及其过滤后的总数。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param input 客户端输入的候选 JSON 或查询参数
     * @return 当前查询范围内的有序结果
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<TeamDTO> listTeams(RequestContext requestContext, Map<String, String> input) {
        Actor actor = authenticateActor(requestContext);
        Map<String, Object> query = createPageQuery(input, Set.of("page", "size", "status"));
        applyStatusFilter(input, query);
        applyPermissionFilter(actor, query);
        return new PageDTO<>(identityMapper.listTeams(query), (int) query.get("page"), (int) query.get("size"),
            identityMapper.countTeams(query));
    }

    /**
     * 按当前权限范围查询员工及其过滤后的总数。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param input 客户端输入的候选 JSON 或查询参数
     * @return 当前查询范围内的有序结果
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<EmployeeDTO> listEmployees(RequestContext requestContext, Map<String, String> input) {
        Actor actor = authenticateActor(requestContext);
        Map<String, Object> query = createPageQuery(input, Set.of("page", "size", "status", "primaryTeamId"));
        applyStatusFilter(input, query);
        applyPermissionFilter(actor, query);
        if (input.containsKey("primaryTeamId")) {
            query.put("primaryTeamId", ValidatedBody.pathId(input.get("primaryTeamId"), false));
        }
        return new PageDTO<>(identityMapper.listEmployees(query), (int) query.get("page"), (int) query.get("size"),
            identityMapper.countEmployees(query));
    }

    /**
     * 仅向管理员分页返回凭证元数据，不包含明文或摘要。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param employeeId 目标员工主键
     * @param input 客户端输入的候选 JSON 或查询参数
     * @return 当前查询范围内的有序结果
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<ManagementCredentialMetadataDTO> listCredentialMetadata(RequestContext requestContext,
        long employeeId, Map<String, String> input) {
        requireAdministrator(requestContext);
        getRequiredEmployee(employeeId);
        Map<String, Object> query = createPageQuery(input, Set.of("page", "size"));
        query.put("employeeId", employeeId);
        return new PageDTO<>(identityMapper.listCredentialMetadata(query), (int) query.get("page"),
            (int) query.get("size"), identityMapper.countCredentialsByEmployeeId(employeeId));
    }

    /**
     * 按受限查询条件返回审计元数据，时间区间最多九十天。
     *
     * @param requestContext 已通过服务身份校验的管理请求上下文
     * @param input 客户端输入的候选 JSON 或查询参数
     * @return 当前查询范围内的有序结果
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<AuditMetadataDTO> listAuditEvents(RequestContext requestContext, Map<String, String> input) {
        requireAdministrator(requestContext);
        Map<String, Object> query = createPageQuery(input,
            Set.of("page", "size", "actorType", "actorId", "targetType", "targetId", "from", "to"));
        if (input.containsKey("actorType")) {
            if (!Set.of("BOOTSTRAP", "EMPLOYEE").contains(input.get("actorType"))) {
                throw ApiException.invalid();
            }
            query.put("actorType", input.get("actorType"));
        }
        if (input.containsKey("targetType")) {
            String type = input.get("targetType");
            if (!type.matches("[A-Z_]{1,32}")) {
                throw ApiException.invalid();
            }
            query.put("targetType", type);
        }
        for (String field : List.of("actorId", "targetId")) {
            if (input.containsKey(field)) {
                query.put(field, ValidatedBody.pathId(input.get(field), true));
            }
        }
        Instant now = Instant.now();
        Instant from = input.containsKey("from")
            ? ValidatedBody.instantValue(input.get("from"))
            : now.minus(Duration.ofDays(90));
        Instant to = input.containsKey("to") ? ValidatedBody.instantValue(input.get("to")) : now;
        if (to.isBefore(from) || Duration.between(from, to).compareTo(Duration.ofDays(90)) > 0) {
            throw ApiException.invalid();
        }
        query.put("from", from);
        query.put("to", to);
        List<AuditMetadataDTO> result = identityMapper.listAuditEvents(query).stream()
            .map(row -> new AuditMetadataDTO(((Number) row.get("id")).longValue(), (String) row.get("actor_type"),
                ((Number) row.get("actor_id")).longValue(), (String) row.get("action"), (String) row.get("target_type"),
                ((Number) row.get("target_id")).longValue(),
                jsonMapper.readValue((String) row.get("changed_fields_json"),
                    jsonMapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                (String) row.get("trace_id"), convertDatabaseTimestamp(row.get("occurred_at"))))
            .toList();
        return new PageDTO<>(result, (int) query.get("page"), (int) query.get("size"),
            identityMapper.countAuditEvents(query));
    }

    /**
     * 将驱动返回的时间类型统一转换为 UTC Instant。
     *
     * @param value 待校验或转换的受控输入
     * @return 符合 UTC 与数据库精度约束的时间
     */
    private Instant convertDatabaseTimestamp(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof LocalDateTime local) {
            return local.toInstant(ZoneOffset.UTC);
        }
        throw new IllegalStateException("Unsupported database timestamp");
    }
}
