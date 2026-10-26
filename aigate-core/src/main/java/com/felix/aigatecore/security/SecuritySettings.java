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
     * @param id Pepper 版本标识，用于选择匹配的 HMAC 密钥
     * @param bytes 解码后的独立 HMAC 密钥材料
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
     * 读取并检查服务凭证、Pepper、Bootstrap 和来源网段配置。
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

        // 1、检查当前服务凭证，保存当前凭证和旧凭证配置。
        requireToken(serviceToken, "svc_");
        this.serviceToken = serviceToken;
        this.previousServiceToken = previousServiceToken;
        this.previousServiceUntil = optionalUntil(previousServiceUntil);

        // 2、配置旧服务凭证时，要求其与当前凭证不同，且必须设置截止时间。
        if (!previousServiceToken.isEmpty()) {
            requireToken(previousServiceToken, "svc_");
            if (this.previousServiceUntil == null || previousServiceToken.equals(serviceToken)) {
                throw badConfiguration();
            }
        }

        // 3、读取新旧 Pepper，检查版本、密钥材料和旧版本截止时间。
        this.current = pepper(pepperId, pepper);
        this.previous = previousPepper.isEmpty() ? null : pepper(previousPepperId, previousPepper);
        this.previousPepperUntil = optionalUntil(previousPepperUntil);

        if (previous != null && (previous.id().equals(current.id()) || Arrays.equals(previous.bytes(), current.bytes())
            || this.previousPepperUntil == null)) {
            throw badConfiguration();
        }

        // 4、读取 Bootstrap 模式和凭证，模式只能为 OFF、INIT 或 RECOVERY。
        this.bootstrapMode = bootstrapMode;
        this.bootstrapToken = bootstrapToken;
        if (!Set.of("OFF", "INIT", "RECOVERY").contains(bootstrapMode)) {
            throw badConfiguration();
        }

        // 5、解析允许访问运维接口的 IP 网段，不接受域名。
        this.operationsSources = cidrs.isBlank()
            ? List.of()
            : Arrays.stream(cidrs.split(",")).map(String::strip).map(source -> {
                if (!source.matches("[0-9a-fA-F:.]+/[0-9]{1,3}")) {
                    throw badConfiguration();
                }

                return new IpAddressMatcher(source);
            }).toList();

        // 6、开启 INIT 或 RECOVERY 时，要求同时配置独立凭证和来源网段。
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
     * 解析独立 HMAC Pepper 及其标识，校验解码后至少包含 32 字节密钥材料。
     *
     * @param id Pepper 版本标识，用于选择匹配的 HMAC 密钥
     * @param encoded 标准 Base64 编码的独立 HMAC 密钥
     * @return 校验后的 HMAC 密钥与版本
     */
    private static Pepper pepper(String id, String encoded) {
        try {
            // 解码 Base64，要求版本格式正确，密钥材料至少为 32 字节。
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
     * @param value 待检查或转换的值
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
     * @param value 待检查或转换的值
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
     * @return 两边均非空且字节相同时为 true
     */
    public static boolean same(String left, String right) {
        // 拒绝空值，再使用 MessageDigest.isEqual 比较 UTF-8 字节。
        return left != null && right != null
            && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验当前服务凭证，或仍在明确轮换窗口内的上一版服务凭证。
     *
     * @param bearer 完整 Bearer 服务凭证头
     * @return 服务凭证有效且未超过允许的轮换窗口时为 true
     */
    public boolean validService(String bearer) {
        // 接受当前服务凭证，或截止时间之前仍可使用的旧凭证。
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
        // 返回当前 Pepper；旧 Pepper 只在轮换截止时间之前加入验证列表。
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
     * 检查运行凭证配置是否复用了管理 Pepper，包括仍保留的历史配置。
     *
     * @param material 候选密钥材料
     * @return 与管理用途的任一材料相同时为 true
     */
    public boolean usesPepper(byte[] material) {
        return Arrays.equals(current.bytes(), material)
            || (previous != null && Arrays.equals(previous.bytes(), material));
    }

    /**
     * 联合校验运维模式、独立 Bootstrap 凭证和真实来源网段。
     *
     * @param mode 当前运维动作要求的模式
     * @param authorization 完整用户或 Bootstrap 凭证头
     * @param source 经受信 Gateway 写入的真实客户端地址
     */
    public void requireBootstrap(String mode, String authorization, String source) {
        // 1、检查当前运维模式是否允许本次操作。
        if (!bootstrapMode.equals(mode)) {
            throw new ApiException(403, "BOOTSTRAP_DISABLED", "Bootstrap operation is not enabled");
        }

        // 2、检查独立 Bootstrap 凭证，不使用员工或服务凭证替代。
        if (!same(authorization, "Bearer " + bootstrapToken)) {
            throw ApiException.unauthenticated();
        }

        // 3、检查客户端来源是否属于允许的 IP 网段。
        if (source == null || operationsSources.stream().noneMatch(matcher -> matcher.matches(source))) {
            throw ApiException.forbidden();
        }
    }
}
