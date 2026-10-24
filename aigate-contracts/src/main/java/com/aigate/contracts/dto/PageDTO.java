package com.aigate.contracts.dto;

import java.util.List;

/**
 * 权限过滤后的分页响应，记录总数和结果列表必须使用相同的数据范围。
 *
 * @param <T> 结果元素类型
 * @param items 权限过滤后的结果列表
 * @param page 从 1 开始的页码
 * @param size 每页条数
 * @param total 权限过滤后的记录总数
 * @author AIGate
 */
public record PageDTO<T>(List<T> items, int page, int size, long total) {
}
