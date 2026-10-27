package com.felix.aigatecore.catalog;

import com.aigate.contracts.dto.AgentDTO;
import com.aigate.contracts.dto.AgentModelPermissionDTO;
import com.aigate.contracts.dto.CatalogEligibilityDTO;
import com.aigate.contracts.dto.CatalogEligibilityDTO.EligibleDeployment;
import com.aigate.contracts.dto.DeploymentDTO;
import com.aigate.contracts.dto.LogicalModelDTO;
import com.aigate.contracts.dto.PageDTO;
import com.aigate.contracts.dto.PointsPriceDTO;
import com.aigate.contracts.dto.ProviderDTO;
import com.aigate.contracts.dto.PurchasePriceDTO;
import com.felix.aigatecore.identity.IdentityMapper;
import com.felix.aigatecore.identity.IdentityService;
import com.felix.aigatecore.identity.RequestContext;
import com.felix.aigatecore.runtime.RuntimeIdentityMapper;
import com.felix.aigatecore.runtime.RuntimeIdentityService;
import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模型目录、不可变双价格及Agent-Model授权；P3不创建资金、队列或出站许可。
 *
 * @author AIGate
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class CatalogService {

    private final CatalogMapper mapper;
    private final IdentityMapper identities;
    private final IdentityService management;
    private final RuntimeIdentityMapper runtimeMapper;
    private final RuntimeIdentityService runtime;
    private final CatalogEndpointValidator endpoints;
    private final CatalogApprovals approvals;
    private final ProviderCredentialCryptography cryptography;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 注入目录存储、当前身份检查、端点/批准校验及可替换UTC时钟。
     *
     * @param mapper Catalog存储
     * @param identities 组织和审计存储
     * @param management 管理身份与角色检查
     * @param runtimeMapper Agent存储
     * @param runtime 双运行身份检查
     * @param endpoints 端点和DNS校验
     * @param approvals 受保护批准快照
     * @param cryptography 凭证完整性校验
     * @param clock 服务端时钟
     */
    public CatalogService(CatalogMapper mapper, IdentityMapper identities, IdentityService management,
        RuntimeIdentityMapper runtimeMapper, RuntimeIdentityService runtime, CatalogEndpointValidator endpoints,
        CatalogApprovals approvals, ProviderCredentialCryptography cryptography, Clock clock) {
        this.mapper = mapper;
        this.identities = identities;
        this.management = management;
        this.runtimeMapper = runtimeMapper;
        this.runtime = runtime;
        this.endpoints = endpoints;
        this.approvals = approvals;
        this.cryptography = cryptography;
        this.clock = clock;
    }

    /** 统一截断为数据库可无损保存的微秒，不能靠系统默认时区推断UTC。 */
    Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** 管理写与P1/P2使用同一协调锁，拿锁后复查实际角色，必须在当前事务内调用。 */
    long writeAdministrator(RequestContext context) {
        if (identities.lockBootstrapControl() == null) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Identity state is unavailable");
        }
        return management.requireGlobalAdministrator(context);
    }

    /** 只按当前数据库中的mgt_、员工、Team及全局角色认证管理读取。 */
    void readAdministrator(RequestContext context) {
        management.requireGlobalAdministrator(context);
    }

    /** 审计与业务同事务，只保留实际修改字段名，不能把配置或秘密放进JSON。 */
    void audit(long actor, String action, String target, long id, List<String> fields, RequestContext context) {
        identities.insertAuditEvent(new HashMap<>(Map.of("actorType", "EMPLOYEE", "actorId", actor,
            "action", action, "targetType", target, "targetId", id, "changedFields", json.writeValueAsString(fields),
            "traceId", context.requestId())));
    }

    /** 检查回填主键；JDBC回填异常必须回滚，不能生成假成功响应。 */
    long generatedId(Map<String, Object> values) {
        Object id = values.get("id");
        if (!(id instanceof Number number) || number.longValue() <= 0) {
            throw new IllegalStateException("Invalid generated Catalog identity");
        }
        return number.longValue();
    }

    /** 读取供应商内部记录，写操作必须按当前事务持有父行锁。 */
    ProviderDO provider(long id, boolean lock) {
        ProviderDO value = lock ? mapper.lockProvider(id) : mapper.selectProvider(id);
        if (value == null) {
            throw ApiException.missing();
        }
        return value;
    }

    /** 读取固定模型身份，写操作不能在陈旧版本上覆盖。 */
    LogicalModelDO model(long id, boolean lock) {
        LogicalModelDO value = lock ? mapper.lockModel(id) : mapper.selectModel(id);
        if (value == null) {
            throw ApiException.missing();
        }
        return value;
    }

    /** 读取部署；凭证、采购价和状态改变先锁部署再处理其子记录。 */
    DeploymentDO deployment(long id, boolean lock) {
        DeploymentDO value = lock ? mapper.lockDeployment(id) : mapper.selectDeployment(id);
        if (value == null) {
            throw ApiException.missing();
        }
        return value;
    }

    /** 复用列表白名单及分页上限，先校验再交给SQL过滤和COUNT。 */
    Map<String, Object> page(Map<String, String> input, Set<String> allowed) {
        if (!allowed.containsAll(input.keySet())) {
            throw ApiException.invalid();
        }
        long page = ValidatedBody.pathId(input.getOrDefault("page", "1"), false);
        long size = ValidatedBody.pathId(input.getOrDefault("size", "20"), false);
        if (page > Integer.MAX_VALUE || size > 100) {
            throw ApiException.invalid();
        }
        return new HashMap<>(Map.of("page", (int) page, "size", (int) size, "offset", (page - 1) * size));
    }

    /** 保持响应中的page/size与SQL使用的同一组规范参数一致。 */
    <T> PageDTO<T> result(List<T> items, Map<String, Object> query, long total) {
        return new PageDTO<>(items, (int) query.get("page"), (int) query.get("size"), total);
    }

    /** 枚举条件不允许模糊匹配或未知值。 */
    private void filter(Map<String, String> input, Map<String, Object> query, String name, Set<String> allowed) {
        if (input.containsKey(name)) {
            if (!allowed.contains(input.get(name))) {
                throw ApiException.invalid();
            }
            query.put(name, input.get(name));
        }
    }

    /** 只把允许公开的供应商字段投影到DTO。 */
    private ProviderDTO dto(ProviderDO value) {
        return new ProviderDTO(value.id(), value.code(), value.name(), value.hostingMode(), value.status(),
            value.version(),
            value.createdAt(), value.updatedAt());
    }

    /** 能力JSON必须经过固定schema校验，不能把数据库中的任意对象透传。 */
    private LogicalModelDTO dto(LogicalModelDO value) {
        return new LogicalModelDTO(value.id(), value.modelCode(), value.displayName(), value.modelFamily(),
            value.immutableModelRevision(),
            CatalogValidation.capability(CatalogValidation.json(value.capabilityProfileJson())),
            value.status(), value.activePointsPriceVersionId(), value.activeRoutingRevisionId(), value.version(),
            value.createdAt(), value.updatedAt());
    }

    /** 元数据只暴露当前凭证可用布尔值及批准布尔值，不暴露Secret或其加密材料。 */
    private DeploymentDTO dto(DeploymentDO value) {
        ProviderDO provider = provider(value.providerId(), false);
        LogicalModelDO model = model(value.logicalModelId(), false);
        return new DeploymentDTO(value.id(), value.logicalModelId(), value.providerId(), value.deploymentCode(),
            value.remoteModelName(), value.hostingMode(), value.regionCode(), value.capabilityRevision(),
            value.costModel(),
            value.status(), value.routingPriority(), value.routingWeight(), value.maxConcurrent(),
            value.capacityPoolId(),
            value.activePurchasePriceVersionId(), credentialUsable(value, provider, model),
            approvals.find(provider, model, value) != null, value.version(), value.createdAt(), value.updatedAt());
    }

    /** 检查凭证归属、密文完整性和NONE的独立批准，不能因当前指针非空就宣称可用。 */
    private boolean credentialUsable(DeploymentDO deployment, ProviderDO provider, LogicalModelDO model) {
        if (deployment.activeCredentialId() == null) {
            return false;
        }
        ProviderCredentialDO credential = mapper.selectCredential(deployment.activeCredentialId());
        if (credential == null || credential.deploymentId() != deployment.id() || !cryptography.usable(credential)) {
            return false;
        }
        return !"NONE".equals(credential.credentialKind()) || allowsNoAuth(deployment, provider, model);
    }

    /** NONE仅适用于精确批准且allowNoAuth=true的SELF_HOSTED部署。 */
    boolean allowsNoAuth(DeploymentDO deployment, ProviderDO provider, LogicalModelDO model) {
        var approval = approvals.find(provider, model, deployment);
        return "SELF_HOSTED".equals(provider.hostingMode()) && approval != null && approval.allowNoAuth();
    }

    /** 价格指针必须指向同一父对象的真实PUBLISHED版本，绝不借用别人的价格。 */
    private CatalogPriceDO currentPrice(boolean points, long owner, Long id) {
        if (id == null) {
            return null;
        }
        CatalogPriceDO price = mapper.selectPriceById(Map.of("points", points, "id", id));
        return price != null && price.ownerId() == owner && "PUBLISHED".equals(price.status()) ? price : null;
    }

    /** 检查所有P3目录前提；任何缺失都不会变成P7的出站许可。 */
    private boolean ready(DeploymentDO deployment, ProviderDO provider, LogicalModelDO model) {
        if (!"ACTIVE".equals(provider.status()) || !"ACTIVE".equals(model.status())
            || approvals.find(provider, model, deployment) == null || !credentialUsable(deployment, provider, model)
            || currentPrice(true, model.id(), model.activePointsPriceVersionId()) == null
            || ("THIRD_PARTY_API".equals(deployment.hostingMode())
                && currentPrice(false, deployment.id(), deployment.activePurchasePriceVersionId()) == null)) {
            return false;
        }
        try {
            endpoints.check(deployment.endpointUri(), CatalogValidation.json(provider.baseUrlHostPolicy()),
                provider.hostingMode());
            return true;
        } catch (ApiException failure) {
            if (failure.status() == 503) {
                throw failure;
            }
            return false;
        }
    }

    /** 提取实际提交的字段名作为审计内容，值及原正文不进入审计。 */
    private List<String> fields(ValidatedBody body, String... candidates) {
        return java.util.Arrays.stream(candidates).filter(body::has).toList();
    }

    /** 拒绝空PATCH以及无意义的expectedVersion-only更新。 */
    private void requireChanges(ValidatedBody body, String... candidates) {
        if (fields(body, candidates).isEmpty()) {
            throw ApiException.invalid();
        }
    }

    /** 路由数值必须在INT范围内，不能让Long到int的转换静默溢出。 */
    private int integer(ValidatedBody body, String field, int min, int max, int fallback) {
        if (!body.has(field)) {
            return fallback;
        }
        long value = body.number(field, min == 0);
        if (value < min || value > max) {
            throw ApiException.invalid();
        }
        return (int) value;
    }

    /**
     * 登记供应商和封闭HostPolicy，声明不等于独立批准。
     *
     * @param context 当前管理请求
     * @param body 已检查字段白名单的请求正文
     * @return 经过权限和字段检查的安全响应
     */
    public ProviderDTO createProvider(RequestContext context, ValidatedBody body) {
        // 1、复查管理员，封闭校验候选HostPolicy，HTTP声明不产生批准事实。
        long actor = writeAdministrator(context);
        String mode = body.rawText("hostingMode", 24);
        JsonNode policy = CatalogValidation.hostPolicy(body.object("baseUrlHostPolicy"), mode);
        Map<String, Object> values = new HashMap<>(Map.of("code", CatalogValidation.code(body.rawText("code", 64), 64),
            "name", body.text("name", 100), "hostingMode", mode, "policy", policy.toString(), "now", now()));

        // 2、记录与审计一起提交，只返回安全DTO。
        mapper.insertProvider(values);
        long id = generatedId(values);
        audit(actor, "PROVIDER_CREATED", "PROVIDER", id, List.of("code", "name", "hostingMode", "baseUrlHostPolicy"),
            context);
        return dto(provider(id, false));
    }

    /**
     * 读取供应商安全元数据。
     *
     * @param context 当前管理请求
     * @param id 目标资源主键
     * @return 经过权限和字段检查的安全响应
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ProviderDTO getProvider(RequestContext context, long id) {
        readAdministrator(context);
        return dto(provider(id, false));
    }

    /**
     * 按允许条件稳定分页查询供应商。
     *
     * @param context 当前管理请求
     * @param input 单值列表查询参数
     * @return 经过权限和字段检查的安全响应
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<ProviderDTO> listProviders(RequestContext context, Map<String, String> input) {
        readAdministrator(context);
        Map<String, Object> query = page(input, Set.of("page", "size", "status", "hostingMode"));
        filter(input, query, "status", Set.of("ACTIVE", "DISABLED"));
        filter(input, query, "hostingMode", Set.of("THIRD_PARTY_API", "SELF_HOSTED"));
        return result(mapper.listProviders(query).stream().map(this::dto).toList(), query,
            mapper.countProviders(query));
    }

    /**
     * CAS更新供应商，安全策略不得使ACTIVE部署绕过批准。
     *
     * @param context 当前管理请求
     * @param id 目标资源主键
     * @param body 已检查字段白名单的请求正文
     * @return 经过权限和字段检查的安全响应
     */
    public ProviderDTO updateProvider(RequestContext context, long id, ValidatedBody body) {
        // 1、锁父行并检查CAS；code与hostingMode不在HTTP可变字段中。
        long actor = writeAdministrator(context);
        ProviderDO previous = provider(id, true);
        requireChanges(body, "name", "status", "baseUrlHostPolicy");
        long expected = body.number("expectedVersion", true);
        if (expected != previous.version()) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        String policy = body.has("baseUrlHostPolicy")
            ? CatalogValidation.hostPolicy(body.object("baseUrlHostPolicy"), previous.hostingMode()).toString()
            : previous.baseUrlHostPolicy();
        String name = body.has("name") ? body.text("name", 100) : previous.name();
        String status = body.has("status") ? body.status() : previous.status();

        // 2、策略变更锁定受影响ACTIVE部署；不能悄悄使原批准失效或扩大出站范围。
        if (body.has("baseUrlHostPolicy")) {
            ProviderDO candidate = new ProviderDO(id, previous.code(), name, previous.hostingMode(), status, policy,
                expected, previous.createdAt(), previous.updatedAt());
            for (DeploymentDO deployment : mapper.lockActiveProviderDeployments(id)) {
                try {
                    endpoints.check(deployment.endpointUri(), CatalogValidation.json(policy), previous.hostingMode());
                    if (approvals.find(candidate, model(deployment.logicalModelId(), false), deployment) == null) {
                        throw ApiException.conflict("HOST_POLICY_CONFLICT");
                    }
                } catch (ApiException failure) {
                    if (failure.status() == 503) {
                        throw failure;
                    }
                    throw ApiException.conflict("HOST_POLICY_CONFLICT");
                }
            }
        }
        if (mapper.updateProvider(Map.of("id", id, "name", name, "status", status, "policy", policy,
            "expectedVersion", expected, "now", now())) != 1) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        audit(actor, "PROVIDER_UPDATED", "PROVIDER", id, fields(body, "name", "status", "baseUrlHostPolicy"), context);
        return dto(provider(id, false));
    }

    /**
     * 创建不可变逻辑模型身份和固定能力。
     *
     * @param context 当前管理请求
     * @param body 已检查字段白名单的请求正文
     * @return 经过权限和字段检查的安全响应
     */
    public LogicalModelDTO createModel(RequestContext context, ValidatedBody body) {
        long actor = writeAdministrator(context);
        // 固定身份和能力在创建时完整校验，后续PATCH不提供替换入口。
        JsonNode profile = CatalogValidation.capabilityJson(body.object("capabilityProfile"));
        Map<String, Object> values = new HashMap<>(Map.of("modelCode",
            CatalogValidation.code(body.rawText("modelCode", 96), 96), "displayName", body.text("displayName", 100),
            "modelFamily", body.text("modelFamily", 100), "immutableModelRevision",
            body.text("immutableModelRevision", 100),
            "profile", profile.toString(), "now", now()));

        // 创建与审计在同一事务提交，返回固定能力DTO而非内部DO。
        mapper.insertModel(values);
        long id = generatedId(values);
        audit(actor, "MODEL_CREATED", "LOGICAL_MODEL", id,
            List.of("modelCode", "displayName", "modelFamily", "immutableModelRevision", "capabilityProfile"), context);
        return dto(model(id, false));
    }

    /**
     * 读取模型身份及固定能力。
     *
     * @param context 当前管理请求
     * @param id 目标资源主键
     * @return 经过权限和字段检查的安全响应
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public LogicalModelDTO getModel(RequestContext context, long id) {
        readAdministrator(context);
        return dto(model(id, false));
    }

    /**
     * 稳定分页查询模型。
     *
     * @param context 当前管理请求
     * @param input 单值列表查询参数
     * @return 经过权限和字段检查的安全响应
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<LogicalModelDTO> listModels(RequestContext context, Map<String, String> input) {
        readAdministrator(context);
        Map<String, Object> query = page(input, Set.of("page", "size", "status"));
        filter(input, query, "status", Set.of("ACTIVE", "DISABLED"));
        return result(mapper.listModels(query).stream().map(this::dto).toList(), query, mapper.countModels(query));
    }

    /**
     * 只更新名称和状态，模型真实身份不可暗换。
     *
     * @param context 当前管理请求
     * @param id 目标资源主键
     * @param body 已检查字段白名单的请求正文
     * @return 经过权限和字段检查的安全响应
     */
    public LogicalModelDTO updateModel(RequestContext context, long id, ValidatedBody body) {
        long actor = writeAdministrator(context);
        LogicalModelDO previous = model(id, true);
        requireChanges(body, "displayName", "status");
        long expected = body.number("expectedVersion", true);
        if (expected != previous.version()) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        if (mapper.updateModel(Map.of("id", id, "displayName",
            body.has("displayName") ? body.text("displayName", 100) : previous.displayName(), "status",
            body.has("status") ? body.status() : previous.status(), "expectedVersion", expected, "now", now())) != 1) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        audit(actor, "MODEL_UPDATED", "LOGICAL_MODEL", id, fields(body, "displayName", "status"), context);
        return dto(model(id, false));
    }

    /**
     * 登记DRAFT部署并回填服务端主键，保持真实模型和适配版本绑定。
     *
     * @param context 当前管理请求
     * @param modelId 逻辑模型主键
     * @param body 已检查字段白名单的请求正文
     * @return 经过权限和字段检查的安全响应
     */
    public DeploymentDTO createDeployment(RequestContext context, long modelId, ValidatedBody body) {
        // 1、管理写锁保护跨对象一致性，provider与model由真实外键引用且必须ACTIVE。
        long actor = writeAdministrator(context);
        ProviderDO provider = provider(body.number("providerId", false), true);
        LogicalModelDO model = model(modelId, true);
        if (!"ACTIVE".equals(provider.status()) || !"ACTIVE".equals(model.status())) {
            throw ApiException.conflict("INVALID_STATE");
        }
        String revision = body.rawText("capabilityRevision", 96);
        String evidence = body.rawText("adapterEvidenceRef", 256);
        JsonNode adapter = CatalogValidation.adapter(body.object("adapterCompatibility"), model, provider.hostingMode(),
            revision, evidence, now(), false);
        String endpoint = endpoints.check(body.rawText("endpointUri", 2048),
            CatalogValidation.json(provider.baseUrlHostPolicy()), provider.hostingMode());

        // 2、只登记DRAFT；初次插入后立即在同事务绑定服务端ID，客户端不能预占或替换ID。
        Map<String, Object> values = new HashMap<>(Map.of("modelId", modelId, "providerId", provider.id(),
            "deploymentCode", CatalogValidation.code(body.rawText("deploymentCode", 96), 96), "endpointUri", endpoint,
            "remoteModelName", body.rawText("remoteModelName", 128), "hostingMode", provider.hostingMode(),
            "regionCode", body.rawText("regionCode", 32), "capabilityRevision", revision, "adapter", adapter.toString(),
            "evidence", evidence));
        values.put("costModel",
            "THIRD_PARTY_API".equals(provider.hostingMode()) ? "PER_TOKEN_API" : "INTERNAL_RESOURCE");
        values.put("routingPriority", integer(body, "routingPriority", 0, Integer.MAX_VALUE, 100));
        values.put("routingWeight", integer(body, "routingWeight", 1, 100, 1));
        values.put("maxConcurrent", integer(body, "maxConcurrent", 1, Integer.MAX_VALUE, -1));
        if ((int) values.get("maxConcurrent") < 1) {
            throw ApiException.invalid();
        }
        values.put("now", now());
        mapper.insertDeployment(values);
        long id = generatedId(values);
        if (mapper.bindDeploymentIdentity(Map.of("id", id)) != 1) {
            throw new IllegalStateException("Deployment identity binding failed");
        }
        audit(actor, "DEPLOYMENT_CREATED", "DEPLOYMENT", id, List.of("logicalModelId", "providerId", "deploymentCode",
            "endpointUri", "remoteModelName", "regionCode", "capabilityRevision", "adapterCompatibility",
            "adapterEvidenceRef"),
            context);
        return dto(deployment(id, false));
    }

    /**
     * 读取不含实际端点和秘密的部署元数据。
     *
     * @param context 当前管理请求
     * @param id 目标资源主键
     * @return 经过权限和字段检查的安全响应
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public DeploymentDTO getDeployment(RequestContext context, long id) {
        readAdministrator(context);
        return dto(deployment(id, false));
    }

    /**
     * 分页查询指定模型的部署。
     *
     * @param context 当前管理请求
     * @param modelId 逻辑模型主键
     * @param input 单值列表查询参数
     * @return 经过权限和字段检查的安全响应
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<DeploymentDTO> listDeployments(RequestContext context, long modelId, Map<String, String> input) {
        readAdministrator(context);
        model(modelId, false);
        Map<String, Object> query = page(input, Set.of("page", "size", "status"));
        filter(input, query, "status", Set.of("DRAFT", "ACTIVE", "DISABLED", "MAINTENANCE"));
        query.put("modelId", modelId);
        return result(mapper.listDeployments(query).stream().map(this::dto).toList(), query,
            mapper.countDeployments(query));
    }

    /**
     * CAS更新部署状态和路由元数据，ACTIVE必须复核当前全部目录条件。
     *
     * @param context 当前管理请求
     * @param id 目标资源主键
     * @param body 已检查字段白名单的请求正文
     * @return 经过权限和字段检查的安全响应
     */
    public DeploymentDTO updateDeployment(RequestContext context, long id, ValidatedBody body) {
        // 1、CAS保护状态和路由元数据，端点、真实模型、地区和适配修订不可就地更换。
        long actor = writeAdministrator(context);
        DeploymentDO previous = deployment(id, true);
        requireChanges(body, "status", "routingPriority", "routingWeight", "maxConcurrent");
        long expected = body.number("expectedVersion", true);
        if (expected != previous.version()) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        String status = body.has("status") ? body.rawText("status", 16) : previous.status();
        if (!Set.of("DRAFT", "ACTIVE", "DISABLED", "MAINTENANCE").contains(status)) {
            throw ApiException.invalid();
        }

        // 2、每次进入或保持ACTIVE都复核当前批准、密钥、价格、Provider状态与DNS。
        if ("ACTIVE".equals(status) && !ready(previous, provider(previous.providerId(), false),
            model(previous.logicalModelId(), false))) {
            throw ApiException.conflict("CATALOG_NOT_READY");
        }
        if (mapper.updateDeployment(Map.of("id", id, "status", status, "expectedVersion", expected, "now", now(),
            "routingPriority", integer(body, "routingPriority", 0, Integer.MAX_VALUE, previous.routingPriority()),
            "routingWeight", integer(body, "routingWeight", 1, 100, previous.routingWeight()),
            "maxConcurrent", integer(body, "maxConcurrent", 1, Integer.MAX_VALUE, previous.maxConcurrent()))) != 1) {
            throw ApiException.conflict("VERSION_CONFLICT");
        }
        audit(actor, "DEPLOYMENT_UPDATED", "DEPLOYMENT", id,
            fields(body, "status", "routingPriority", "routingWeight", "maxConcurrent"), context);
        return dto(deployment(id, false));
    }

    /** 两种价格共享内部校验与事务流程，公开DTO及数据库表仍独立。 */
    private Map<String, Object> priceQuery(boolean points, long owner) {
        return new HashMap<>(Map.of("points", points, "ownerId", owner));
    }

    /** 采购价只属于第三方部署，不能给本地资源虚构供应商货币成本。 */
    private long lockPriceOwner(boolean points, long owner) {
        if (points) {
            return model(owner, true).version();
        }
        DeploymentDO deployment = deployment(owner, true);
        if (!"THIRD_PARTY_API".equals(deployment.hostingMode())) {
            throw ApiException.invalid();
        }
        return deployment.version();
    }

    /** 积分价格只投影固定六位字符串，不使用浮点或隐式舍入。 */
    private PointsPriceDTO pointsPrice(CatalogPriceDO value) {
        return new PointsPriceDTO(value.id(), value.revision(), value.status(),
            value.inputPrice().setScale(6, RoundingMode.UNNECESSARY).toPlainString(),
            value.outputPrice().setScale(6, RoundingMode.UNNECESSARY).toPlainString(), value.effectiveFrom(),
            value.publishedAt());
    }

    /** 采购价保持独立币种与八位小数，不能套用员工积分金额。 */
    private PurchasePriceDTO purchasePrice(CatalogPriceDO value) {
        return new PurchasePriceDTO(value.id(), value.revision(), value.status(), value.currency(),
            value.inputPrice().setScale(8, RoundingMode.UNNECESSARY).toPlainString(),
            value.outputPrice().setScale(8, RoundingMode.UNNECESSARY).toPlainString(), value.effectiveFrom(),
            value.publishedAt());
    }

    /**
     * 创建独立积分价或采购原币种价格草稿。
     *
     * @param context 当前管理请求
     * @param points 是否处理模型积分价，false为部署采购价
     * @param owner 所属模型或部署主键
     * @param body 已检查字段白名单的请求正文
     * @return 经过权限和字段检查的安全响应
     */
    private CatalogPriceDO createPrice(RequestContext context, boolean points, long owner, ValidatedBody body) {
        long actor = writeAdministrator(context);
        lockPriceOwner(points, owner);

        // 精确校验修订、字符串价格与独立币种，禁止在写库时靠四舍五入修正输入。
        long revision = body.number("revision", false);
        if (revision > Integer.MAX_VALUE) {
            throw ApiException.invalid();
        }
        int scale = points ? 6 : 8;
        Map<String, Object> values = priceQuery(points, owner);
        values.put("revision", (int) revision);
        values.put("inputPrice",
            CatalogValidation.decimal(body.rawText(points ? "pointPer1kInput" : "amountPer1kInput", 32), scale));
        values.put("outputPrice",
            CatalogValidation.decimal(body.rawText(points ? "pointPer1kOutput" : "amountPer1kOutput", 32), scale));
        if (!points) {
            String currency = body.rawText("currency", 3);
            if (!currency.matches("[A-Z]{3}")) {
                throw ApiException.invalid();
            }
            values.put("currency", currency);
        }
        values.put("actorId", actor);
        values.put("now", now());

        // 草稿不切父当前指针，发布由独立CAS动作完成。
        mapper.insertPrice(values);
        audit(actor, points ? "POINTS_PRICE_CREATED" : "PURCHASE_PRICE_CREATED",
            points ? "LOGICAL_MODEL" : "DEPLOYMENT",
            owner, List.of("revision", points ? "pointPer1kInput" : "amountPer1kInput",
                points ? "pointPer1kOutput" : "amountPer1kOutput"),
            context);
        return mapper.selectPrice(values);
    }

    /**
     * 以父版本CAS发布价格，并在同一事务切换当前指针。
     *
     * @param context 当前管理请求
     * @param points 是否处理模型积分价，false为部署采购价
     * @param owner 所属模型或部署主键
     * @param revision 待发布修订号
     * @param body 已检查字段白名单的请求正文
     * @return 经过权限和字段检查的安全响应
     */
    private CatalogPriceDO publishPrice(RequestContext context, boolean points, long owner, int revision,
        ValidatedBody body) {
        // 1、价格发布以父资源版本CAS决定先后，草稿创建不切换当前价格。
        long actor = writeAdministrator(context);
        long actual = lockPriceOwner(points, owner);
        long expected = body.number(points ? "expectedModelVersion" : "expectedDeploymentVersion", true);
        if (actual != expected) {
            throw ApiException.conflict("PRICE_VERSION_CONFLICT");
        }
        Map<String, Object> values = priceQuery(points, owner);
        values.put("revision", revision);
        CatalogPriceDO price = mapper.selectPrice(values);
        if (price == null) {
            throw ApiException.missing();
        }
        if (!"DRAFT".equals(price.status())) {
            throw ApiException.conflict("INVALID_STATE");
        }

        // 2、发布事实、父当前指针和审计一起提交；任何失败都不能留下半发布状态。
        values.put("id", price.id());
        values.put("priceId", price.id());
        values.put("expectedVersion", expected);
        values.put("actorId", actor);
        values.put("now", now());
        if (mapper.publishPrice(values) != 1
            || (points ? mapper.pointPricePointer(values) : mapper.purchasePricePointer(values)) != 1) {
            throw ApiException.conflict("PRICE_VERSION_CONFLICT");
        }
        audit(actor, points ? "POINTS_PRICE_PUBLISHED" : "PURCHASE_PRICE_PUBLISHED",
            points ? "LOGICAL_MODEL" : "DEPLOYMENT",
            owner, List.of("revision", points ? "activePointsPriceVersionId" : "activePurchasePriceVersionId"),
            context);
        return mapper.selectPrice(values);
    }

    /**
     * 按revision升序读取不可变价格历史。
     *
     * @param context 当前管理请求
     * @param points 是否处理模型积分价，false为部署采购价
     * @param owner 所属模型或部署主键
     * @param input 单值列表查询参数
     * @return 经过权限和字段检查的安全响应
     */
    private <T> PageDTO<T> listPrices(RequestContext context, boolean points, long owner, Map<String, String> input,
        Function<CatalogPriceDO, T> conversion) {
        readAdministrator(context);
        if (points) {
            model(owner, false);
        } else {
            deployment(owner, false);
        }
        Map<String, Object> query = page(input, Set.of("page", "size"));
        query.putAll(priceQuery(points, owner));
        return result(mapper.listPrices(query).stream().map(conversion).toList(), query,
            mapper.countPrices(query));
    }

    /**
     * 创建未发布的模型积分价。
     *
     * @param context 当前管理请求
     * @param owner 所属模型或部署主键
     * @param body 封闭字段请求
     * @return 强类型安全价格响应
     */
    public PointsPriceDTO createPointsPrice(RequestContext context, long owner, ValidatedBody body) {
        return pointsPrice(createPrice(context, true, owner, body));
    }

    /**
     * 发布不可变的模型积分价。
     *
     * @param context 当前管理请求
     * @param owner 所属模型或部署主键
     * @param revision 待发布修订
     * @param body 封闭字段请求
     * @return 强类型安全价格响应
     */
    public PointsPriceDTO publishPointsPrice(RequestContext context, long owner, int revision, ValidatedBody body) {
        return pointsPrice(publishPrice(context, true, owner, revision, body));
    }

    /**
     * 分页查询模型积分价。
     *
     * @param context 当前管理请求
     * @param owner 所属模型或部署主键
     * @param input 单值分页参数
     * @return 强类型安全价格响应
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<PointsPriceDTO> listPointsPrices(RequestContext context, long owner, Map<String, String> input) {
        return listPrices(context, true, owner, input, this::pointsPrice);
    }

    /**
     * 创建未发布的部署原币种采购价。
     *
     * @param context 当前管理请求
     * @param owner 所属模型或部署主键
     * @param body 封闭字段请求
     * @return 强类型安全价格响应
     */
    public PurchasePriceDTO createPurchasePrice(RequestContext context, long owner, ValidatedBody body) {
        return purchasePrice(createPrice(context, false, owner, body));
    }

    /**
     * 发布不可变的部署原币种采购价。
     *
     * @param context 当前管理请求
     * @param owner 所属模型或部署主键
     * @param revision 待发布修订
     * @param body 封闭字段请求
     * @return 强类型安全价格响应
     */
    public PurchasePriceDTO publishPurchasePrice(RequestContext context, long owner, int revision, ValidatedBody body) {
        return purchasePrice(publishPrice(context, false, owner, revision, body));
    }

    /**
     * 分页查询部署原币种采购价。
     *
     * @param context 当前管理请求
     * @param owner 所属模型或部署主键
     * @param input 单值分页参数
     * @return 强类型安全价格响应
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<PurchasePriceDTO> listPurchasePrices(RequestContext context, long owner, Map<String, String> input) {
        return listPrices(context, false, owner, input, this::purchasePrice);
    }

    /**
     * 幂等授予、撤销或恢复Agent-Model授权，保持同一行和递增版本。
     *
     * @param context 当前管理请求
     * @param agentId Agent主键
     * @param modelId 逻辑模型主键
     * @param grant 是否授予授权
     * @return 是否发生真实授权状态切换
     */
    public boolean changePermission(RequestContext context, long agentId, long modelId, boolean grant) {
        // 1、锁定两个授权主体；授权要求ACTIVE，撤销允许清理已停用主体。
        long actor = writeAdministrator(context);
        AgentDTO agent = runtimeMapper.lockAgent(agentId);
        LogicalModelDO model = model(modelId, true);
        if (agent == null) {
            throw ApiException.missing();
        }
        var team = identities.selectTeamById(agent.ownerTeamId());
        if (grant && (!"ACTIVE".equals(agent.status()) || !"ACTIVE".equals(model.status()) || team == null
            || !"ACTIVE".equals(team.status()))) {
            throw ApiException.conflict("INVALID_STATE");
        }
        Map<String, Object> values = new HashMap<>(
            Map.of("agentId", agentId, "modelId", modelId, "actorId", actor, "now", now()));
        AgentModelPermissionDTO previous = mapper.selectPermission(values);
        if (grant
            ? previous != null && "GRANTED".equals(previous.status())
            : previous == null || "REVOKED".equals(previous.status())) {
            return false;
        }

        // 2、恢复原唯一行并增加版本，重复PUT/DELETE不写额外审计。
        if (grant) {
            mapper.grantPermission(values);
        } else {
            mapper.revokePermission(values);
        }
        audit(actor, grant ? "AGENT_MODEL_GRANTED" : "AGENT_MODEL_REVOKED", "AGENT", agentId,
            List.of("modelId", "status"), context);
        return true;
    }

    /**
     * 查询Agent的模型授权历史。
     *
     * @param context 当前管理请求
     * @param agentId Agent主键
     * @param input 单值列表查询参数
     * @return 经过权限和字段检查的安全响应
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<AgentModelPermissionDTO> listPermissions(RequestContext context, long agentId,
        Map<String, String> input) {
        readAdministrator(context);
        if (runtimeMapper.selectAgent(agentId) == null) {
            throw ApiException.missing();
        }
        Map<String, Object> query = page(input, Set.of("page", "size", "status"));
        filter(input, query, "status", Set.of("GRANTED", "REVOKED"));
        query.put("agentId", agentId);
        return result(mapper.listPermissions(query), query, mapper.countPermissions(query));
    }

    /**
     * 验证当前双身份、双ACL及目录就绪条件，不产生真实出站许可。
     *
     * @param code 不可变模型编码
     * @param agentAuthorization 完整Agent Bearer凭证头
     * @param employeeKey 员工运行凭证
     * @return 经过权限和字段检查的安全响应
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public CatalogEligibilityDTO eligibility(String code, String agentAuthorization, String employeeKey) {
        // 1、实时复用P2双身份和EmployeeAgent ACL；AgentModel ACL不能替代它。
        var identity = runtime.verify(agentAuthorization, employeeKey);
        LogicalModelDO model = mapper.selectModelByCode(CatalogValidation.code(code, 96));
        if (model == null) {
            throw ApiException.missing();
        }
        AgentModelPermissionDTO permission = mapper
            .selectPermission(Map.of("agentId", identity.agentId(), "modelId", model.id()));
        if (permission == null || !"GRANTED".equals(permission.status())) {
            throw ApiException.forbidden();
        }
        if (!"ACTIVE".equals(model.status())) {
            throw ApiException.conflict("INVALID_STATE");
        }

        // 2、候选逐项检查当前Provider、价格、批准、凭证和全部DNS答案；无Provider HTTP。
        CatalogPriceDO points = currentPrice(true, model.id(), model.activePointsPriceVersionId());
        List<EligibleDeployment> candidates = new ArrayList<>();
        if (points != null) {
            for (DeploymentDO deployment : mapper.activeModelDeployments(model.id())) {
                ProviderDO provider = provider(deployment.providerId(), false);
                if (ready(deployment, provider, model)) {
                    CatalogPriceDO purchase = currentPrice(false, deployment.id(),
                        deployment.activePurchasePriceVersionId());
                    candidates
                        .add(new EligibleDeployment(deployment.id(), deployment.hostingMode(), deployment.costModel(),
                            deployment.capabilityRevision(), purchase == null ? null : purchase.id(),
                            purchase == null ? null : purchase.currency()));
                }
            }
        }
        return new CatalogEligibilityDTO(model.id(), model.modelCode(), identity.agentId(), identity.employeeId(),
            points == null ? null : points.id(), !candidates.isEmpty(), candidates, now());
    }
}
