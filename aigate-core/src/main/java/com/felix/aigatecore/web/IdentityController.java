package com.felix.aigatecore.web;

import com.aigate.contracts.dto.AuditMetadataDTO;
import com.aigate.contracts.dto.BootstrapInitializedDTO;
import com.aigate.contracts.dto.EmployeeDTO;
import com.aigate.contracts.dto.IssuedManagementCredentialDTO;
import com.aigate.contracts.dto.ManagementCredentialMetadataDTO;
import com.aigate.contracts.dto.ManagementKeyRecoveredDTO;
import com.aigate.contracts.dto.ManagementProfileDTO;
import com.aigate.contracts.dto.PageDTO;
import com.aigate.contracts.dto.TeamDTO;
import com.felix.aigatecore.identity.IdentityService;
import com.felix.aigatecore.identity.RequestContext;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * P1 管理接口入口，仅负责 HTTP 参数解析和响应组装，权限及事务规则统一交给领域服务。
 *
 * @author AIGate
 */
@RestController
public class IdentityController {

    private final IdentityService service;

    /**
     * 通过构造器注入身份管理服务。
     *
     * @param service 身份领域服务
     */
    public IdentityController(IdentityService service) {
        this.service = service;
    }

    /**
     * 读取不带查询参数的管理请求上下文。
     *
     * @param request 当前 HTTP 请求
     * @return 已提取单个凭证头及受信来源的请求上下文
     */
    private RequestContext context(HttpServletRequest request) {
        // 拒绝非列表接口的查询参数，再读取认证请求头。
        if (!request.getParameterMap().isEmpty()) {
            throw ApiException.invalid();
        }

        return RequestContext.from(request);
    }

    /**
     * 读取单值查询参数，拒绝业务正文和重复的同名参数。
     *
     * @param request 当前 HTTP 请求
     * @return 经过约束的单值参数集合
     */
    private Map<String, String> query(HttpServletRequest request) {
        // 1、拒绝请求正文，读取查询参数。
        noBody(request);

        Map<String, String> query = new HashMap<>();

        // 2、同名查询参数只能出现一次，不默认选取其中一个值。
        request.getParameterMap().forEach((key, values) -> {
            if (values.length != 1) {
                throw ApiException.invalid();
            }

            query.put(key, values[0]);
        });

        return query;
    }

    /**
     * 确保无正文接口不携带可被误解释的业务请求体。
     *
     * @param request 当前 HTTP 请求
     */
    private void noBody(HttpServletRequest request) {
        try {
            // 实际读取一字节检查正文，同时覆盖没有 Content-Length 的分块请求。
            if (request.getInputStream().read() != -1) {
                throw ApiException.invalid();
            }
        } catch (IOException failure) {
            throw ApiException.invalid();
        }
    }

    /**
     * 在单个事务中首次创建团队、管理员、角色、凭证和审计，并更新初始化单例。
     *
     * @param bytes UTF-8 编码的 JSON 正文
     * @param request 当前 HTTP 请求
     * @return 包含状态码和响应正文的 HTTP 响应
     */
    @PostMapping(path = "/api/v1/bootstrap/initialize", consumes = "application/json")
    ResponseEntity<BootstrapInitializedDTO> initialize(@RequestBody byte[] bytes, HttpServletRequest request) {
        // 只读取初始化所需字段，将 Bootstrap 凭证和幂等键交给服务层。
        return ResponseEntity.status(201)
            .body(service.initialize(context(request),
                ValidatedBody.parse(bytes, "teamName", "employeeCode", "displayName", "managementKeyExpiresAt"),
                request.getHeader("Idempotency-Key")));
    }

    /**
     * 通过受控运维模式为已有有效管理员恢复凭证，不增加任何角色。
     *
     * @param bytes UTF-8 编码的 JSON 正文
     * @param request 当前 HTTP 请求
     * @return 包含状态码和响应正文的 HTTP 响应
     */
    @PostMapping(path = "/api/v1/bootstrap/recover-management-key", consumes = "application/json")
    ResponseEntity<ManagementKeyRecoveredDTO> recover(@RequestBody byte[] bytes, HttpServletRequest request) {
        // 只读取目标员工、到期时间和恢复原因，将恢复请求交给服务层。
        return ResponseEntity.status(201)
            .body(service.recover(context(request),
                ValidatedBody.parse(bytes, "targetEmployeeCode", "managementKeyExpiresAt", "reasonCode"),
                request.getHeader("Idempotency-Key")));
    }

    /**
     * 创建团队，并在同一事务中记录审计。
     *
     * @param bytes UTF-8 编码的 JSON 正文
     * @param request 当前 HTTP 请求
     * @return 包含状态码和响应正文的 HTTP 响应
     */
    @PostMapping(path = "/api/v1/teams", consumes = "application/json")
    ResponseEntity<TeamDTO> createTeam(@RequestBody byte[] bytes, HttpServletRequest request) {
        // 只接受团队名称，创建成功后返回 201。
        return ResponseEntity.status(201)
            .body(service.createTeam(context(request), ValidatedBody.parse(bytes, "name")));
    }

    /**
     * 查询当前身份可见的团队分页，查询范围由服务层确定。
     *
     * @param request 当前 HTTP 请求
     * @return 权限过滤后的团队分页及同范围总数
     */
    @GetMapping("/api/v1/teams")
    PageDTO<TeamDTO> teams(HttpServletRequest request) {
        return service.listTeams(RequestContext.from(request), query(request));
    }

    /**
     * 读取指定团队，实际可见范围由服务层按当前身份校验。
     *
     * @param teamId 目标团队主键
     * @param request 当前 HTTP 请求
     * @return 当前调用者可查看的团队信息
     */
    @GetMapping("/api/v1/teams/{teamId}")
    TeamDTO team(@PathVariable String teamId, HttpServletRequest request) {
        // 此类接口不接收正文，先拒绝多余输入，再读取路径或查询参数。
        noBody(request);

        return service.getTeam(context(request), ValidatedBody.pathId(teamId, false));
    }

    /**
     * 使用 expectedVersion 更新团队；仍有 ACTIVE 员工时禁止禁用。
     *
     * @param teamId 目标团队主键
     * @param bytes UTF-8 编码的 JSON 正文
     * @param request 当前 HTTP 请求
     * @return 当前调用者可查看的团队信息
     */
    @PatchMapping(path = "/api/v1/teams/{teamId}", consumes = "application/json")
    TeamDTO updateTeam(@PathVariable String teamId, @RequestBody byte[] bytes, HttpServletRequest request) {
        // 解析团队 ID、期望版本和修改字段，由服务层检查权限及版本。
        return service.updateTeam(context(request), ValidatedBody.pathId(teamId, false),
            ValidatedBody.parse(bytes, "expectedVersion", "name", "status"));
    }

    /**
     * 在 ACTIVE 团队下创建员工，并追加同事务审计。
     *
     * @param bytes UTF-8 编码的 JSON 正文
     * @param request 当前 HTTP 请求
     * @return 包含状态码和响应正文的 HTTP 响应
     */
    @PostMapping(path = "/api/v1/employees", consumes = "application/json")
    ResponseEntity<EmployeeDTO> createEmployee(@RequestBody byte[] bytes, HttpServletRequest request) {
        // 只接受员工编码、显示名称和归属团队，创建成功后返回 201。
        return ResponseEntity.status(201).body(service.createEmployee(context(request),
            ValidatedBody.parse(bytes, "employeeCode", "displayName", "primaryTeamId")));
    }

    /**
     * 查询当前身份可见的员工分页。
     *
     * @param request 当前 HTTP 请求
     * @return 权限过滤后的员工分页及同范围总数
     */
    @GetMapping("/api/v1/employees")
    PageDTO<EmployeeDTO> employees(HttpServletRequest request) {
        return service.listEmployees(RequestContext.from(request), query(request));
    }

    /**
     * 读取指定员工，服务层校验其归属团队是否对当前身份可见。
     *
     * @param employeeId 目标员工主键
     * @param request 当前 HTTP 请求
     * @return 当前调用者可查看的员工信息
     */
    @GetMapping("/api/v1/employees/{employeeId}")
    EmployeeDTO employee(@PathVariable String employeeId, HttpServletRequest request) {
        // 此类接口不接收正文，先拒绝多余输入，再读取路径或查询参数。
        noBody(request);

        return service.getEmployee(context(request), ValidatedBody.pathId(employeeId, false));
    }

    /**
     * 按期望版本更新员工资料及状态，禁用前保护最后有效管理员。
     *
     * @param employeeId 目标员工主键
     * @param bytes UTF-8 编码的 JSON 正文
     * @param request 当前 HTTP 请求
     * @return 当前调用者可查看的员工信息
     */
    @PatchMapping(path = "/api/v1/employees/{employeeId}", consumes = "application/json")
    EmployeeDTO updateEmployee(@PathVariable String employeeId, @RequestBody byte[] bytes, HttpServletRequest request) {
        // 解析员工 ID、期望版本和修改字段，不接收员工编码更新。
        return service.updateEmployee(context(request), ValidatedBody.pathId(employeeId, false),
            ValidatedBody.parse(bytes, "expectedVersion", "displayName", "primaryTeamId", "status"));
    }

    /**
     * 授予接口允许的角色关系，重复操作由服务层幂等处理。
     *
     * @param employeeId 目标员工主键
     * @param roleCode 接口允许的角色编码
     * @param scopeType GLOBAL 或 TEAM 作用域类型
     * @param scopeId 授权范围主键；GLOBAL 固定为 0
     * @param request 当前 HTTP 请求
     * @return 包含状态码和响应正文的 HTTP 响应
     */
    @PutMapping("/api/v1/employees/{employeeId}/role-assignments/{roleCode}/{scopeType}/{scopeId}")
    ResponseEntity<Void> grant(@PathVariable String employeeId, @PathVariable String roleCode,
        @PathVariable String scopeType, @PathVariable String scopeId, HttpServletRequest request) {
        // 1、拒绝正文，解析员工、角色及作用域参数。
        noBody(request);

        boolean created = service.grantRole(context(request), ValidatedBody.pathId(employeeId, false), roleCode,
            scopeType, ValidatedBody.pathId(scopeId, true));

        // 2、新授权返回 201，已有有效关系返回 204。
        return ResponseEntity.status(created ? 201 : 204).build();
    }

    /**
     * 幂等撤销角色；禁止撤销最后有效全局管理员。
     *
     * @param employeeId 目标员工主键
     * @param roleCode 接口允许的角色编码
     * @param scopeType GLOBAL 或 TEAM 作用域类型
     * @param scopeId 授权范围主键；GLOBAL 固定为 0
     * @param request 当前 HTTP 请求
     * @return 包含状态码和响应正文的 HTTP 响应
     */
    @DeleteMapping("/api/v1/employees/{employeeId}/role-assignments/{roleCode}/{scopeType}/{scopeId}")
    ResponseEntity<Void> revokeRole(@PathVariable String employeeId, @PathVariable String roleCode,
        @PathVariable String scopeType, @PathVariable String scopeId, HttpServletRequest request) {
        // 拒绝正文，撤销指定角色关系，成功后返回 204。
        noBody(request);

        service.revokeRole(context(request), ValidatedBody.pathId(employeeId, false), roleCode, scopeType,
            ValidatedBody.pathId(scopeId, true));

        return ResponseEntity.noContent().build();
    }

    /**
     * 签发管理凭证，仅在首次成功响应中返回密钥明文。
     *
     * @param employeeId 目标员工主键
     * @param bytes UTF-8 编码的 JSON 正文
     * @param request 当前 HTTP 请求
     * @return 包含状态码和响应正文的 HTTP 响应
     */
    @PostMapping(path = "/api/v1/employees/{employeeId}/management-credentials", consumes = "application/json")
    ResponseEntity<IssuedManagementCredentialDTO> issue(@PathVariable String employeeId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        // 解析目标员工、到期时间和幂等键，首次签发成功后返回 201。
        return ResponseEntity.status(201)
            .body(service.issueCredential(context(request), ValidatedBody.pathId(employeeId, false),
                ValidatedBody.parse(bytes, "expiresAt"), request.getHeader("Idempotency-Key")));
    }

    /**
     * 分页读取指定员工的管理凭证元数据，不返回明文或摘要。
     *
     * @param employeeId 目标员工主键
     * @param request 当前 HTTP 请求
     * @return 不含明文与摘要的凭证元数据分页
     */
    @GetMapping("/api/v1/employees/{employeeId}/management-credentials")
    PageDTO<ManagementCredentialMetadataDTO> credentials(@PathVariable String employeeId, HttpServletRequest request) {
        // 解析员工 ID 和分页参数，只查询凭证元数据。
        return service.listCredentialMetadata(RequestContext.from(request), ValidatedBody.pathId(employeeId, false),
            query(request));
    }

    /**
     * 核对凭证归属后吊销；重复吊销不重复追加成功审计。
     *
     * @param employeeId 目标员工主键
     * @param credentialId 目标管理凭证主键
     * @param request 当前 HTTP 请求
     * @return 包含状态码和响应正文的 HTTP 响应
     */
    @DeleteMapping("/api/v1/employees/{employeeId}/management-credentials/{credentialId}")
    ResponseEntity<Void> revokeCredential(@PathVariable String employeeId, @PathVariable String credentialId,
        HttpServletRequest request) {
        // 拒绝正文，吊销指定员工的凭证，成功后返回 204。
        noBody(request);

        service.revokeCredential(context(request), ValidatedBody.pathId(employeeId, false),
            ValidatedBody.pathId(credentialId, false));

        return ResponseEntity.noContent().build();
    }

    /**
     * 查询当前管理身份及其有效角色。
     *
     * @param request 当前 HTTP 请求
     * @return 当前有效员工及其角色视图
     */
    @GetMapping("/api/v1/me/profile")
    ManagementProfileDTO profile(HttpServletRequest request) {
        // 拒绝正文，查询当前员工和角色。
        noBody(request);

        return service.getCurrentProfile(context(request));
    }

    /**
     * 查询受权限保护的审计元数据分页。
     *
     * @param request 当前 HTTP 请求
     * @return 仅包含字段名和操作信息的审计元数据分页
     */
    @GetMapping("/api/v1/admin/audit-events")
    PageDTO<AuditMetadataDTO> audits(HttpServletRequest request) {
        // 仅解析 HTTP 输入；审计访问权限、时间窗口和字段过滤由服务层统一校验。
        return service.listAuditEvents(RequestContext.from(request), query(request));
    }

    /**
     * 只向通过服务身份校验的内部调用者返回最小服务状态。
     *
     * @param request 当前 HTTP 请求
     * @return 经过约束的单值参数集合
     */
    @GetMapping("/internal/v1/ping")
    Map<String, String> ping(HttpServletRequest request) {
        // 拒绝正文及查询参数，仅返回 Core 的最小运行状态。
        noBody(request);
        if (!request.getParameterMap().isEmpty()) {
            throw ApiException.invalid();
        }

        return Map.of("service", "aigate-core", "status", "UP");
    }
}
