package com.aigate.contracts.dto;

import java.util.List;

/**
 * 分页响应，包含当前页数据、页码、每页条数和总数。
 *
 * <p>
 * total 是应用与 items 相同过滤条件后计算的总数，不能使用未过滤的全表总数。
 * </p>
 *
 * @param <T> 结果元素类型
 * @param items 当前身份可查看的结果列表
 * @param page 从 1 开始的页码
 * @param size 每页条数
 * @param total 当前身份可查看的记录总数
 * @author AIGate
 */
public record PageDTO<T>(List<T> items, int page, int size, long total) {
}
