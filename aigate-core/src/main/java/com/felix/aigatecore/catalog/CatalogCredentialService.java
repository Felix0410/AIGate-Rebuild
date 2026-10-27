package com.felix.aigatecore.catalog;

import com.aigate.contracts.dto.PageDTO;
import com.aigate.contracts.dto.ProviderCredentialMetadataDTO;
import com.felix.aigatecore.identity.RequestContext;
import com.felix.aigatecore.security.CatalogSecuritySettings;
import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商凭证签发、一次性加密和永久收据；所有HTTP响应始终只有安全元数据。
 *
 * @author AIGate
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class CatalogCredentialService {

    /**
     * 区分首次201与原收据重放200，两者都只能返回安全元数据。
     *
     * @param metadata 凭证元数据
     * @param created 是否首次创建
     * @author AIGate
     */
    public record IssueResult(ProviderCredentialMetadataDTO metadata, boolean created) {
    }

    private final CatalogMapper mapper;
    private final CatalogService catalog;
    private final CatalogSecuritySettings settings;
    private final ProviderCredentialCryptography cryptography;

    /**
     * 注入真实存储、公共目录事务检查、隔离密钥和加密实现。
     *
     * @param mapper Catalog存储
     * @param catalog 当前管理身份、目录父记录和审计
     * @param settings 加密及收据密钥
     * @param cryptography AES-GCM与HMAC实现
     */
    public CatalogCredentialService(CatalogMapper mapper, CatalogService catalog, CatalogSecuritySettings settings,
        ProviderCredentialCryptography cryptography) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.settings = settings;
        this.cryptography = cryptography;
    }

    /** 不序列化内部DO中的密文、IV、HMAC或密钥版本。 */
    private ProviderCredentialMetadataDTO dto(ProviderCredentialDO value) {
        return new ProviderCredentialMetadataDTO(value.id(), value.deploymentId(), value.credentialKind(),
            value.credentialRevision(), value.status(), value.createdAt(), value.rotatedAt(), value.revokedAt());
    }

    /**
     * 锁父记录后检查收据，相同请求只读回原元数据，不重新加密或切换当前指针。
     *
     * @param context 当前管理请求
     * @param deploymentId URL中的部署
     * @param body 封闭校验后的请求
     * @param operationKey 签发者命名空间内的永久幂等键
     * @return 首次或重放的安全响应
     */
    public IssueResult issue(RequestContext context, long deploymentId, ValidatedBody body, String operationKey) {
        // 1、管理协调锁、部署父锁、永久收据依次检查，跨Deployment也不能复用同一Actor/Key。
        long actor = catalog.writeAdministrator(context);
        DeploymentDO deployment = catalog.deployment(deploymentId, true);
        String key = ValidatedBody.idempotency(operationKey);
        String kind = body.rawText("credentialKind", 24);
        long revision = body.number("credentialRevision", false);
        if (!Set.of("BEARER", "API_KEY_HEADER", "NONE").contains(kind) || revision > Integer.MAX_VALUE
            || ("NONE".equals(kind) && body.has("secret"))) {
            throw ApiException.invalid();
        }
        String secret = "NONE".equals(kind) ? null : body.rawText("secret", 4096);
        ProviderCredentialDO previous = mapper.selectReceipt(Map.of("actorId", actor, "key", key));
        if (previous != null) {
            byte[] candidate = cryptography.receipt(previous.issueHmacKeyId(), deploymentId, kind, (int) revision,
                secret, actor);
            if (!MessageDigest.isEqual(candidate, previous.issueRequestHmac())) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT");
            }
            return new IssueResult(dto(previous), false);
        }

        // 2、首次签发必须满足当前父对象状态，NONE额外依赖独立批准，不能自报免鉴权。
        ProviderDO provider = catalog.provider(deployment.providerId(), false);
        LogicalModelDO model = catalog.model(deployment.logicalModelId(), false);
        if (!"ACTIVE".equals(provider.status()) || !"ACTIVE".equals(model.status())) {
            throw ApiException.conflict("INVALID_STATE");
        }
        if ("NONE".equals(kind) && !catalog.allowsNoAuth(deployment, provider, model)) {
            throw ApiException.conflict("APPROVAL_REQUIRED");
        }
        Instant now = catalog.now();
        Map<String, Object> values = new HashMap<>(Map.of("deploymentId", deploymentId, "kind", kind,
            "revision", (int) revision, "actorId", actor, "key", key, "now", now));
        String hmacKeyId = settings.currentIssue().id();
        values.put("hmacKeyId", hmacKeyId);
        values.put("hmac", cryptography.receipt(hmacKeyId, deploymentId, kind, (int) revision, secret, actor));
        values.put("rotatedAt", deployment.activeCredentialId() == null ? null : now);
        values.put("encryptedSecret", null);
        values.put("iv", null);
        values.put("encryptionKeyId", null);
        if (secret != null) {
            var encrypted = cryptography.encrypt(deploymentId, kind, (int) revision, secret);
            values.put("encryptedSecret", encrypted.ciphertext());
            values.put("iv", encrypted.iv());
            values.put("encryptionKeyId", encrypted.keyId());
        }

        // 3、密文记录、当前指针与审计同事务；不能因失败响应再签发第二条。
        mapper.insertCredential(values);
        long id = catalog.generatedId(values);
        if (mapper.credentialPointer(Map.of("deploymentId", deploymentId, "credentialId", id, "now", now)) != 1) {
            throw ApiException.conflict("INVALID_STATE");
        }
        catalog.audit(actor, "PROVIDER_CREDENTIAL_ISSUED", "DEPLOYMENT", deploymentId,
            List.of("credentialKind", "credentialRevision", "activeCredentialId"), context);
        return new IssueResult(dto(mapper.selectCredential(id)), true);
    }

    /**
     * 吊销只改变当前真实状态，吊销当前凭证会安全禁用部署，绝不回退旧凭证。
     *
     * @param context 当前管理请求
     * @param deploymentId URL中的部署
     * @param credentialId URL中的凭证
     */
    public void revoke(RequestContext context, long deploymentId, long credentialId) {
        long actor = catalog.writeAdministrator(context);
        catalog.deployment(deploymentId, true);
        ProviderCredentialDO credential = mapper.lockCredential(credentialId);
        if (credential == null || credential.deploymentId() != deploymentId) {
            throw ApiException.missing();
        }
        Map<String, Object> values = Map.of("deploymentId", deploymentId, "id", credentialId, "now", catalog.now());
        if (mapper.revokeCredential(values) == 1) {
            mapper.clearCredentialPointer(values);
            catalog.audit(actor, "PROVIDER_CREDENTIAL_REVOKED", "DEPLOYMENT", deploymentId,
                List.of("credentialRevision", "revokedAt", "activeCredentialId"), context);
        }
    }

    /**
     * 分页查询凭证历史，只将明确白名单字段交给HTTP层。
     *
     * @param context 当前管理请求
     * @param deploymentId 指定部署
     * @param input 分页参数
     * @return 安全凭证元数据分页
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageDTO<ProviderCredentialMetadataDTO> list(RequestContext context, long deploymentId,
        Map<String, String> input) {
        catalog.readAdministrator(context);
        catalog.deployment(deploymentId, false);
        Map<String, Object> query = catalog.page(input, Set.of("page", "size"));
        query.put("deploymentId", deploymentId);
        return catalog.result(mapper.listCredentials(query).stream().map(this::dto).toList(), query,
            mapper.countCredentials(query));
    }
}
