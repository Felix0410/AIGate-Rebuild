package com.aigate.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 公开路由和请求 ID 的回归测试，防止扩大入口范围或将密钥写入诊断字段。
 *
 * @author AIGate
 */
class P1RoutesTests {

    /**
     * 把密钥形状的请求 ID 替换为安全标识，拒绝换行注入。
     */
    @Test
    @DisplayName("把密钥形状的请求 ID 替换为安全标识，拒绝换行注入。")
    void requestIdsCannotPersistCredentialShapedValues() {
        assertEquals("r-001", RequestIds.safe("r-001"));
        assertNotEquals("mgt_" + "A".repeat(43), RequestIds.safe("mgt_" + "A".repeat(43)));
        assertNotEquals("r\r\nInjected: yes", RequestIds.safe("r\r\nInjected: yes"));
    }

    /**
     * 只有 P1 合同中的十七个方法组合可以进入代理。
     */
    @Test
    @DisplayName("只有 P1 合同中的十七个方法组合可以进入代理。")
    void onlyTheSeventeenContractMethodsCanBeForwarded() {
        assertEquals(17, P1Routes.ALL.stream().mapToInt(route -> route.methods().size()).sum());
        assertEquals(0, P1Routes.rejection("PATCH", "/api/v1/teams/1"));
        assertEquals(405, P1Routes.rejection("DELETE", "/api/v1/teams"));
        assertEquals(405, P1Routes.rejection("HEAD", "/api/v1/teams"));
        assertEquals(404, P1Routes.rejection("GET", "/internal/v1/ping"));
        assertEquals(404, P1Routes.rejection("POST", "/v1/chat/completions"));
        assertEquals(404, P1Routes.rejection("GET", "/api/v1/teams/1/secret"));
        assertEquals(404, P1Routes.rejection("GET", "/api/v1/teams/%2e%2e"));
    }
}
