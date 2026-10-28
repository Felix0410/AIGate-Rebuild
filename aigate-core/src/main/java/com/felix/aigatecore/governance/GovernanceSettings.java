package com.felix.aigatecore.governance;

import com.felix.aigatecore.security.CatalogSecuritySettings;
import com.felix.aigatecore.security.RuntimeSecuritySettings;
import com.felix.aigatecore.security.SecuritySettings;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Core独占的治理审计HMAC材料；不与身份、Provider或未来Runtime摘要用途共用。 */
@Component
public class GovernanceSettings {
    private final byte[] key;
    private final String scannerVersion;
    private final long budgetNanos;

    /**
     * 校验独立密钥和有界扫描预算，不在错误中打印配置。
     *
     * @param environment Core配置
     * @param management 当前/历史管理Pepper
     * @param runtime 当前/历史运行Pepper
     * @param catalog 当前/历史Provider密钥
     */
    public GovernanceSettings(Environment environment, SecuritySettings management, RuntimeSecuritySettings runtime,
        CatalogSecuritySettings catalog) {
        try {
            key = Base64.getDecoder().decode(environment.getRequiredProperty("AIGATE_GOVERNANCE_AUDIT_HMAC_KEY"));
            String id = environment.getRequiredProperty("AIGATE_GOVERNANCE_AUDIT_HMAC_KEY_ID");
            int millis = Integer.parseInt(environment.getProperty("AIGATE_GOVERNANCE_SCANNER_BUDGET_MS", "200"));
            if (key.length != 32 || !id.matches("[A-Za-z0-9_-]{1,24}") || millis < 1 || millis > 200
                || management.usesPepper(key) || runtime.usesPepper(key) || catalog.usesMaterial(key)) {
                throw new IllegalArgumentException();
            }
            scannerVersion = "GOVERNANCE_BUILTIN_V1:" + id;
            budgetNanos = millis * 1_000_000L;
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                "Independent Governance audit key and scanner budget 1..200ms are required");
        }
    }

    byte[] digest(byte[] bytes) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update("AIGATE:GOVERNANCE:AUDIT:V1\n".getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(bytes);
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Governance HMAC unavailable");
        }
    }

    String scannerVersion() {
        return scannerVersion;
    }

    long budgetNanos() {
        return budgetNanos;
    }
}
