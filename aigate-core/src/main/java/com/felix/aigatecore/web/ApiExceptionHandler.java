package com.felix.aigatecore.web;

import com.aigate.contracts.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一 HTTP 异常映射，业务错误与基础设施错误均转换为安全、稳定的错误结构。
 *
 * @author AIGate
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 把可预期业务异常转换为统一响应。
     *
     * @param failure 待转换的异常，不得将其原始消息直接输出给客户端
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException failure, HttpServletRequest request) {
        return response(failure.status(), failure.code(), failure.getMessage(), request);
    }

    /**
     * 把数据库唯一约束冲突转换为公开业务冲突，不暴露索引或 SQL。
     *
     * @param failure 待转换的异常，不得将其原始消息直接输出给客户端
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> duplicate(DuplicateKeyException failure, HttpServletRequest request) {
        return response(409, "DUPLICATE_RESOURCE", "Resource already exists", request);
    }

    /**
     * 创建不携带输入原文的参数校验异常。
     *
     * @param failure 待转换的异常，不得将其原始消息直接输出给客户端
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> invalid(Exception failure, HttpServletRequest request) {
        return response(400, "VALIDATION_ERROR", "Invalid request", request);
    }

    /**
     * 把数据库故障转换为依赖不可用，响应中不包含底层异常消息。
     *
     * @param failure 待转换的异常，不得将其原始消息直接输出给客户端
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> unavailable(DataAccessException failure, HttpServletRequest request) {
        // 数据库异常可能携带 SQL 和绑定值，只记录关联 ID 与异常类型。
        LOGGER.warn("数据库依赖不可用，requestId={}，exceptionType={}", request.getAttribute("requestId"),
            failure.getClass().getName());
        return response(503, "DEPENDENCY_UNAVAILABLE", "The operation could not be completed safely", request);
    }

    /**
     * 表示可见范围内的资源不存在，不回显底层数据库信息。
     *
     * @param failure 待转换的异常，不得将其原始消息直接输出给客户端
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(Exception failure, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "Resource not found", request);
    }

    /**
     * 拒绝合同未允许的 HTTP 方法。
     *
     * @param failure 待转换的异常，不得将其原始消息直接输出给客户端
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(Exception failure, HttpServletRequest request) {
        return response(405, "METHOD_NOT_ALLOWED", "Method not allowed", request);
    }

    /**
     * 兜底处理未预期异常，只返回安全错误并保留请求关联标识。
     *
     * @param failure 待转换的异常，不得将其原始消息直接输出给客户端
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception failure, HttpServletRequest request) {
        // 不直接输出原始异常消息，避免业务输入或凭证进入日志。
        LOGGER.error("未预期的请求异常，requestId={}，exceptionType={}", request.getAttribute("requestId"),
            failure.getClass().getName());
        return response(500, "INTERNAL_ERROR", "The operation could not be completed", request);
    }

    /**
     * 构造禁止缓存的错误响应，确保错误结构在所有接口中一致。
     *
     * @param status 当前合同允许的状态值
     * @param code 稳定错误码或员工编码
     * @param message 允许对外公开的安全消息
     * @param request 当前 Servlet 或函数式请求
     * @return 包含合同状态码及安全响应正文的 HTTP 响应
     */
    private ResponseEntity<ApiError> response(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
            .body(ApiError.of(code, message, (String) request.getAttribute("requestId"), status == 503));
    }
}
