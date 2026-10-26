package com.felix.aigatecore.web;

import com.felix.aigatecore.identity.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理接口共用的 HTTP 参数读取工具，拒绝重复查询参数和多余正文。
 *
 * @author AIGate
 */
public final class ManagementRequestParameters {

    private ManagementRequestParameters() {
    }

    /**
     * 读取非列表接口的请求上下文。
     *
     * @param request HTTP 请求
     * @return 单凭证头、来源地址和请求 ID
     */
    public static RequestContext context(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw ApiException.invalid();
        }
        return RequestContext.from(request);
    }

    /**
     * 读取列表查询参数，拒绝正文和重复参数。
     *
     * @param request HTTP 请求
     * @return 单值参数集合
     */
    public static Map<String, String> query(HttpServletRequest request) {
        // 1、读取正文确认没有附带第二套业务输入。
        requireNoBody(request);
        Map<String, String> query = new HashMap<>();

        // 2、拒绝重复参数，不默认选用第一个或最后一个值。
        request.getParameterMap().forEach((name, values) -> {
            if (values.length != 1) {
                throw ApiException.invalid();
            }
            query.put(name, values[0]);
        });
        return query;
    }

    /**
     * 检查请求没有正文，包括未知 Content-Length 的分块请求。
     *
     * @param request HTTP 请求
     */
    public static void requireNoBody(HttpServletRequest request) {
        try {
            if (request.getInputStream().read() != -1) {
                throw ApiException.invalid();
            }
        } catch (IOException failure) {
            throw ApiException.invalid();
        }
    }
}
