package com.felix.aigatecore.web;

import java.io.Serial;

/**
 * 可预期的业务异常，异常消息必须是可公开文本，不得拼接敏感输入或底层 SQL。
 *
 * @author AIGate
 */
public final class ApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;

    private final String code;

    /**
     * 创建业务异常，保存 HTTP 状态、错误码和公开消息。
     *
     * @param status 当前接口允许的状态值
     * @param code 稳定错误码或员工编码
     * @param message 允许对外公开的安全消息
     */
    public ApiException(int status, String code, String message) {
        // 保存可对外返回的业务消息，不能传入 SQL、密钥或底层异常原文。
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * 读取此业务异常对应的 HTTP 状态码。
     *
     * @return 该业务异常对应的 HTTP 状态码
     */
    public int status() {
        return status;
    }

    /**
     * 读取对外稳定的业务错误编码。
     *
     * @return 业务错误码
     */
    public String code() {
        return code;
    }

    /**
     * 创建不携带输入原文的参数校验异常。
     *
     * @return 不含敏感信息的业务异常
     */
    public static ApiException invalid() {
        return new ApiException(400, "VALIDATION_ERROR", "Invalid request");
    }

    /**
     * 统一表示无效、过期或已吊销凭证，不区分具体密钥是否存在。
     *
     * @return 不含敏感信息的业务异常
     */
    public static ApiException unauthenticated() {
        // 统一返回 401，不区分凭证不存在、已过期或已吊销。
        return new ApiException(401, "UNAUTHENTICATED", "Invalid credentials");
    }

    /**
     * 表示当前身份有效但不具备执行该操作的权限。
     *
     * @return 不含敏感信息的业务异常
     */
    public static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "Operation not permitted");
    }

    /**
     * 表示可见范围内的资源不存在，不回显底层数据库信息。
     *
     * @return 不含敏感信息的业务异常
     */
    public static ApiException missing() {
        return new ApiException(404, "NOT_FOUND", "Resource not found");
    }

    /**
     * 表示当前资源状态、版本或幂等事实与操作要求冲突。
     *
     * @param code 稳定错误码或员工编码
     * @return 不含敏感信息的业务异常
     */
    public static ApiException conflict(String code) {
        return new ApiException(409, code, "The operation conflicts with current state");
    }
}
