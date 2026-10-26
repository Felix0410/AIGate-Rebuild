package com.aigate.contracts;

import java.util.List;
import java.util.UUID;

/**
 * 请求 ID 工具，保留合法 ID，替换无效值及疑似密钥。
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
     * @return 可保留的请求 ID，或新生成的 UUID
     */
    public static String safe(String candidate) {
        // 只保留 1～64 个 ASCII 字母、数字、短横线或下划线，疑似密钥则改用 UUID。
        if (candidate == null || !candidate.matches("[A-Za-z0-9_-]{1,64}")
            || List.of("mgt_", "bst_", "svc_", "agk_", "empk_").stream().anyMatch(candidate::startsWith)) {
            return UUID.randomUUID().toString();
        }

        return candidate;
    }
}
