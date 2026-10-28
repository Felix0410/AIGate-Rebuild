package com.felix.aigatecore.governance;

import com.aigate.contracts.dto.ManagementProfileDTO;
import com.felix.aigatecore.identity.IdentityMapper;
import com.felix.aigatecore.identity.IdentityService;
import com.felix.aigatecore.identity.RequestContext;
import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 当前管理身份与安全角色；PLATFORM_ADMIN不隐含Editor或Approver。 */
@Component
public class GovernanceAccess {
    private final IdentityMapper identities;
    private final IdentityService management;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 装配既有身份及审计权威。
     *
     * @param identities 身份存储
     * @param management 当前管理凭证验证
     */
    public GovernanceAccess(IdentityMapper identities, IdentityService management) {
        this.identities = identities;
        this.management = management;
    }

    static Map<String, Object> values(Object... pairs) {
        var result = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    void coordinate() {
        if (identities.lockBootstrapControl() == null) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Identity state unavailable");
        }
    }

    ManagementProfileDTO profile(RequestContext context) {
        return management.getCurrentProfile(context);
    }

    long role(RequestContext context, String... roles) {
        var profile = profile(context);
        boolean permitted = profile.roles().stream().anyMatch(role -> "GLOBAL".equals(role.scopeType())
            && role.scopeId() == 0 && Set.of(roles).contains(role.roleCode()));
        if (!permitted) {
            throw ApiException.forbidden();
        }
        return profile.employee().id();
    }

    long viewer(RequestContext context) {
        return role(context, "PLATFORM_ADMIN", "POLICY_EDITOR", "SECURITY_APPROVER");
    }

    static String reason(ValidatedBody body) {
        String reason = body.rawText("reasonCode", 64);
        if (!reason.matches("[A-Z][A-Z0-9_-]{1,63}")) {
            throw ApiException.invalid();
        }
        return reason;
    }

    void audit(long actor, String action, String target, long id, List<String> fields, RequestContext context) {
        identities.insertAuditEvent(values("actorType", "EMPLOYEE", "actorId", actor, "action", action,
            "targetType", target, "targetId", id, "changedFields", json.writeValueAsString(fields),
            "traceId", context.requestId()));
    }

    static Map<String, Object> page(Map<String, String> input, Set<String> allowed) {
        if (!allowed.containsAll(input.keySet())) {
            throw ApiException.invalid();
        }
        long page = ValidatedBody.pathId(input.getOrDefault("page", "1"), false);
        long size = ValidatedBody.pathId(input.getOrDefault("size", "20"), false);
        if (page > Integer.MAX_VALUE || size > 100) {
            throw ApiException.invalid();
        }
        return values("page", (int) page, "size", (int) size, "offset", (page - 1) * size);
    }
}
