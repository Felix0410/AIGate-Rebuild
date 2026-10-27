package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 不可变积分价版本，价格使用固定六位小数字符串。
 *
 * @param id 价格主键
 * @param revision 修订号
 * @param status 草稿或发布
 * @param pointPer1kInput 每千输入Token积分价
 * @param pointPer1kOutput 每千输出Token积分价
 * @param effectiveFrom 生效时间，草稿为空
 * @param publishedAt 发布时间，草稿为空
 * @author AIGate
 */
public record PointsPriceDTO(long id, int revision, String status, String pointPer1kInput, String pointPer1kOutput,
    Instant effectiveFrom, Instant publishedAt) {
}
