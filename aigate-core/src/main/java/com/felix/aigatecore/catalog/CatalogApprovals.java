package com.felix.aigatecore.catalog;

import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 独立安全运维注入的目录批准快照，管理HTTP字段永远不能直接增加批准事实。
 *
 * @author AIGate
 */
@Component
public class CatalogApprovals {

    /**
     * 可信批准元数据，仅从受保护文件取得，不从普通管理请求构造。
     *
     * @param approvalRef 运维批准引用
     * @param configDigest 无秘密规范配置摘要
     * @param approvedBy 独立安全运维身份
     * @param approvedAt 批准时刻
     * @param validUntil 绝对失效时刻
     * @param allowNoAuth 是否允许已批准本地部署使用NONE
     * @author AIGate
     */
    public record Approval(String approvalRef, String configDigest, String approvedBy, Instant approvedAt,
        Instant validUntil, boolean allowNoAuth) {
    }

    private final Map<String, Approval> approvals;
    private final Clock clock;

    /**
     * 启动只读加载快照，缺省清单为空；坏文件拒绝启动且不回显内容。
     *
     * @param environment Core配置来源
     * @param clock 统一UTC时钟
     */
    public CatalogApprovals(Environment environment, Clock clock) {
        this.clock = clock;
        String filename = environment.getProperty("AIGATE_CATALOG_APPROVALS_FILE", "");
        if (filename.isEmpty()) {
            approvals = Map.of();
            return;
        }
        try {
            Path path = Path.of(filename);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024 * 1024) {
                throw invalid();
            }
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw invalid();
            }
            JsonNode root = CatalogValidation.json(Files.readString(path));
            CatalogValidation.exact(root, "schemaVersion", "approvals");
            CatalogValidation.integer(root, "schemaVersion", 1, 1);
            JsonNode entries = root.get("approvals");
            if (!entries.isArray() || entries.size() > 512) {
                throw invalid();
            }
            Map<String, Approval> values = new HashMap<>();
            for (JsonNode entry : entries) {
                CatalogValidation.exact(entry, "approvalRef", "configDigest", "approvedBy", "approvedAt", "validUntil",
                    "allowNoAuth");
                String digest = CatalogValidation.text(entry, "configDigest", 64);
                Instant approvedAt = ValidatedBody.instantValue(CatalogValidation.text(entry, "approvedAt", 40));
                Instant until = ValidatedBody.instantValue(CatalogValidation.text(entry, "validUntil", 40));
                if (!digest.matches("[0-9a-f]{64}") || approvedAt.isAfter(clock.instant())
                    || !until.isAfter(approvedAt)) {
                    throw invalid();
                }
                Approval value = new Approval(CatalogValidation.text(entry, "approvalRef", 128), digest,
                    CatalogValidation.text(entry, "approvedBy", 128), approvedAt, until,
                    CatalogValidation.bool(entry, "allowNoAuth"));
                if (values.putIfAbsent(digest, value) != null) {
                    throw invalid();
                }
            }
            approvals = Map.copyOf(values);
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 固定消息不包含文件内容、位置或底层解析错误。
     */
    private static IllegalStateException invalid() {
        return new IllegalStateException("A protected, valid Catalog approval manifest is required");
    }

    /**
     * 生成稳定的无秘密配置摘要，创建前即可预审批，不依赖自增deploymentId。
     *
     * @param provider 当前供应商和HostPolicy
     * @param model 不可变逻辑模型身份
     * @param deployment 当前候选部署配置
     * @param now 当前服务端时刻
     * @return SHA-256小写十六进制摘要
     */
    public static String digest(ProviderDO provider, LogicalModelDO model, DeploymentDO deployment, Instant now) {
        JsonNode policy = CatalogValidation.hostPolicy(CatalogValidation.json(provider.baseUrlHostPolicy()),
            provider.hostingMode());
        JsonNode stored = CatalogValidation.json(deployment.adapterCompatibilityJson());
        if (!stored.has("deploymentId") || !stored.get("deploymentId").isIntegralNumber()
            || stored.get("deploymentId").longValue() != deployment.id()) {
            throw ApiException.invalid();
        }
        JsonNode adapter = CatalogValidation.adapter(stored, model, provider.hostingMode(),
            deployment.capabilityRevision(),
            deployment.adapterEvidenceRef(), now, true);
        ObjectNode canonical = CatalogValidation.newObject().put("schemaVersion", 1)
            .put("providerCode", provider.code())
            .put("hostingMode", provider.hostingMode()).put("modelCode", model.modelCode())
            .put("modelFamily", model.modelFamily()).put("immutableModelRevision", model.immutableModelRevision());
        canonical.set("hostPolicy", policy);
        canonical.put("endpointUri", deployment.endpointUri()).put("remoteModelName", deployment.remoteModelName())
            .put("regionCode", deployment.regionCode()).put("capabilityRevision", deployment.capabilityRevision());
        canonical.set("adapterCompatibility", adapter);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Catalog digest is unavailable");
        }
    }

    /**
     * 只接受当前精确配置的尚未失效批准，HTTP的自报引用和时间不能单独放行。
     *
     * @param provider 当前供应商
     * @param model 当前模型
     * @param deployment 当前部署
     * @return 匹配的批准，未批准或失效时为空
     */
    public Approval find(ProviderDO provider, LogicalModelDO model, DeploymentDO deployment) {
        try {
            Instant now = clock.instant();
            Approval approval = approvals.get(digest(provider, model, deployment, now));
            Instant verifiedAt = ValidatedBody
                .instantValue(CatalogValidation.json(deployment.adapterCompatibilityJson())
                    .get("verifiedAt").asString());
            return approval != null && !now.isBefore(approval.approvedAt()) && now.isBefore(approval.validUntil())
                && !verifiedAt.isAfter(approval.approvedAt())
                    ? approval
                    : null;
        } catch (ApiException failure) {
            // 持久配置不满足当前封闭协议时，不能把旧批准或相近摘要拿来替代。
            return null;
        }
    }
}
