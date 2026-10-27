package com.aigate.contracts.dto;

import java.time.Instant;

/**
 * 不可变原币种采购价，金额使用固定八位小数字符串。
 *
 * @param id 价格主键
 * @param revision 修订号
 * @param status 草稿或发布
 * @param currency 独立原币种
 * @param amountPer1kInput 每千输入Token采购价
 * @param amountPer1kOutput 每千输出Token采购价
 * @param effectiveFrom 生效时间，草稿为空
 * @param publishedAt 发布时间，草稿为空
 * @author AIGate
 */
public record PurchasePriceDTO(long id, int revision, String status, String currency, String amountPer1kInput,
    String amountPer1kOutput, Instant effectiveFrom, Instant publishedAt) {
}
