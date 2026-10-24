package com.felix.aigatecore.security;

import com.felix.aigatecore.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * 安全配置与轮换窗口校验。密钥只从运行时配置读取，错误消息不回显配置原文。
 *
 * @author AIGate
 */
@Component
public class SecuritySettings {

    /**
     * 管理凭证摘要使用的独立 HMAC 密钥及其版本标识，不得序列化到外部响应或日志。
     *
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @param bytes 原始 UTF-8 JSON 字节
     * @author AIGate
     */
    public record Pepper(String id, byte[] bytes) {
    }

    private final String serviceToken;

    private final String previousServiceToken;

    private final String bootstrapMode;

    private final String bootstrapToken;

    private final Instant previousServiceUntil;

    private final Instant previousPepperUntil;

    private final Pepper current;

    private final Pepper previous;

    private final List<IpAddressMatcher> operationsSources;

    /**
     * 创建SecuritySettings，显式注入当前组件依赖或不可变状态。
     *
     * @param serviceToken 当前独立服务凭证，禁止记录其原文
     * @param previousServiceToken 仅在轮换窗口内有效的上一版服务凭证
     * @param previousServiceUntil 上一版服务凭证的截止时间
     * @param pepperId 当前管理 Pepper 的受控标识
     * @param pepper 独立 HMAC 密钥及其版本
     * @param previousPepperId 上一版管理 Pepper 的受控标识
     * @param previousPepper 上一版独立 HMAC 密钥
     * @param previousPepperUntil 上一版管理 Pepper 的截止时间
     * @param bootstrapMode OFF、INIT 或 RECOVERY 运维模式
     * @param bootstrapToken 仅用于受控运维动作的独立凭证
     * @param cidrs 运维来源 CIDR 列表，不接受主机名或客户端自报来源
     */
    public SecuritySettings(@Value("${AIGATE_SERVICE_TOKEN}") String serviceToken,
        @Value("${AIGATE_SERVICE_PREVIOUS_TOKEN:}") String previousServiceToken,
        @Value("${AIGATE_SERVICE_PREVIOUS_VALID_UNTIL:}") String previousServiceUntil,
        @Value("${AIGATE_MANAGEMENT_PEPPER_KEY_ID:mgmt-v1}") String pepperId,
        @Value("${AIGATE_MANAGEMENT_PEPPER}") String pepper,
        @Value("${AIGATE_MANAGEMENT_PREVIOUS_PEPPER_KEY_ID:}") String previousPepperId,
        @Value("${AIGATE_MANAGEMENT_PREVIOUS_PEPPER:}") String previousPepper,
        @Value("${AIGATE_MANAGEMENT_PREVIOUS_VALID_UNTIL:}") String previousPepperUntil,
        @Value("${AIGATE_BOOTSTRAP_MODE:OFF}") String bootstrapMode,
        @Value("${AIGATE_BOOTSTRAP_TOKEN:}") String bootstrapToken,
        @Value("${AIGATE_BOOTSTRAP_ALLOWED_CIDRS:}") String cidrs) {
        requireToken(serviceToken, "svc_");
        this.serviceToken = serviceToken;
        this.previousServiceToken = previousServiceToken;
        this.previousServiceUntil = optionalUntil(previousServiceUntil);
        if (!previousServiceToken.isEmpty()) {
            requireToken(previousServiceToken, "svc_");
            if (this.previousServiceUntil == null || previousServiceToken.equals(serviceToken)) {
                throw badConfiguration();
            }
        }
        this.current = pepper(pepperId, pepper);
        this.previous = previousPepper.isEmpty() ? null : pepper(previousPepperId, previousPepper);
        this.previousPepperUntil = optionalUntil(previousPepperUntil);
        if (previous != null && (previous.id().equals(current.id()) || Arrays.equals(previous.bytes(), current.bytes())
            || this.previousPepperUntil == null)) {
            throw badConfiguration();
        }
        this.bootstrapMode = bootstrapMode;
        this.bootstrapToken = bootstrapToken;
        if (!Set.of("OFF", "INIT", "RECOVERY").contains(bootstrapMode)) {
            throw badConfiguration();
        }
        this.operationsSources = cidrs.isBlank()
            ? List.of()
            : Arrays.stream(cidrs.split(",")).map(String::strip).map(source -> {
                if (!source.matches("[0-9a-fA-F:.]+/[0-9]{1,3}")) {
                    throw badConfiguration();
                }
                return new IpAddressMatcher(source);
            }).toList();
        if (!bootstrapMode.equals("OFF")) {
            requireToken(bootstrapToken, "bst_");
            if (operationsSources.isEmpty()) {
                throw badConfiguration();
            }
        }
    }

    /**
     * 创建安全配置异常，不在异常消息或异常原因中携带配置原文。
     *
     * @return 不含配置原文的安全配置异常
     */
    private static IllegalStateException badConfiguration() {
        return new IllegalStateException(
            "Invalid security configuration; explicit strong credentials and bounded rotation windows are required");
    }

    /**
     * 解析独立 HMAC Pepper 及其标识，校验至少具有 32 字节随机熵。
     *
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @param encoded 标准 Base64 编码的独立 HMAC 密钥
     * @return 校验后的 HMAC 密钥与版本
     */
    private static Pepper pepper(String id, String encoded) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (!id.matches("[A-Za-z0-9_-]{1,32}") || decoded.length < 32) {
                throw badConfiguration();
            }
            return new Pepper(id, decoded);
        } catch (IllegalArgumentException failure) {
            throw badConfiguration();
        }
    }

    /**
     * 解析可选轮换窗口截止时间；不允许无截止时间地接受旧凭证。
     *
     * @param value 待校验或转换的受控输入
     * @return 符合 UTC 与数据库精度约束的时间
     */
    private static Instant optionalUntil(String value) {
        try {
            return value.isEmpty() ? null : Instant.parse(value);
        } catch (RuntimeException failure) {
            throw badConfiguration();
        }
    }

    /**
     * 校验服务或 Bootstrap 凭证的前缀和固定编码长度。
     *
     * @param value 待校验或转换的受控输入
     * @param prefix 凭证类型前缀
     */
    public static void requireToken(String value, String prefix) {
        if (value == null || !value.matches(prefix + "[A-Za-z0-9_-]{43}")) {
            throw badConfiguration();
        }
    }

    /**
     * 使用常量时间比较函数校验凭证，空值始终视为不匹配。
     *
     * @param left 第一个待比较的凭证
     * @param right 第二个待比较的凭证
     * @return 满足方法所述条件时为 true，否则为 false
     */
    public static boolean same(String left, String right) {
        return left != null && right != null
            && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验当前服务凭证，或仍在明确轮换窗口内的上一版服务凭证。
     *
     * @param bearer 完整 Bearer 服务凭证头
     * @return 满足方法所述条件时为 true，否则为 false
     */
    public boolean validService(String bearer) {
        return same(bearer, "Bearer " + serviceToken)
            || (!previousServiceToken.isEmpty() && previousServiceUntil != null
                && Instant.now().isBefore(previousServiceUntil) && same(bearer, "Bearer " + previousServiceToken));
    }

    /**
     * 返回当前可用于验证已有管理密钥的 Pepper，过期旧版本不会参与计算。
     *
     * @return 当前及仍在轮换窗口内的历史 Pepper
     */
    public List<Pepper> acceptedPeppers() {
        return previous != null && Instant.now().isBefore(previousPepperUntil)
            ? List.of(current, previous)
            : List.of(current);
    }

    /**
     * 取得新签发管理凭证唯一允许使用的当前 Pepper。
     *
     * @return 校验后的 HMAC 密钥与版本
     */
    public Pepper currentPepper() {
        return current;
    }

    /**
     * 联合校验运维模式、独立 Bootstrap 凭证和真实来源网段。
     *
     * @param mode 当前运维动作要求的模式
     * @param authorization 完整用户或 Bootstrap 凭证头
     * @param source 经受信 Gateway 写入的真实客户端地址
     */
    public void requireBootstrap(String mode, String authorization, String source) {
        if (!bootstrapMode.equals(mode)) {
            throw new ApiException(403, "BOOTSTRAP_DISABLED", "Bootstrap operation is not enabled");
        }
        if (!same(authorization, "Bearer " + bootstrapToken)) {
            throw ApiException.unauthenticated();
        }
        if (source == null || operationsSources.stream().noneMatch(matcher -> matcher.matches(source))) {
            throw ApiException.forbidden();
        }
    }
}
