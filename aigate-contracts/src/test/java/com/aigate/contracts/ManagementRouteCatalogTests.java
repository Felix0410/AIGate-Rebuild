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
class ManagementRouteCatalogTests {

    /**
     * 把密钥形状的请求 ID 替换为安全标识，拒绝换行注入。
     */
    @Test
    @DisplayName("把密钥形状的请求 ID 替换为安全标识，拒绝换行注入。")
    void requestIdsCannotPersistCredentialShapedValues() {
        // 1、检查正常请求 ID 保持不变。
        assertEquals("r-001", RequestIds.safe("r-001"));

        // 2、检查密钥形状的 ID 和包含换行的 ID 会被替换。
        assertNotEquals("mgt_" + "A".repeat(43), RequestIds.safe("mgt_" + "A".repeat(43)));
        assertNotEquals("r\r\nInjected: yes", RequestIds.safe("r\r\nInjected: yes"));
    }

    /**
     * 只有 P1 合同中的三十二个方法组合可以进入代理。
     */
    @Test
    @DisplayName("只有 P1 合同中的三十二个方法组合可以进入代理。")
    void onlyCurrentPhaseMethodsCanBeForwarded() {
        // 1、检查公开方法数量和合法路径。
        assertEquals(32, ManagementRouteCatalog.ALL.stream().mapToInt(route -> route.methods().size()).sum());
        assertEquals(0, ManagementRouteCatalog.rejection("PATCH", "/api/v1/teams/1"));

        // 2、检查不支持的方法返回 405。
        assertEquals(405, ManagementRouteCatalog.rejection("DELETE", "/api/v1/teams"));
        assertEquals(405, ManagementRouteCatalog.rejection("HEAD", "/api/v1/teams"));

        // 3、检查内部、未来、额外路径段及编码路径返回 404。
        assertEquals(404, ManagementRouteCatalog.rejection("GET", "/internal/v1/ping"));
        assertEquals(404, ManagementRouteCatalog.rejection("POST", "/v1/chat/completions"));
        assertEquals(404, ManagementRouteCatalog.rejection("GET", "/api/v1/teams/1/secret"));
        assertEquals(404, ManagementRouteCatalog.rejection("GET", "/api/v1/teams/%2e%2e"));
    }
}
