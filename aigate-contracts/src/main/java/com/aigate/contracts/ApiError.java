package com.aigate.contracts;

/**
 * 统一 HTTP 错误响应，只输出经过约束的错误信息，禁止携带 SQL、密钥或内部服务地址。
 *
 * @param error 不含底层异常的公开错误明细
 * @author AIGate
 */
public record ApiError(ErrorDetail error) {

    /**
     * 错误响应的公开明细；重试标志不代表客户端可以自动重放已可能提交的写操作。
     *
     * @param code 稳定错误码或员工编码
     * @param message 允许对外公开的安全消息
     * @param requestId 已规范化的诊断请求 ID
     * @param retryable 错误是否允许受控重试；不授予自动重放写操作的权限
     * @author AIGate
     */
    public record ErrorDetail(String code, String message, String requestId, boolean retryable) {
    }

    /**
     * 构造对外统一错误结构，不接受底层异常作为响应内容。
     *
     * @param code 稳定错误码或员工编码
     * @param message 允许对外公开的安全消息
     * @param requestId 已规范化的诊断请求 ID
     * @param retryable 错误是否允许受控重试；不授予自动重放写操作的权限
     * @return 不含底层异常和敏感信息的统一错误
     */
    public static ApiError of(String code, String message, String requestId, boolean retryable) {
        return new ApiError(new ErrorDetail(code, message, requestId, retryable));
    }
}
