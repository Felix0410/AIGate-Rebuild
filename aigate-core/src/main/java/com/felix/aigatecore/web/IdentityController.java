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
     * 创建IdentityController，显式注入当前组件依赖或不可变状态。
     *
     * @param service 身份领域服务
     */
    public IdentityController(IdentityService service) {
        this.service = service;
    }

    /**
     * 读取不带查询参数的管理请求上下文。
     *
     * @param request 当前 Servlet 或函数式请求
     * @return 已提取单个凭证头及受信来源的请求上下文
     */
    private RequestContext context(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw ApiException.invalid();
        }
        return RequestContext.from(request);
    }

    /**
     * 读取单值查询参数，拒绝业务正文和重复的同名参数。
     *
     * @param request 当前 Servlet 或函数式请求
     * @return 经过约束的单值参数集合
     */
    private Map<String, String> query(HttpServletRequest request) {
        noBody(request);
        Map<String, String> query = new HashMap<>();
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
     * @param request 当前 Servlet 或函数式请求
     */
    private void noBody(HttpServletRequest request) {
        try {
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
     * @param bytes 原始 UTF-8 JSON 字节
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @PostMapping(path = "/api/v1/bootstrap/initialize", consumes = "application/json")
    ResponseEntity<BootstrapInitializedDTO> initialize(@RequestBody byte[] bytes, HttpServletRequest request) {
        return ResponseEntity.status(201)
            .body(service.initialize(context(request),
                ValidatedBody.parse(bytes, "teamName", "employeeCode", "displayName", "managementKeyExpiresAt"),
                request.getHeader("Idempotency-Key")));
    }

    /**
     * 通过受控运维模式为已有有效管理员恢复凭证，不增加任何角色。
     *
     * @param bytes 原始 UTF-8 JSON 字节
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @PostMapping(path = "/api/v1/bootstrap/recover-management-key", consumes = "application/json")
    ResponseEntity<ManagementKeyRecoveredDTO> recover(@RequestBody byte[] bytes, HttpServletRequest request) {
        return ResponseEntity.status(201)
            .body(service.recover(context(request),
                ValidatedBody.parse(bytes, "targetEmployeeCode", "managementKeyExpiresAt", "reasonCode"),
                request.getHeader("Idempotency-Key")));
    }

    /**
     * 创建团队，并在同一事务中记录审计。
     *
     * @param bytes 原始 UTF-8 JSON 字节
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @PostMapping(path = "/api/v1/teams", consumes = "application/json")
    ResponseEntity<TeamDTO> createTeam(@RequestBody byte[] bytes, HttpServletRequest request) {
        return ResponseEntity.status(201)
            .body(service.createTeam(context(request), ValidatedBody.parse(bytes, "name")));
    }

    /**
     * 查询当前身份可见的团队分页，查询范围由服务层确定。
     *
     * @param request 当前 Servlet 或函数式请求
     * @return 权限过滤后的团队分页及同范围总数
     */
    @GetMapping("/api/v1/teams")
    PageDTO<TeamDTO> teams(HttpServletRequest request) {
        return service.listTeams(RequestContext.from(request), query(request));
    }

    /**
     * 读取指定团队；测试辅助方法通过真实 HTTP 创建场景所需团队。
     *
     * @param teamId 目标团队主键
     * @param request 当前 Servlet 或函数式请求
     * @return 当前可见团队的安全视图
     */
    @GetMapping("/api/v1/teams/{teamId}")
    TeamDTO team(@PathVariable String teamId, HttpServletRequest request) {
        noBody(request);
        return service.getTeam(context(request), ValidatedBody.pathId(teamId, false));
    }

    /**
     * 使用 expectedVersion 更新团队；仍有 ACTIVE 员工时禁止禁用。
     *
     * @param teamId 目标团队主键
     * @param bytes 原始 UTF-8 JSON 字节
     * @param request 当前 Servlet 或函数式请求
     * @return 当前可见团队的安全视图
     */
    @PatchMapping(path = "/api/v1/teams/{teamId}", consumes = "application/json")
    TeamDTO updateTeam(@PathVariable String teamId, @RequestBody byte[] bytes, HttpServletRequest request) {
        return service.updateTeam(context(request), ValidatedBody.pathId(teamId, false),
            ValidatedBody.parse(bytes, "expectedVersion", "name", "status"));
    }

    /**
     * 在 ACTIVE 团队下创建员工，并追加同事务审计。
     *
     * @param bytes 原始 UTF-8 JSON 字节
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @PostMapping(path = "/api/v1/employees", consumes = "application/json")
    ResponseEntity<EmployeeDTO> createEmployee(@RequestBody byte[] bytes, HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.createEmployee(context(request),
            ValidatedBody.parse(bytes, "employeeCode", "displayName", "primaryTeamId")));
    }

    /**
     * 查询当前身份可见的员工分页。
     *
     * @param request 当前 Servlet 或函数式请求
     * @return 权限过滤后的员工分页及同范围总数
     */
    @GetMapping("/api/v1/employees")
    PageDTO<EmployeeDTO> employees(HttpServletRequest request) {
        return service.listEmployees(RequestContext.from(request), query(request));
    }

    /**
     * 读取指定员工；测试辅助方法通过真实 HTTP 创建场景所需员工。
     *
     * @param employeeId 目标员工主键
     * @param request 当前 Servlet 或函数式请求
     * @return 当前可见员工的安全视图
     */
    @GetMapping("/api/v1/employees/{employeeId}")
    EmployeeDTO employee(@PathVariable String employeeId, HttpServletRequest request) {
        noBody(request);
        return service.getEmployee(context(request), ValidatedBody.pathId(employeeId, false));
    }

    /**
     * 按期望版本更新员工资料及状态，禁用前保护最后有效管理员。
     *
     * @param employeeId 目标员工主键
     * @param bytes 原始 UTF-8 JSON 字节
     * @param request 当前 Servlet 或函数式请求
     * @return 当前可见员工的安全视图
     */
    @PatchMapping(path = "/api/v1/employees/{employeeId}", consumes = "application/json")
    EmployeeDTO updateEmployee(@PathVariable String employeeId, @RequestBody byte[] bytes, HttpServletRequest request) {
        return service.updateEmployee(context(request), ValidatedBody.pathId(employeeId, false),
            ValidatedBody.parse(bytes, "expectedVersion", "displayName", "primaryTeamId", "status"));
    }

    /**
     * 授予合同允许的角色关系，重复操作由服务层幂等处理。
     *
     * @param employeeId 目标员工主键
     * @param roleCode 合同允许的角色编码
     * @param scopeType GLOBAL 或 TEAM 作用域类型
     * @param scopeId 授权范围主键；GLOBAL 固定为 0
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @PutMapping("/api/v1/employees/{employeeId}/role-assignments/{roleCode}/{scopeType}/{scopeId}")
    ResponseEntity<Void> grant(@PathVariable String employeeId, @PathVariable String roleCode,
        @PathVariable String scopeType, @PathVariable String scopeId, HttpServletRequest request) {
        noBody(request);
        boolean created = service.grantRole(context(request), ValidatedBody.pathId(employeeId, false), roleCode,
            scopeType, ValidatedBody.pathId(scopeId, true));
        return ResponseEntity.status(created ? 201 : 204).build();
    }

    /**
     * 幂等撤销角色；禁止撤销最后有效全局管理员。
     *
     * @param employeeId 目标员工主键
     * @param roleCode 合同允许的角色编码
     * @param scopeType GLOBAL 或 TEAM 作用域类型
     * @param scopeId 授权范围主键；GLOBAL 固定为 0
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @DeleteMapping("/api/v1/employees/{employeeId}/role-assignments/{roleCode}/{scopeType}/{scopeId}")
    ResponseEntity<Void> revokeRole(@PathVariable String employeeId, @PathVariable String roleCode,
        @PathVariable String scopeType, @PathVariable String scopeId, HttpServletRequest request) {
        noBody(request);
        service.revokeRole(context(request), ValidatedBody.pathId(employeeId, false), roleCode, scopeType,
            ValidatedBody.pathId(scopeId, true));
        return ResponseEntity.noContent().build();
    }

    /**
     * 仅在当前测试内保存首次返回的密钥，后续断言不得再次回显。
     *
     * @param employeeId 目标员工主键
     * @param bytes 原始 UTF-8 JSON 字节
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @PostMapping(path = "/api/v1/employees/{employeeId}/management-credentials", consumes = "application/json")
    ResponseEntity<IssuedManagementCredentialDTO> issue(@PathVariable String employeeId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return ResponseEntity.status(201)
            .body(service.issueCredential(context(request), ValidatedBody.pathId(employeeId, false),
                ValidatedBody.parse(bytes, "expiresAt"), request.getHeader("Idempotency-Key")));
    }

    /**
     * 构造指定员工的管理凭证路径。
     *
     * @param employeeId 目标员工主键
     * @param request 当前 Servlet 或函数式请求
     * @return 不含明文与摘要的凭证元数据分页
     */
    @GetMapping("/api/v1/employees/{employeeId}/management-credentials")
    PageDTO<ManagementCredentialMetadataDTO> credentials(@PathVariable String employeeId, HttpServletRequest request) {
        return service.listCredentialMetadata(RequestContext.from(request), ValidatedBody.pathId(employeeId, false),
            query(request));
    }

    /**
     * 核对凭证归属后吊销；重复吊销不重复追加成功审计。
     *
     * @param employeeId 目标员工主键
     * @param credentialId 目标管理凭证主键
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @DeleteMapping("/api/v1/employees/{employeeId}/management-credentials/{credentialId}")
    ResponseEntity<Void> revokeCredential(@PathVariable String employeeId, @PathVariable String credentialId,
        HttpServletRequest request) {
        noBody(request);
        service.revokeCredential(context(request), ValidatedBody.pathId(employeeId, false),
            ValidatedBody.pathId(credentialId, false));
        return ResponseEntity.noContent().build();
    }

    /**
     * 查询当前管理身份及其有效角色。
     *
     * @param request 当前 Servlet 或函数式请求
     * @return 当前有效员工及其角色视图
     */
    @GetMapping("/api/v1/me/profile")
    ManagementProfileDTO profile(HttpServletRequest request) {
        noBody(request);
        return service.getCurrentProfile(context(request));
    }

    /**
     * 查询受权限保护的审计元数据分页。
     *
     * @param request 当前 Servlet 或函数式请求
     * @return 仅包含字段名和操作信息的审计元数据分页
     */
    @GetMapping("/api/v1/admin/audit-events")
    PageDTO<AuditMetadataDTO> audits(HttpServletRequest request) {
        return service.listAuditEvents(RequestContext.from(request), query(request));
    }

    /**
     * 只向通过服务身份校验的内部调用者返回最小服务状态。
     *
     * @param request 当前 Servlet 或函数式请求
     * @return 经过约束的单值参数集合
     */
    @GetMapping("/internal/v1/ping")
    Map<String, String> ping(HttpServletRequest request) {
        noBody(request);
        if (!request.getParameterMap().isEmpty()) {
            throw ApiException.invalid();
        }
        return Map.of("service", "aigate-core", "status", "UP");
    }
}
