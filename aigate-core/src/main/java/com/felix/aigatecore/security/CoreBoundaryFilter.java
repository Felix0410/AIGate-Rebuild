package com.felix.aigatecore.security;

import com.aigate.contracts.ApiContractConstants;
import com.aigate.contracts.ApiError;
import com.aigate.contracts.RequestIds;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

/**
 * Core 服务身份边界，验证受信 Gateway 凭证，设置安全请求 ID，并限制固定长度和分块请求体的大小。
 *
 * @author AIGate
 */
@Component
public class CoreBoundaryFilter extends OncePerRequestFilter {

    private final SecuritySettings settings;

    private final ObjectMapper json;

    /**
     * 注入安全配置和 JSON 转换工具。
     *
     * @param settings 服务凭证及运维访问配置
     * @param json 用于生成错误响应的 JSON 转换工具
     */
    public CoreBoundaryFilter(SecuritySettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    /**
     * 执行本服务的请求边界检查；拒绝响应不回显原始凭证或内部地址。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param chain 后续 Servlet 过滤链
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        // 1、生成安全请求 ID，写入请求上下文和响应，并禁止缓存。
        String requestId = RequestIds.safe(request.getHeader("X-Request-Id"));
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("Cache-Control", "no-store");

        // 2、只接受一个服务身份头，并检查当前或轮换期内的服务凭证。
        var headers = Collections.list(request.getHeaders("X-AIGate-Service-Authorization"));
        if (headers.size() != 1 || !settings.validService(headers.getFirst())) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write(json.writeValueAsString(
                ApiError.of("INTERNAL_UNAUTHENTICATED", "Invalid service credentials", requestId, false)));
            return;
        }

        // 3、正文长度已知且超过 64 KiB 时，直接返回 400。
        if (request.getContentLengthLong() > ApiContractConstants.MAX_JSON_BODY_BYTES) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter()
                .write(json.writeValueAsString(ApiError.of("VALIDATION_ERROR", "Invalid request", requestId, false)));
            return;
        }

        // 4、包装请求输入流，实际读取时累计字节，继续拦截分块传输的超限正文。
        var bounded = new HttpServletRequestWrapper(request) {

            private final ServletInputStream source = request.getInputStream();

            private final ServletInputStream limited = new ServletInputStream() {

                private int count;

                @Override
                public int read() throws IOException {
                    // 读取一个字节，超过上限时抛出异常，EOF 不计入长度。
                    int value = source.read();

                    if (value >= 0 && ++count > ApiContractConstants.MAX_JSON_BODY_BYTES) {
                        throw new IOException("Request too large");
                    }

                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    // 1、空缓冲读取直接返回 0。
                    if (length == 0) {
                        return 0;
                    }

                    // 2、最多读取剩余额度加一字节，发现超限时抛出异常。
                    int read = source.read(bytes, offset,
                        Math.min(length, ApiContractConstants.MAX_JSON_BODY_BYTES + 1 - count));
                    if (read > 0 && (count += read) > ApiContractConstants.MAX_JSON_BODY_BYTES) {
                        throw new IOException("Request too large");
                    }

                    return read;
                }

                @Override
                public boolean isFinished() {
                    return source.isFinished();
                }

                @Override
                public boolean isReady() {
                    return source.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    source.setReadListener(listener);
                }
            };

            @Override
            public ServletInputStream getInputStream() {
                return limited;
            }

            @Override
            public BufferedReader getReader() {
                // 字符读取也使用同一个受限输入流，防止绕过大小检查。
                return new BufferedReader(new InputStreamReader(limited, StandardCharsets.UTF_8));
            }
        };

        // 5、建立 Gateway 服务身份，员工权限仍由业务层重新检查。
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("gateway", null, List.of()));
        SecurityContextHolder.setContext(context);

        // 6、执行后续过滤链，请求结束时在 finally 中清除线程身份。
        try {
            chain.doFilter(bounded, response);
        } finally {
            // 线程会被容器复用，必须清除本次身份。
            SecurityContextHolder.clearContext();
        }
    }
}
