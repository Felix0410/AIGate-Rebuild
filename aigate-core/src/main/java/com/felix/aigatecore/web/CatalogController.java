package com.felix.aigatecore.web;

import static com.felix.aigatecore.web.ManagementRequestParameters.context;
import static com.felix.aigatecore.web.ManagementRequestParameters.query;
import static com.felix.aigatecore.web.ManagementRequestParameters.requireNoBody;

import com.aigate.contracts.dto.ProviderDTO;
import com.aigate.contracts.dto.PointsPriceDTO;
import com.aigate.contracts.dto.PurchasePriceDTO;
import com.aigate.contracts.dto.LogicalModelDTO;
import com.aigate.contracts.dto.DeploymentDTO;
import com.aigate.contracts.dto.PageDTO;
import com.aigate.contracts.dto.AgentModelPermissionDTO;
import com.aigate.contracts.dto.CatalogEligibilityDTO;
import com.aigate.contracts.dto.ProviderCredentialMetadataDTO;
import com.felix.aigatecore.catalog.CatalogService;
import com.felix.aigatecore.catalog.CatalogCredentialService;
import com.felix.aigatecore.identity.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * P3精确管理路由及私有目录检查，字段白名单、方法和权限在Core逐层校验。
 *
 * @author AIGate
 */
@RestController
public class CatalogController {

    private final CatalogService service;
    private final CatalogCredentialService credentials;

    /**
     * 注入目录和凭证业务，不在控制器读取数据库或秘密配置。
     *
     * @param service 目录、价格及ACL业务
     * @param credentials Provider凭证业务
     */
    public CatalogController(CatalogService service, CatalogCredentialService credentials) {
        this.service = service;
        this.credentials = credentials;
    }

    /** 路径ID必须为可无损转为Java Long的十进制正整数。 */
    private long id(String value) {
        return ValidatedBody.pathId(value, false);
    }

    /** 价格修订与数据库INT范围一致，不做静默窄化转换。 */
    private int revision(String value) {
        long result = id(value);
        if (result > Integer.MAX_VALUE) {
            throw ApiException.invalid();
        }
        return (int) result;
    }

    /** 收据头必须唯一，不能由容器默认选取多个Header中的第一个。 */
    private String idempotency(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Idempotency-Key"));
        if (headers.size() != 1) {
            throw ApiException.invalid();
        }
        return headers.getFirst();
    }

    /** 创建Provider，禁止未知字段和隐式类型转换。 */
    @PostMapping(path = "/api/v1/providers", consumes = "application/json")
    ResponseEntity<ProviderDTO> createProvider(@RequestBody byte[] bytes, HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.createProvider(context(request),
            ValidatedBody.parse(bytes, "code", "name", "hostingMode", "baseUrlHostPolicy")));
    }

    /** 分页读取安全Provider元数据，过滤条件与total由同一查询规则确定。 */
    @GetMapping("/api/v1/providers")
    PageDTO<ProviderDTO> listProviders(HttpServletRequest request) {
        return service.listProviders(RequestContext.from(request), query(request));
    }

    /** 查询单项Provider，拒绝多余正文和查询参数。 */
    @GetMapping("/api/v1/providers/{providerId}")
    ProviderDTO getProvider(@PathVariable String providerId, HttpServletRequest request) {
        requireNoBody(request);
        return service.getProvider(context(request), id(providerId));
    }

    /** 通过CAS更新可变字段，不允许替换不可变身份。 */
    @PatchMapping(path = "/api/v1/providers/{providerId}", consumes = "application/json")
    ProviderDTO updateProvider(@PathVariable String providerId, @RequestBody byte[] bytes, HttpServletRequest request) {
        return service.updateProvider(context(request), id(providerId),
            ValidatedBody.parse(bytes, "expectedVersion", "name", "status", "baseUrlHostPolicy"));
    }

    /** 创建Model，禁止未知字段和隐式类型转换。 */
    @PostMapping(path = "/api/v1/models", consumes = "application/json")
    ResponseEntity<LogicalModelDTO> createModel(@RequestBody byte[] bytes, HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.createModel(context(request), ValidatedBody.parse(bytes,
            "modelCode", "displayName", "modelFamily", "immutableModelRevision", "capabilityProfile")));
    }

    /** 分页读取安全Model元数据，过滤条件与total由同一查询规则确定。 */
    @GetMapping("/api/v1/models")
    PageDTO<LogicalModelDTO> listModels(HttpServletRequest request) {
        return service.listModels(RequestContext.from(request), query(request));
    }

    /** 查询单项Model，拒绝多余正文和查询参数。 */
    @GetMapping("/api/v1/models/{modelId}")
    LogicalModelDTO getModel(@PathVariable String modelId, HttpServletRequest request) {
        requireNoBody(request);
        return service.getModel(context(request), id(modelId));
    }

    /** 通过CAS更新可变字段，不允许替换不可变身份。 */
    @PatchMapping(path = "/api/v1/models/{modelId}", consumes = "application/json")
    LogicalModelDTO updateModel(@PathVariable String modelId, @RequestBody byte[] bytes, HttpServletRequest request) {
        return service.updateModel(context(request), id(modelId),
            ValidatedBody.parse(bytes, "expectedVersion", "displayName", "status"));
    }

    /** 登记DRAFT部署，真实ID由服务端生成，创建时不能绑定未来容量池。 */
    @PostMapping(path = "/api/v1/models/{modelId}/deployments", consumes = "application/json")
    ResponseEntity<DeploymentDTO> createDeployment(@PathVariable String modelId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.createDeployment(context(request), id(modelId),
            ValidatedBody.parse(bytes, "providerId", "deploymentCode", "endpointUri", "remoteModelName", "regionCode",
                "capabilityRevision", "adapterCompatibility", "adapterEvidenceRef", "routingPriority", "routingWeight",
                "maxConcurrent")));
    }

    /** 按模型分页查询其部署，只有安全元数据进入HTTP响应。 */
    @GetMapping("/api/v1/models/{modelId}/deployments")
    PageDTO<DeploymentDTO> listDeployments(@PathVariable String modelId, HttpServletRequest request) {
        return service.listDeployments(RequestContext.from(request), id(modelId), query(request));
    }

    /** 查询部署，不回显endpoint或适配证据全文。 */
    @GetMapping("/api/v1/deployments/{deploymentId}")
    DeploymentDTO getDeployment(@PathVariable String deploymentId, HttpServletRequest request) {
        requireNoBody(request);
        return service.getDeployment(context(request), id(deploymentId));
    }

    /** 更新状态和路由元数据，进入ACTIVE必须复核全部P3目录条件。 */
    @PatchMapping(path = "/api/v1/deployments/{deploymentId}", consumes = "application/json")
    DeploymentDTO updateDeployment(@PathVariable String deploymentId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return service.updateDeployment(context(request), id(deploymentId), ValidatedBody.parse(bytes,
            "expectedVersion", "status", "routingPriority", "routingWeight", "maxConcurrent"));
    }

    /** 创建Points价格草稿，金额只接受固定刻度字符串。 */
    @PostMapping(path = "/api/v1/models/{modelId}/points-prices", consumes = "application/json")
    ResponseEntity<PointsPriceDTO> createPointsPrice(@PathVariable String modelId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.createPointsPrice(context(request), id(modelId),
            ValidatedBody.parse(bytes, "revision", "pointPer1kInput", "pointPer1kOutput")));
    }

    /** 发布不可变价格，父版本CAS与当前指针切换使用同一事务。 */
    @PostMapping(path = "/api/v1/models/{modelId}/points-prices/{revision}/publish", consumes = "application/json")
    PointsPriceDTO publishPointsPrice(@PathVariable String modelId, @PathVariable String revision,
        @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return service.publishPointsPrice(context(request), id(modelId), revision(revision),
            ValidatedBody.parse(bytes, "expectedModelVersion"));
    }

    /** 按修订升序查询价格历史，不提供已发布价格的修改路由。 */
    @GetMapping("/api/v1/models/{modelId}/points-prices")
    PageDTO<PointsPriceDTO> listPointsPrices(@PathVariable String modelId, HttpServletRequest request) {
        return service.listPointsPrices(RequestContext.from(request), id(modelId), query(request));
    }

    /** 创建Purchase价格草稿，金额只接受固定刻度字符串。 */
    @PostMapping(path = "/api/v1/deployments/{deploymentId}/purchase-prices", consumes = "application/json")
    ResponseEntity<PurchasePriceDTO> createPurchasePrice(@PathVariable String deploymentId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.createPurchasePrice(context(request), id(deploymentId),
            ValidatedBody.parse(bytes, "revision", "currency", "amountPer1kInput", "amountPer1kOutput")));
    }

    /** 发布不可变价格，父版本CAS与当前指针切换使用同一事务。 */
    @PostMapping(path = "/api/v1/deployments/{deploymentId}/purchase-prices/"
        + "{revision}/publish", consumes = "application/json")
    PurchasePriceDTO publishPurchasePrice(@PathVariable String deploymentId, @PathVariable String revision,
        @RequestBody byte[] bytes,
        HttpServletRequest request) {
        return service.publishPurchasePrice(context(request), id(deploymentId), revision(revision),
            ValidatedBody.parse(bytes, "expectedDeploymentVersion"));
    }

    /** 按修订升序查询价格历史，不提供已发布价格的修改路由。 */
    @GetMapping("/api/v1/deployments/{deploymentId}/purchase-prices")
    PageDTO<PurchasePriceDTO> listPurchasePrices(@PathVariable String deploymentId, HttpServletRequest request) {
        return service.listPurchasePrices(RequestContext.from(request), id(deploymentId), query(request));
    }

    /** 首次签发201、相同收据重放200；任何响应都不能回显ProviderSecret。 */
    @PostMapping(path = "/api/v1/deployments/{deploymentId}/credentials", consumes = "application/json")
    ResponseEntity<ProviderCredentialMetadataDTO> issueCredential(@PathVariable String deploymentId,
        @RequestBody byte[] bytes, HttpServletRequest request) {
        var result = credentials.issue(context(request), id(deploymentId),
            ValidatedBody.parse(bytes, "credentialKind", "secret", "credentialRevision"), idempotency(request));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.metadata());
    }

    /** 查询凭证安全历史，不允许秘密或密文读取参数。 */
    @GetMapping("/api/v1/deployments/{deploymentId}/credentials")
    PageDTO<ProviderCredentialMetadataDTO> listCredentials(@PathVariable String deploymentId,
        HttpServletRequest request) {
        return credentials.list(RequestContext.from(request), id(deploymentId), query(request));
    }

    /** 幂等吊销凭证，当前凭证被吊销时部署安全禁用。 */
    @DeleteMapping("/api/v1/deployments/{deploymentId}/credentials/{credentialId}")
    ResponseEntity<Void> revokeCredential(@PathVariable String deploymentId, @PathVariable String credentialId,
        HttpServletRequest request) {
        requireNoBody(request);
        credentials.revoke(context(request), id(deploymentId), id(credentialId));
        return ResponseEntity.noContent().build();
    }

    /** 授予或恢复Agent-Model授权，重复操作204。 */
    @PutMapping("/api/v1/agents/{agentId}/models/{modelId}")
    ResponseEntity<Void> grant(@PathVariable String agentId, @PathVariable String modelId, HttpServletRequest request) {
        requireNoBody(request);
        boolean changed = service.changePermission(context(request), id(agentId), id(modelId), true);
        return ResponseEntity.status(changed ? 201 : 204).build();
    }

    /** 幂等撤销Agent-Model授权，不修改Employee-Agent关系。 */
    @DeleteMapping("/api/v1/agents/{agentId}/models/{modelId}")
    ResponseEntity<Void> revoke(@PathVariable String agentId, @PathVariable String modelId,
        HttpServletRequest request) {
        requireNoBody(request);
        service.changePermission(context(request), id(agentId), id(modelId), false);
        return ResponseEntity.noContent().build();
    }

    /** 分页读取Agent的模型授权历史。 */
    @GetMapping("/api/v1/agents/{agentId}/models")
    PageDTO<AgentModelPermissionDTO> permissions(@PathVariable String agentId, HttpServletRequest request) {
        return service.listPermissions(RequestContext.from(request), id(agentId), query(request));
    }

    /** 内部双身份、双ACL和目录就绪检查；服务凭证已由Core边界过滤器验证。 */
    @GetMapping("/internal/v1/catalog/models/{modelCode}/eligibility")
    CatalogEligibilityDTO eligibility(@PathVariable String modelCode, HttpServletRequest request) {
        // 1、没有正文或查询参数，运行身份只能来自两类真实Key。
        requireNoBody(request);
        if (!request.getParameterMap().isEmpty()) {
            throw ApiException.invalid();
        }
        var agentHeaders = Collections.list(request.getHeaders("Authorization"));
        var employeeHeaders = Collections.list(request.getHeaders("X-Employee-Key"));
        if (agentHeaders.size() != 1 || employeeHeaders.size() != 1) {
            throw ApiException.unauthenticated();
        }

        // 2、Core每次复查当前事实，仅返回目录候选而不授予真实出站许可。
        return service.eligibility(modelCode, agentHeaders.getFirst(), employeeHeaders.getFirst());
    }
}
