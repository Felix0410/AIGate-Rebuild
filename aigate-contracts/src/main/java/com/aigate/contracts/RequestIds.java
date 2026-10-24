package com.aigate.contracts;

import java.util.List;
import java.util.UUID;

/**
 * 诊断请求 ID 的规范化工具，避免凭证被当作请求 ID 写入日志或审计。
 *
 * @author AIGate
 */
public final class RequestIds {

    /**
     * 工具类不允许实例化。
     */
    private RequestIds() {
    }

    /**
     * 规范化诊断标识或测试输出，防止秘密信息进入日志和报告。
     *
     * @param candidate 待规范化的路径或诊断标识
     * @return 当前操作生成或校验后的文本
     */
    public static String safe(String candidate) {
        if (candidate == null || !candidate.matches("[A-Za-z0-9_-]{1,64}")
            || List.of("mgt_", "bst_", "svc_", "agk_", "empk_").stream().anyMatch(candidate::startsWith)) {
            return UUID.randomUUID().toString();
        }
        return candidate;
    }
}
