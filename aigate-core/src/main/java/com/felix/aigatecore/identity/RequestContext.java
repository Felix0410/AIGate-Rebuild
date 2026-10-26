package com.felix.aigatecore.identity;

import com.felix.aigatecore.web.ApiException;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;

/**
 * 已通过服务身份校验的请求上下文；用户身份仍由领域服务根据当前管理凭证重新验证。
 *
 * @param authorization 完整用户或 Bootstrap 凭证头
 * @param clientAddress 由受信 Gateway 设置的实际客户端地址
 * @param requestId 已规范化的诊断请求 ID
 * @author AIGate
 */
public record RequestContext(String authorization, String clientAddress, String requestId) {

    /**
     * 提取单个 Authorization 头及受信来源，不接受重复凭证头。
     *
     * @param request 当前 HTTP 请求
     * @return 已提取单个凭证头及受信来源的请求上下文
     */
    public static RequestContext from(HttpServletRequest request) {
        // 1、读取 Authorization，只允许出现一个凭证头。
        var authorization = Collections.list(request.getHeaders("Authorization"));
        if (authorization.size() != 1) {
            throw ApiException.unauthenticated();
        }

        // 2、读取 Gateway 写入的来源地址和请求 ID；调用前必须已验证服务身份。
        return new RequestContext(authorization.getFirst(), request.getHeader("X-AIGate-Client-Address"),
            (String) request.getAttribute("requestId"));
    }
}
