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
     * @param request 当前 Servlet 或函数式请求
     * @return 已提取单个凭证头及受信来源的请求上下文
     */
    public static RequestContext from(HttpServletRequest request) {
        var authorization = Collections.list(request.getHeaders("Authorization"));
        if (authorization.size() != 1) {
            throw ApiException.unauthenticated();
        }
        return new RequestContext(authorization.getFirst(), request.getHeader("X-AIGate-Client-Address"),
            (String) request.getAttribute("requestId"));
    }
}
