package com.aigate.contracts;

/**
 * HTTP 输入、管理凭证和分页的公共边界。
 *
 * <p>
 * 网关和 Core 使用相同常量，避免各自修改限制后产生接收与校验不一致。 这里只定义现有合同的技术限制，不承载角色授权或资金规则。
 * </p>
 *
 * @author AIGate
 */
public final class ApiContractConstants {

    /**
     * JSON 请求体最多允许 64 KiB，按字节计算。
     */
    public static final int MAX_JSON_BODY_BYTES = 64 * 1024;

    /**
     * 诊断请求 ID 的最大 ASCII 字符数。
     */
    public static final int MAX_REQUEST_ID_LENGTH = 64;

    /**
     * 每个管理或服务凭证的随机熵为 32 字节。
     */
    public static final int SECRET_RANDOM_BYTES = 32;

    /**
     * 32 字节经无填充 Base64URL 编码后的字符数。
     */
    public static final int ENCODED_SECRET_LENGTH = 43;

    /**
     * 数据库和安全 DTO 中可公开的管理密钥前缀长度。
     */
    public static final int MANAGEMENT_KEY_PREFIX_LENGTH = 12;

    /**
     * 分页默认每页条数。
     */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 分页允许的最大每页条数。
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * 工具类不允许实例化。
     */
    private ApiContractConstants() {
    }
}
