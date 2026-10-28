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
 * 身份管理服务，处理团队、员工、角色、管理凭证和审计。
 *
 * <p>
 * 普通写操作先获取控制行锁，再检查调用者权限。 初始化和恢复先检查运维身份，再持锁检查初始化状态和目标管理员。 业务数据和审计一起提交，任一步失败都回滚。
 * </p>
 *
 * @author AIGate
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class IdentityService {

    private final IdentityMapper identityMapper;

    private final SecuritySettings securitySettings;

    private final CredentialCodec credentialCodec;

    // 用于审计字段名数组的 JSON 编解码，不处理密钥明文。
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * 从数据库查询得到的当前员工、管理员标记、可管理团队和角色。
     *
     * @param employee 员工响应数据
     * @param admin 当前事务验证的身份是否为有效全局管理员
     * @param teams 当前事务计算的可见团队主键
     * @param roles 当前未撤销的角色列表
     * @author AIGate
     */
    private record Actor(EmployeeDTO employee, boolean admin, List<Long> teams, List<RoleAssignmentDTO> roles) {
    }

    /**
     * 通过构造器注入数据访问、安全配置和密钥工具。
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

    /**
     * 锁定初始化控制行，协调管理接口的并发写入。
     *
     * @return 当前事务已锁定的初始化单例状态；未初始化时完成时间为 null
     */
    private BootstrapControlDO lockIdentityWriteState() {
        // 1、锁定初始化控制行，后续写操作在同一事务内完成。
        BootstrapControlDO state = identityMapper.lockBootstrapControl();

        // 2、控制行不存在时返回 503，不自动创建新行。
        if (state == null) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Identity state is unavailable");
        }

        return state;
    }

    /**
     * 根据管理凭证，查询并检查当前员工、团队和角色。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @return 从数据库验证得到的当前员工及权限信息
     */
    private Actor authenticateActor(RequestContext requestContext) {
        // 1、读取管理凭证，只接受 Bearer mgt_ 格式。
        String header = requestContext.authorization();
        if (header == null || !header.matches("Bearer mgt_[A-Za-z0-9_-]{43}")) {
            throw ApiException.unauthenticated();
        }

        String token = header.substring(7);
        ManagementCredentialDO credential = null;

        // 2、使用当前及轮换期内的 Pepper 计算摘要，查找凭证。
        for (var pepper : securitySettings.acceptedPeppers()) {
            credential = identityMapper.selectCredentialByDigest(credentialCodec.digest(token, pepper), pepper.id());
            if (credential != null) {
                break;
            }
        }

        // 3、拒绝不存在、已吊销或已到期的凭证。
        if (credential == null || credential.revokedAt() != null || !Instant.now().isBefore(credential.expiresAt())) {
            throw ApiException.unauthenticated();
        }

        // 4、查询员工和归属团队，两者都必须为 ACTIVE。
        EmployeeDTO employee = identityMapper.selectEmployeeById(credential.employeeId());
        if (employee == null) {
            throw ApiException.unauthenticated();
        }

        TeamDTO team = identityMapper.selectTeamById(employee.primaryTeamId());
        if (!employee.status().equals("ACTIVE") || team == null || !team.status().equals("ACTIVE")) {
            throw ApiException.forbidden();
        }

        // 5、读取当前角色，判断是否为全局管理员，并查询可管理的团队。
        List<RoleAssignmentDTO> roles = identityMapper.listRoleAssignments(employee.id());
        boolean admin = roles.stream().anyMatch(role -> role.roleCode().equals("PLATFORM_ADMIN")
            && role.scopeType().equals("GLOBAL") && role.scopeId() == 0);

        return new Actor(employee, admin, identityMapper.listVisibleTeamIds(employee.id()), roles);
    }

    /**
     * 验证当前身份具备有效的全局平台管理员角色。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @return 从数据库验证得到的当前员工及权限信息
     */
    private Actor requireAdministrator(RequestContext requestContext) {
        Actor actor = authenticateActor(requestContext);
        if (!actor.admin()) {
            throw ApiException.forbidden();
        }

        return actor;
    }

    /**
     * 为其他管理模块检查当前全局管理员，复用管理凭证、员工和团队检查。
     *
     * @param requestContext 当前管理请求
     * @return 当前管理员员工主键
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public long requireGlobalAdministrator(RequestContext requestContext) {
        return requireAdministrator(requestContext).employee().id();
    }

    /**
     * 读取必需的团队记录，不存在时转换为业务 404。
     *
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @return 已存在的团队信息；访问权限由调用方检查
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
     * @return 已存在的员工信息；访问权限由调用方检查
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
     * 读取数据库回填的主键，并检查其转换后的 Long 值为正数。
     *
     * @param values SQL 写入参数，不能包含密钥明文
     * @return 数据库回填的正数主键
     */
    private long getGeneratedId(Map<String, Object> values) {
        // 读取 MyBatis 回填的主键，转换后的 Long 值必须为正数。
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
     * @param fields 允许的字段名或本次修改的字段名
     * @param traceId 已规范化的诊断请求 ID
     */
    private void appendAuditEvent(String actorType, long actorId, String action, String target, long targetId,
        List<String> fields, String traceId) {
        // 写入当前事务的审计，只记录修改字段名，不记录字段值和密钥。
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
     * @param fields 允许的字段名或本次修改的字段名
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
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
     * @return 供 MyBatis 使用的参数集合
     */
    private Map<String, Object> createRoleParameters(long employeeId, String role, String scope, long scopeId) {
        // 检查角色与作用域：平台管理员只能是 GLOBAL/0，团队管理员只能是 TEAM/正数。
        if (!Set.of("PLATFORM_ADMIN", "TEAM_MANAGER", "FINOPS_READER", "POLICY_EDITOR", "SECURITY_APPROVER")
            .contains(role)
            || !(scope.equals("GLOBAL") && scopeId == 0 && !role.equals("TEAM_MANAGER")
                || scope.equals("TEAM") && scopeId > 0 && Set.of("TEAM_MANAGER", "FINOPS_READER").contains(role))) {
            throw ApiException.invalid();
        }

        return new HashMap<>(
            Map.of("employeeId", employeeId, "roleCode", role, "scopeType", scope, "scopeId", scopeId));
    }

    /**
     * 根据员工、归属团队和当前角色判断管理员是否实际有效。
     *
     * @param employee 员工响应数据
     * @return 员工、归属团队和全局管理员角色都有效时为 true
     */
    private boolean isEffectiveGlobalAdministrator(EmployeeDTO employee) {
        // 检查员工、归属团队和全局管理员角色是否同时有效。
        return employee.status().equals("ACTIVE") && getRequiredTeam(employee.primaryTeamId()).status().equals("ACTIVE")
            && identityMapper.listRoleAssignments(employee.id()).stream()
                .anyMatch(role -> role.roleCode().equals("PLATFORM_ADMIN") && role.scopeType().equals("GLOBAL")
                    && role.scopeId() == 0);
    }

    /**
     * 禁止撤销或停用最后一名有效全局管理员，调用前必须持有控制行锁。
     *
     * @param employee 员工响应数据
     */
    private void ensureAdministratorRemains(EmployeeDTO employee) {
        // 持有写锁时统计有效管理员，禁止移除最后一名。
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
        // 使用服务端时间，要求到期时间在一小时至九十天之间。
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
     * @param key 当前签发者命名空间内的业务幂等键，与密钥明文无关
     * @param expiry 服务器校验的凭证到期时间
     * @return 仅本次首次响应可展示的凭证及元数据
     */
    private IssuedManagementCredentialDTO issueCredentialOnce(long owner, String actorType, long actorId, String key,
        Instant expiry) {
        // 1、按签发者和幂等键查询是否已经签发过凭证。
        ManagementCredentialDO previous = identityMapper.selectIssuedCredential(actorType, actorId, key);

        // 2、已经签发时检查员工和到期时间，同参数也不能再次返回密钥明文。
        if (previous != null) {
            // 目标员工或到期时间不同，返回幂等冲突。
            if (previous.employeeId() != owner || !previous.expiresAt().equals(expiry)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT");
            }

            // 参数相同也只返回已签发状态，不再次展示明文。
            throw ApiException.conflict(actorType.equals("BOOTSTRAP")
                ? "IDEMPOTENCY_REPLAY_NOT_REVEALABLE"
                : "KEY_ALREADY_ISSUED_NOT_REVEALABLE");
        }

        // 3、首次签发先检查到期时间。
        validateCredentialExpiry(expiry);

        // 4、生成密钥，使用当前 Pepper 计算摘要，并提取可公开的短前缀。
        String token = credentialCodec.newManagementKey();
        String prefix = token.substring(0, ApiContractConstants.MANAGEMENT_KEY_PREFIX_LENGTH);
        var pepper = securitySettings.currentPepper();
        Map<String, Object> values = new HashMap<>(Map.of("employeeId", owner, "keyPrefix", prefix, "digest",
            credentialCodec.digest(token, pepper), "pepperKeyId", pepper.id(), "expiresAt", expiry, "actorType",
            actorType, "actorId", actorId, "key", key));

        // 5、保存摘要和签发信息，完整密钥只放入本次返回结果。
        identityMapper.insertCredential(values);

        return new IssuedManagementCredentialDTO(getGeneratedId(values), token, prefix, expiry);
    }

    /**
     * 在单个事务中首次创建团队、管理员、角色、凭证和审计，并更新初始化单例。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param body 已校验的请求正文
     * @param operationKey 客户端业务幂等键，不作为授权凭证
     * @return 首次创建的团队、员工及一次性管理凭证
     */
    public BootstrapInitializedDTO initialize(RequestContext requestContext, ValidatedBody body, String operationKey) {
        // 1、检查 INIT 模式、Bootstrap 凭证和来源网段。
        securitySettings.requireBootstrap("INIT", requestContext.authorization(), requestContext.clientAddress());
        String key = ValidatedBody.idempotency(operationKey);

        // 2、锁定初始化控制行，已初始化时返回 409。
        BootstrapControlDO state = lockIdentityWriteState();

        if (state.initializedAt() != null) {
            throw ApiException.conflict("BOOTSTRAP_ALREADY_INITIALIZED");
        }

        // 3、检查管理员凭证的到期时间。
        Instant expiry = body.instant("managementKeyExpiresAt");
        validateCredentialExpiry(expiry);

        // 4、创建首个团队，获取数据库生成的团队 ID。
        Map<String, Object> teamValues = new HashMap<>(Map.of("name", body.text("teamName", 80)));
        identityMapper.insertTeam(teamValues);
        long teamId = getGeneratedId(teamValues);

        // 5、在该团队中创建首名管理员员工。
        Map<String, Object> employeeValues = new HashMap<>(Map.of("employeeCode", body.code("employeeCode"),
            "displayName", body.text("displayName", 100), "primaryTeamId", teamId));
        identityMapper.insertEmployee(employeeValues);
        long employeeId = getGeneratedId(employeeValues);

        // 6、授予 GLOBAL/0 平台管理员角色，并首次签发管理密钥。
        identityMapper.grantRole(createRoleParameters(employeeId, "PLATFORM_ADMIN", "GLOBAL", 0));

        IssuedManagementCredentialDTO credential = issueCredentialOnce(employeeId, "BOOTSTRAP", 0, key, expiry);

        // 7、写入初始化完成标记，更新不成功时回滚整个事务。
        if (identityMapper.initializeBootstrapControl(employeeId) != 1) {
            throw ApiException.conflict("BOOTSTRAP_ALREADY_INITIALIZED");
        }

        // 8、记录初始化审计；审计失败时，团队、员工和凭证也一起回滚。
        appendAuditEvent("BOOTSTRAP", 0, "BOOTSTRAP_INITIALIZE", "MANAGEMENT_CREDENTIAL", credential.credentialId(),
            List.of("teamName", "employeeCode", "displayName", "managementKeyExpiresAt"), requestContext.requestId());

        // 9、重读数据库保存的完成时间，组装首次初始化响应。
        state = identityMapper.lockBootstrapControl();

        return new BootstrapInitializedDTO(getRequiredTeam(teamId), getRequiredEmployee(employeeId), credential,
            state.initializedAt());
    }

    /**
     * 通过受控运维模式为已有有效管理员恢复凭证，不增加任何角色。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param body 已校验的请求正文
     * @param operationKey 客户端业务幂等键，不作为授权凭证
     * @return 恢复目标及一次性管理凭证
     */
    public ManagementKeyRecoveredDTO recover(RequestContext requestContext, ValidatedBody body, String operationKey) {
        // 1、检查 RECOVERY 模式、Bootstrap 凭证、来源和恢复原因。
        securitySettings.requireBootstrap("RECOVERY", requestContext.authorization(), requestContext.clientAddress());
        if (!body.text("reasonCode", 32).equals("ADMIN_KEY_LOST")) {
            throw ApiException.invalid();
        }

        // 2、检查幂等键并锁定控制行，系统必须已经初始化。
        String key = ValidatedBody.idempotency(operationKey);
        if (lockIdentityWriteState().initializedAt() == null) {
            throw ApiException.conflict("BOOTSTRAP_NOT_INITIALIZED");
        }

        // 3、按员工编码查找仍有效的全局管理员，不创建员工或补授角色。
        EmployeeDTO target = identityMapper.selectEmployeeByCode(body.code("targetEmployeeCode"));
        if (target == null || !isEffectiveGlobalAdministrator(target)) {
            throw ApiException.forbidden();
        }

        // 4、首次签发恢复密钥，并记录本次恢复审计。
        IssuedManagementCredentialDTO credential = issueCredentialOnce(target.id(), "BOOTSTRAP", 0, key,
            body.instant("managementKeyExpiresAt"));

        appendAuditEvent("BOOTSTRAP", 0, "BOOTSTRAP_RECOVER", "MANAGEMENT_CREDENTIAL", credential.credentialId(),
            List.of("managementKeyExpiresAt", "reasonCode"), requestContext.requestId());

        // 5、返回目标员工、新密钥及数据库记录的签发时间。
        return new ManagementKeyRecoveredDTO(target.id(), credential,
            identityMapper.selectCredentialById(credential.credentialId()).createdAt());
    }

    /**
     * 创建团队，并在同一事务中记录审计。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param body 已校验的请求正文
     * @return 当前调用者可查看的团队信息
     */
    public TeamDTO createTeam(RequestContext requestContext, ValidatedBody body) {
        // 1、先获取管理写锁，再检查当前调用者是否仍为全局管理员。
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);

        // 2、创建团队，获取数据库生成的团队 ID。
        Map<String, Object> values = new HashMap<>(Map.of("name", body.text("name", 80)));
        identityMapper.insertTeam(values);
        long id = getGeneratedId(values);

        // 3、记录创建审计，并返回创建后的团队信息。
        appendAuditEvent(actor, "TEAM_CREATED", "TEAM", id, List.of("name"), requestContext);

        return getRequiredTeam(id);
    }

    /**
     * 查询当前身份有权查看的单个团队。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @return 当前调用者可查看的团队信息
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public TeamDTO getTeam(RequestContext requestContext, long id) {
        // 认证当前调用者，非全局管理员只能读取自己可管理的团队。
        Actor actor = authenticateActor(requestContext);
        if (!actor.admin() && !actor.teams().contains(id)) {
            throw ApiException.forbidden();
        }

        return getRequiredTeam(id);
    }

    /**
     * 使用 expectedVersion 更新团队；仍有 ACTIVE 员工时禁止禁用。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @param body 已校验的请求正文
     * @return 当前调用者可查看的团队信息
     */
    public TeamDTO updateTeam(RequestContext requestContext, long id, ValidatedBody body) {
        // 1、先获取管理写锁，再检查当前调用者是否仍为全局管理员。
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);

        // 2、读取原团队和期望版本，PATCH 至少要提交一个可修改字段。
        TeamDTO before = getRequiredTeam(id);
        long expected = body.number("expectedVersion", true);

        if (body.has("name") == false && body.has("status") == false) {
            throw ApiException.invalid();
        }

        // 3、合并更新字段，未提交的字段保留原值。
        String name = body.has("name") ? body.text("name", 80) : before.name();
        String status = body.has("status") ? body.status() : before.status();

        // 4、检查版本是否一致，停用团队前确认没有 ACTIVE 员工。
        if (before.version() != expected) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }

        if (status.equals("DISABLED") && identityMapper.countActiveEmployeesByTeamId(id) > 0) {
            throw ApiException.conflict("ACTIVE_EMPLOYEES_EXIST");
        }

        // 5、按 ID 和版本号更新，影响行数不为 1 时返回版本冲突。
        if (identityMapper
            .updateTeam(Map.of("id", id, "name", name, "status", status, "expectedVersion", expected)) != 1) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }

        // 6、收集本次提交的字段名，记录审计并返回更新后的团队。
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
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param body 已校验的请求正文
     * @return 当前调用者可查看的员工信息
     */
    public EmployeeDTO createEmployee(RequestContext requestContext, ValidatedBody body) {
        // 1、先获取管理写锁，再检查当前调用者是否仍为全局管理员。
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);

        // 2、检查归属团队为 ACTIVE，再创建员工。
        long teamId = body.number("primaryTeamId", false);
        requireActiveTeam(teamId);
        Map<String, Object> values = new HashMap<>(Map.of("employeeCode", body.code("employeeCode"), "displayName",
            body.text("displayName", 100), "primaryTeamId", teamId));
        identityMapper.insertEmployee(values);
        long id = getGeneratedId(values);

        // 3、记录创建审计，并返回创建后的员工信息。
        appendAuditEvent(actor, "EMPLOYEE_CREATED", "EMPLOYEE", id,
            List.of("employeeCode", "displayName", "primaryTeamId"), requestContext);

        return getRequiredEmployee(id);
    }

    /**
     * 查询员工，并校验其归属团队是否在当前身份的可见范围内。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @return 当前调用者可查看的员工信息
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public EmployeeDTO getEmployee(RequestContext requestContext, long id) {
        // 1、认证当前调用者，并读取目标员工。
        Actor actor = authenticateActor(requestContext);

        EmployeeDTO target = getRequiredEmployee(id);

        // 2、非全局管理员只能读取可管理团队内的员工。
        if (!actor.admin() && !actor.teams().contains(target.primaryTeamId())) {
            throw ApiException.forbidden();
        }

        return target;
    }

    /**
     * 按期望版本更新员工资料及状态，禁用前保护最后有效管理员。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @param body 已校验的请求正文
     * @return 当前调用者可查看的员工信息
     */
    public EmployeeDTO updateEmployee(RequestContext requestContext, long id, ValidatedBody body) {
        // 1、先获取管理写锁，再检查当前调用者是否仍为全局管理员。
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);

        // 2、读取原员工，要求提交修改字段且期望版本与当前版本一致。
        EmployeeDTO before = getRequiredEmployee(id);
        long expected = body.number("expectedVersion", true);
        if (!body.has("displayName") && !body.has("primaryTeamId") && !body.has("status")) {
            throw ApiException.invalid();
        }

        if (before.version() != expected) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }

        // 3、合并名称、归属团队和状态；缺省字段保留原值，员工编码不修改。
        String name = body.has("displayName") ? body.text("displayName", 100) : before.displayName();
        String status = body.has("status") ? body.status() : before.status();
        long teamId = body.has("primaryTeamId") ? body.number("primaryTeamId", false) : before.primaryTeamId();
        requireActiveTeam(teamId);

        // 4、停用员工前，检查其是否为最后一名有效管理员。
        if (status.equals("DISABLED")) {
            ensureAdministratorRemains(before);
        }

        // 5、按 ID 和版本号更新，影响行数不为 1 时返回版本冲突。
        if (identityMapper.updateEmployee(Map.of("id", id, "displayName", name, "primaryTeamId", teamId, "status",
            status, "expectedVersion", expected)) != 1) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }

        // 6、收集修改字段名，记录审计并返回更新后的员工。
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
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param employeeId 目标员工主键
     * @param role 目标角色编码
     * @param scope 目标作用域类型
     * @param scopeId 授权范围主键；GLOBAL 固定为 0
     * @return 新增或恢复角色关系时为 true，已有有效关系时为 false
     */
    public boolean grantRole(RequestContext requestContext, long employeeId, String role, String scope, long scopeId) {
        // 1、先获取管理写锁，再检查当前调用者是否仍为全局管理员。
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);

        // 2、检查员工、角色与作用域；团队作用域必须指向 ACTIVE 团队。
        getRequiredEmployee(employeeId);
        Map<String, Object> values = createRoleParameters(employeeId, role, scope, scopeId);
        if (scope.equals("TEAM")) {
            requireActiveTeam(scopeId);
        }

        // 3、已有有效角色时直接返回，不重复写入或追加审计。
        if (identityMapper.countActiveRoleAssignments(values) > 0) {
            return false;
        }

        // 4、新增角色或恢复已撤销的关系，并记录授权审计。
        identityMapper.grantRole(values);

        appendAuditEvent(actor, "ROLE_GRANTED", "EMPLOYEE", employeeId, List.of("roleCode", "scopeType", "scopeId"),
            requestContext);

        return true;
    }

    /**
     * 幂等撤销角色；禁止撤销最后有效全局管理员。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param employeeId 目标员工主键
     * @param role 目标角色编码
     * @param scope 目标作用域类型
     * @param scopeId 授权范围主键；GLOBAL 固定为 0
     */
    public void revokeRole(RequestContext requestContext, long employeeId, String role, String scope, long scopeId) {
        // 1、先获取管理写锁，再检查当前调用者是否仍为全局管理员。
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);

        // 2、检查目标员工和角色参数，关系不存在或已撤销时直接结束。
        EmployeeDTO target = getRequiredEmployee(employeeId);
        Map<String, Object> values = createRoleParameters(employeeId, role, scope, scopeId);

        if (identityMapper.countActiveRoleAssignments(values) == 0) {
            return;
        }

        // 3、撤销平台管理员角色前，检查是否还会保留有效管理员。
        if (role.equals("PLATFORM_ADMIN")) {
            ensureAdministratorRemains(target);
        }

        // 4、标记角色已撤销，并记录撤权审计。
        identityMapper.revokeRole(values);

        appendAuditEvent(actor, "ROLE_REVOKED", "EMPLOYEE", employeeId, List.of("roleCode", "scopeType", "scopeId"),
            requestContext);
    }

    /**
     * 验证管理员及目标员工当前状态后签发一次性展示的管理密钥。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param employeeId 目标员工主键
     * @param body 已校验的请求正文
     * @param operationKey 客户端业务幂等键，不作为授权凭证
     * @return 仅本次首次响应可展示的凭证及元数据
     */
    public IssuedManagementCredentialDTO issueCredential(RequestContext requestContext, long employeeId,
        ValidatedBody body, String operationKey) {
        // 1、先获取管理写锁，再检查当前调用者是否仍为全局管理员。
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);

        // 2、检查目标员工和归属团队都为 ACTIVE。
        EmployeeDTO target = getRequiredEmployee(employeeId);
        if (!target.status().equals("ACTIVE")) {
            throw ApiException.conflict("INVALID_STATE");
        }

        requireActiveTeam(target.primaryTeamId());

        // 3、按签发者和幂等键签发凭证，同一键不能用于不同员工。
        IssuedManagementCredentialDTO issued = issueCredentialOnce(employeeId, "EMPLOYEE", actor.employee().id(),
            ValidatedBody.idempotency(operationKey), body.instant("expiresAt"));

        // 4、记录签发审计，完整密钥只在本次成功响应中返回。
        appendAuditEvent(actor, "MANAGEMENT_CREDENTIAL_ISSUED", "MANAGEMENT_CREDENTIAL", issued.credentialId(),
            List.of("expiresAt"), requestContext);

        return issued;
    }

    /**
     * 核对凭证归属后吊销；重复吊销不重复追加成功审计。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param employeeId 目标员工主键
     * @param credentialId 目标管理凭证主键
     */
    public void revokeCredential(RequestContext requestContext, long employeeId, long credentialId) {
        // 1、先获取管理写锁，再检查当前调用者是否仍为全局管理员。
        lockIdentityWriteState();
        Actor actor = requireAdministrator(requestContext);

        // 2、检查员工和凭证，凭证必须属于路径指定的员工。
        getRequiredEmployee(employeeId);
        ManagementCredentialDO credential = identityMapper.selectCredentialById(credentialId);

        if (credential == null || credential.employeeId() != employeeId) {
            throw ApiException.missing();
        }

        // 3、吊销凭证，只在本次确实更新了记录时追加审计。
        if (identityMapper.revokeCredential(credentialId) == 1) {
            appendAuditEvent(actor, "MANAGEMENT_CREDENTIAL_REVOKED", "MANAGEMENT_CREDENTIAL", credentialId,
                List.of("revokedAt"), requestContext);
        }
    }

    /**
     * 读取当前有效员工及其当前角色的安全视图。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
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
     * @param allowed 当前接口允许的字段名
     * @return 供 MyBatis 使用的参数集合
     */
    private Map<String, Object> createPageQuery(Map<String, String> input, Set<String> allowed) {
        // 1、拒绝当前接口不支持的查询参数。
        if (!allowed.containsAll(input.keySet())) {
            throw ApiException.invalid();
        }

        // 2、解析页码和每页条数，检查是否超出上限。
        int page = (int) parseBoundedPositiveNumber(input.getOrDefault("page", "1"), Integer.MAX_VALUE);
        int size = (int) parseBoundedPositiveNumber(
            input.getOrDefault("size", Integer.toString(ApiContractConstants.DEFAULT_PAGE_SIZE)),
            ApiContractConstants.MAX_PAGE_SIZE);
        Map<String, Object> query = new HashMap<>();
        query.put("page", page);
        query.put("size", size);

        // 3、先转为 long 再计算偏移量，避免 int 乘法溢出。
        query.put("offset", ((long) page - 1) * size);

        return query;
    }

    /**
     * 校验受上界约束的正整数查询参数。
     *
     * @param value 待检查或转换的值
     * @param max 允许的最大数值
     * @return 不超过指定上界的正整数
     */
    private long parseBoundedPositiveNumber(String value, long max) {
        long number = ValidatedBody.pathId(value, false);
        if (number > max) {
            throw ApiException.invalid();
        }

        return number;
    }

    /**
     * 仅接受现有接口允许的状态过滤条件。
     *
     * @param input 客户端输入的候选 JSON 或查询参数
     * @param query 已应用白名单和权限范围的查询参数
     */
    private void applyStatusFilter(Map<String, String> input, Map<String, Object> query) {
        // 提供状态时，只接受 ACTIVE 或 DISABLED。
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
        // 1、非全局管理员必须至少拥有一个有效管理团队。
        if (!actor.admin() && actor.teams().isEmpty()) {
            throw ApiException.forbidden();
        }

        // 2、将管理员标记和可管理团队传给 SQL，列表和 COUNT 使用相同范围。
        query.put("admin", actor.admin());
        query.put("visibleTeams", actor.teams());
    }

    /**
     * 按当前权限范围查询团队及其过滤后的总数。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param input 客户端输入的候选 JSON 或查询参数
     * @return 应用当前过滤条件后的查询结果
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<TeamDTO> listTeams(RequestContext requestContext, Map<String, String> input) {
        // 1、认证调用者，解析分页、状态和可管理团队。
        Actor actor = authenticateActor(requestContext);

        Map<String, Object> query = createPageQuery(input, Set.of("page", "size", "status"));
        applyStatusFilter(input, query);
        applyPermissionFilter(actor, query);

        // 2、使用相同查询条件读取团队列表和总数。
        return new PageDTO<>(identityMapper.listTeams(query), (int) query.get("page"), (int) query.get("size"),
            identityMapper.countTeams(query));
    }

    /**
     * 按当前权限范围查询员工及其过滤后的总数。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param input 客户端输入的候选 JSON 或查询参数
     * @return 应用当前过滤条件后的查询结果
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<EmployeeDTO> listEmployees(RequestContext requestContext, Map<String, String> input) {
        // 1、认证调用者，解析分页、状态和可管理团队。
        Actor actor = authenticateActor(requestContext);

        Map<String, Object> query = createPageQuery(input, Set.of("page", "size", "status", "primaryTeamId"));
        applyStatusFilter(input, query);
        applyPermissionFilter(actor, query);

        // 2、提供归属团队时追加过滤，与可管理团队范围共同生效。
        if (input.containsKey("primaryTeamId")) {
            query.put("primaryTeamId", ValidatedBody.pathId(input.get("primaryTeamId"), false));
        }

        // 3、使用相同查询条件读取员工列表和总数。
        return new PageDTO<>(identityMapper.listEmployees(query), (int) query.get("page"), (int) query.get("size"),
            identityMapper.countEmployees(query));
    }

    /**
     * 仅向管理员分页返回凭证元数据，不包含明文或摘要。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param employeeId 目标员工主键
     * @param input 客户端输入的候选 JSON 或查询参数
     * @return 应用当前过滤条件后的查询结果
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<ManagementCredentialMetadataDTO> listCredentialMetadata(RequestContext requestContext,
        long employeeId, Map<String, String> input) {
        // 1、检查全局管理员权限，并确认目标员工存在。
        requireAdministrator(requestContext);

        getRequiredEmployee(employeeId);

        // 2、按员工查询凭证元数据，保留已过期和已吊销记录，不返回密钥或摘要。
        Map<String, Object> query = createPageQuery(input, Set.of("page", "size"));
        query.put("employeeId", employeeId);

        return new PageDTO<>(identityMapper.listCredentialMetadata(query), (int) query.get("page"),
            (int) query.get("size"), identityMapper.countCredentialsByEmployeeId(employeeId));
    }

    /**
     * 按受限查询条件返回审计元数据，时间区间最多九十天。
     *
     * @param requestContext 包含凭证、来源地址和请求 ID 的请求上下文
     * @param input 客户端输入的候选 JSON 或查询参数
     * @return 应用当前过滤条件后的查询结果
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<AuditMetadataDTO> listAuditEvents(RequestContext requestContext, Map<String, String> input) {
        // 1、检查全局管理员权限，并校验允许的查询参数。
        requireAdministrator(requestContext);

        Map<String, Object> query = createPageQuery(input,
            Set.of("page", "size", "actorType", "actorId", "targetType", "targetId", "from", "to"));

        // 2、检查操作者类型，只接受 BOOTSTRAP 或 EMPLOYEE。
        if (input.containsKey("actorType")) {
            if (!Set.of("BOOTSTRAP", "EMPLOYEE").contains(input.get("actorType"))) {
                throw ApiException.invalid();
            }

            query.put("actorType", input.get("actorType"));
        }

        // 3、检查目标类型的字符和长度。
        if (input.containsKey("targetType")) {
            String type = input.get("targetType");
            if (!type.matches("[A-Z_]{1,32}")) {
                throw ApiException.invalid();
            }

            query.put("targetType", type);
        }

        // 4、解析非负 ID，兼容 Bootstrap 操作者固定使用的 ID 0。
        for (String field : List.of("actorId", "targetId")) {
            if (input.containsKey(field)) {
                query.put(field, ValidatedBody.pathId(input.get(field), true));
            }
        }

        // 5、解析起止时间，默认最近九十天，区间不能倒置或超过九十天。
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

        // 6、查询审计，将字段名 JSON 和数据库时间转换为响应字段。
        List<AuditMetadataDTO> result = identityMapper.listAuditEvents(query).stream()
            .map(row -> new AuditMetadataDTO(((Number) row.get("id")).longValue(), (String) row.get("actor_type"),
                ((Number) row.get("actor_id")).longValue(), (String) row.get("action"), (String) row.get("target_type"),
                ((Number) row.get("target_id")).longValue(),
                jsonMapper.readValue((String) row.get("changed_fields_json"),
                    jsonMapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                (String) row.get("trace_id"), convertDatabaseTimestamp(row.get("occurred_at"))))
            .toList();

        // 7、按相同过滤条件统计总数，返回审计分页结果。
        return new PageDTO<>(result, (int) query.get("page"), (int) query.get("size"),
            identityMapper.countAuditEvents(query));
    }

    /**
     * 将驱动返回的时间类型统一转换为 UTC Instant。
     *
     * @param value 待检查或转换的值
     * @return 符合 UTC 与数据库精度约束的时间
     */
    private Instant convertDatabaseTimestamp(Object value) {
        // 1、按驱动返回的类型转换时间；LocalDateTime 按 UTC 解释。
        if (value instanceof Instant instant) {
            return instant;
        }

        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }

        if (value instanceof LocalDateTime local) {
            return local.toInstant(ZoneOffset.UTC);
        }

        // 2、遇到不支持的类型直接报错，不用当前时间替代。
        throw new IllegalStateException("Unsupported database timestamp");
    }
}
