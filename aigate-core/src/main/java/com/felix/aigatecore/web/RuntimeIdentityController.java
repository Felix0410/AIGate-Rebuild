package com.felix.aigatecore.web;

import static com.felix.aigatecore.web.ManagementRequestParameters.context;
import static com.felix.aigatecore.web.ManagementRequestParameters.query;
import static com.felix.aigatecore.web.ManagementRequestParameters.requireNoBody;

import com.aigate.contracts.dto.AgentDTO;
import com.aigate.contracts.dto.AgentEmployeePermissionDTO;
import com.aigate.contracts.dto.KeyMetadataDTO;
import com.aigate.contracts.dto.NewKeyDTO;
import com.aigate.contracts.dto.PageDTO;
import com.aigate.contracts.dto.RuntimeIdentityDTO;
import com.felix.aigatecore.runtime.RuntimeIdentityService;
import com.felix.aigatecore.identity.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
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
 * Agent、双运行凭证和员工授权的 HTTP 接口，所有管理权限由 Core 服务层检查。
 *
 * @author AIGate
 */
@RestController
public class RuntimeIdentityController {

    private final RuntimeIdentityService service;

    /**
     * 注入运行身份服务。
     *
     * @param service 运行身份服务
     */
    public RuntimeIdentityController(RuntimeIdentityService service) {
        this.service = service;
    }

    /**
     * 创建 Agent，正文只允许应用编码、名称和归属团队。
     */
    @PostMapping(path = "/api/v1/agents", consumes = "application/json")
    ResponseEntity<AgentDTO> createAgent(@RequestBody byte[] bytes, HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.createAgent(context(request),
            ValidatedBody.parse(bytes, "applicationCode", "displayName", "ownerTeamId")));
    }

    /**
     * 按管理员可见范围分页查询 Agent，查询条件由服务层逐项校验。
     */
    @GetMapping("/api/v1/agents")
    PageDTO<AgentDTO> agents(HttpServletRequest request) {
        return service.listAgents(RequestContext.from(request), query(request));
    }

    /**
     * 查询单个 Agent，拒绝多余正文和查询参数。
     */
    @GetMapping("/api/v1/agents/{agentId}")
    AgentDTO agent(@PathVariable String agentId, HttpServletRequest request) {
        requireNoBody(request);
        return service.getAgent(context(request), ValidatedBody.pathId(agentId, false));
    }

    /**
     * 按期望版本更新 Agent，应用编码不在可修改字段内。
     */
    @PatchMapping(path = "/api/v1/agents/{agentId}", consumes = "application/json")
    AgentDTO updateAgent(@PathVariable String agentId, @RequestBody byte[] bytes, HttpServletRequest request) {
        return service.updateAgent(context(request), ValidatedBody.pathId(agentId, false),
            ValidatedBody.parse(bytes, "expectedVersion", "displayName", "ownerTeamId", "status"));
    }

    /**
     * 为 Agent 首次签发运行密钥，明文只在本次 201 响应中返回。
     */
    @PostMapping(path = "/api/v1/agents/{agentId}/keys", consumes = "application/json")
    ResponseEntity<NewKeyDTO> issueAgentKey(@PathVariable String agentId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.issueKey(context(request), true,
            ValidatedBody.pathId(agentId, false), null, ValidatedBody.parse(bytes, "expiresAt"),
            request.getHeader("Idempotency-Key")));
    }

    /**
     * 分页查询 Agent 密钥元数据，不提供密钥明文或摘要。
     */
    @GetMapping("/api/v1/agents/{agentId}/keys")
    PageDTO<KeyMetadataDTO> listAgentKey(@PathVariable String agentId, HttpServletRequest request) {
        return service.listKeys(RequestContext.from(request), true,
            ValidatedBody.pathId(agentId, false), query(request));
    }

    /**
     * 轮换指定 Agent 密钥，以独立幂等键标识本次物理签发。
     */
    @PostMapping(path = "/api/v1/agents/{agentId}/keys/{keyId}/rotate", consumes = "application/json")
    ResponseEntity<NewKeyDTO> rotateAgentKey(@PathVariable String agentId, @PathVariable String keyId,
        @RequestBody byte[] bytes, HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.issueKey(context(request), true,
            ValidatedBody.pathId(agentId, false), ValidatedBody.pathId(keyId, false),
            ValidatedBody.parse(bytes, "expiresAt"), request.getHeader("Idempotency-Key")));
    }

    /**
     * 吊销指定 Agent 密钥，已吊销时仍返回 204。
     */
    @DeleteMapping("/api/v1/agents/{agentId}/keys/{keyId}")
    ResponseEntity<Void> revokeAgentKey(@PathVariable String agentId, @PathVariable String keyId,
        HttpServletRequest request) {
        requireNoBody(request);
        service.revokeKey(context(request), true, ValidatedBody.pathId(agentId, false),
            ValidatedBody.pathId(keyId, false));
        return ResponseEntity.noContent().build();
    }

    /**
     * 为员工签发独立运行凭证，不复用管理密钥用途。
     */
    @PostMapping(path = "/api/v1/employees/{employeeId}/runtime-credentials", consumes = "application/json")
    ResponseEntity<NewKeyDTO> issueEmployeeCredential(@PathVariable String employeeId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.issueKey(context(request), false,
            ValidatedBody.pathId(employeeId, false), null, ValidatedBody.parse(bytes, "expiresAt"),
            request.getHeader("Idempotency-Key")));
    }

    /**
     * 分页查询员工运行凭证历史，只返回安全元数据。
     */
    @GetMapping("/api/v1/employees/{employeeId}/runtime-credentials")
    PageDTO<KeyMetadataDTO> listEmployeeCredential(@PathVariable String employeeId, HttpServletRequest request) {
        return service.listKeys(RequestContext.from(request), false,
            ValidatedBody.pathId(employeeId, false), query(request));
    }

    /**
     * 轮换员工运行凭证，路径中的旧凭证必须属于该员工。
     */
    @PostMapping(path = "/api/v1/employees/{employeeId}/runtime-credentials/"
        + "{credentialId}/rotate", consumes = "application/json")
    ResponseEntity<NewKeyDTO> rotateEmployeeCredential(@PathVariable String employeeId,
        @PathVariable String credentialId,
        @RequestBody byte[] bytes, HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.issueKey(context(request), false,
            ValidatedBody.pathId(employeeId, false), ValidatedBody.pathId(credentialId, false),
            ValidatedBody.parse(bytes, "expiresAt"), request.getHeader("Idempotency-Key")));
    }

    /**
     * 吊销员工运行凭证，保留永久签发记录供幂等重放检查。
     */
    @DeleteMapping("/api/v1/employees/{employeeId}/runtime-credentials/{credentialId}")
    ResponseEntity<Void> revokeEmployeeCredential(@PathVariable String employeeId, @PathVariable String credentialId,
        HttpServletRequest request) {
        requireNoBody(request);
        service.revokeKey(context(request), false, ValidatedBody.pathId(employeeId, false),
            ValidatedBody.pathId(credentialId, false));
        return ResponseEntity.noContent().build();
    }

    /**
     * 授予或恢复员工使用 Agent 的权限，重复授予返回 204。
     */
    @PutMapping("/api/v1/agents/{agentId}/employees/{employeeId}")
    ResponseEntity<Void> grant(@PathVariable String agentId, @PathVariable String employeeId,
        HttpServletRequest request) {
        requireNoBody(request);
        boolean created = service.changePermission(context(request), ValidatedBody.pathId(agentId, false),
            ValidatedBody.pathId(employeeId, false), true);
        return ResponseEntity.status(created ? 201 : 204).build();
    }

    /**
     * 撤销员工使用 Agent 的权限，重复撤销不增加审计。
     */
    @DeleteMapping("/api/v1/agents/{agentId}/employees/{employeeId}")
    ResponseEntity<Void> revoke(@PathVariable String agentId, @PathVariable String employeeId,
        HttpServletRequest request) {
        requireNoBody(request);
        service.changePermission(context(request), ValidatedBody.pathId(agentId, false),
            ValidatedBody.pathId(employeeId, false), false);
        return ResponseEntity.noContent().build();
    }

    /**
     * 分页查询 Agent 的员工授权，包含已撤销关系。
     */
    @GetMapping("/api/v1/agents/{agentId}/employees")
    PageDTO<AgentEmployeePermissionDTO> permissions(@PathVariable String agentId, HttpServletRequest request) {
        return service.listPermissions(RequestContext.from(request),
            ValidatedBody.pathId(agentId, false), query(request));
    }

    /**
     * 验证内网服务携带的两把运行密钥，正文不能替代认证头。
     */
    @PostMapping("/internal/v1/runtime-identities/verify")
    RuntimeIdentityDTO verify(@RequestBody(required = false) byte[] bytes, HttpServletRequest request) {
        // 1、服务身份已由过滤器检查，正文不能携带替代凭证的员工或 Agent ID。
        if (!request.getParameterMap().isEmpty()) {
            throw ApiException.invalid();
        }
        if (bytes != null && bytes.length > 0) {
            ValidatedBody.parse(bytes);
        }

        // 2、两个凭证头都必须唯一，分别交给服务层按各自前缀和 Pepper 验证。
        var agentHeaders = Collections.list(request.getHeaders("Authorization"));
        var employeeHeaders = Collections.list(request.getHeaders("X-Employee-Key"));
        if (agentHeaders.size() != 1 || employeeHeaders.size() != 1) {
            throw ApiException.unauthenticated();
        }
        return service.verify(agentHeaders.getFirst(), employeeHeaders.getFirst());
    }
}
