package com.felix.aigatecore.catalog;

import java.time.Instant;
import java.math.BigDecimal;

/**
 * Catalog内部持久化记录，只能转换为安全DTO后响应，禁止直接序列化或记录日志。
 *
 * @param id 资源主键
 * @param ownerId 该价格所属模型或部署主键
 * @param revision 正整数价格修订
 * @param currency 独立原币种，积分价为空
 * @param inputPrice 每千输入Token的定点单价
 * @param outputPrice 每千输出Token的定点单价
 * @param status 当前资源状态
 * @param effectiveFrom 发布时写入的UTC生效时刻，草稿为空
 * @param publishedAt 真实发布时间，草稿为空
 * @author AIGate
 */
public record CatalogPriceDO(long id, long ownerId, int revision, String currency, BigDecimal inputPrice,
    BigDecimal outputPrice, String status, Instant effectiveFrom, Instant publishedAt) {
}
