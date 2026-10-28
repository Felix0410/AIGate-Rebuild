package com.felix.aigatecore.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aigate.contracts.ChatRequestV1;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 请求摘要版本轮换及外部独立HMAC向量，避免签名和验证同时犯同一错误。 */
class RequestDigestKeyringTests {
    private ChatRequestV1 request(String content) {
        return ChatRequestV1.parse(("{\"model\":\"TEXT-Q4-STABLE\",\"stream\":false,\"messages\":[{\"role\":\"user\","
            + "\"content\":\"" + content + "\"}],\"max_tokens\":128}").getBytes(StandardCharsets.UTF_8));
    }

    private byte[] key(int offset) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (offset + i);
        }
        return bytes;
    }

    @Test
    void matchesIndependentPythonHmacSha256VectorAndNeverUsesPlainSha() {
        var ring = new RequestDigestKeyring("k1", Map.of("k1", key(0)));
        assertEquals("b3e07515a2dc779bbc9c3dee94875c52e013bd619a898789ac8b725e7c36cdf5",
            HexFormat.of().formatHex(ring.digest("k1", 1, request("Hello"))));
    }

    @Test
    void rotationUsesOriginalVersionAndRevocationNeverFallsBack() {
        var old = new RequestDigestKeyring("k1", Map.of("k1", key(0)));
        var digest = old.digest(old.currentId(), 1, request("Hello"));
        var rotated = new RequestDigestKeyring("k2", Map.of("k1", key(0), "k2", key(32)));
        assertEquals("k2", rotated.currentId());
        assertTrue(rotated.matches("k1", 1, request("Hello"), digest));
        assertFalse(rotated.matches("k2", 1, request("Hello"), digest));
        assertFalse(rotated.matches("k1", 1, request("Goodbye"), digest));
        assertFalse(rotated.matches("k1", 1, request("Hello"), null));
        assertFalse(rotated.matches("k1", 1, request("Hello"), new byte[31]));
        var revoked = new RequestDigestKeyring("k2", Map.of("k2", key(32)));
        assertThrows(IllegalArgumentException.class, () -> revoked.matches("k1", 1, request("Hello"), digest));
        assertThrows(IllegalArgumentException.class, () -> rotated.matches("k1", 99, request("Hello"), digest));
        assertThrows(IllegalArgumentException.class, () -> rotated.matches(null, 1, request("Hello"), digest));
    }

    @Test
    void copiesSecretInputAndReturnsIndependentDigests() {
        byte[] material = key(0);
        var ring = new RequestDigestKeyring("k1", Map.of("k1", material));
        byte[] before = ring.digest("k1", 1, request("Hello"));
        material[0] = 99;
        assertArrayEquals(before, ring.digest("k1", 1, request("Hello")));
        before[0] ^= 1;
        assertFalse(ring.matches("k1", 1, request("Hello"), before));
        assertFalse(ring.toString().contains(HexFormat.of().formatHex(key(0))));
    }

    @Test
    void rejectsMissingShortDuplicateMaterialAndInvalidIds() {
        assertThrows(IllegalArgumentException.class, () -> new RequestDigestKeyring("missing", Map.of("k1", key(0))));
        assertThrows(IllegalArgumentException.class, () -> new RequestDigestKeyring("k1", Map.of("k1", new byte[31])));
        assertThrows(IllegalArgumentException.class,
            () -> new RequestDigestKeyring("k1", Map.of("k1", key(0), "k2", key(0))));
        assertThrows(IllegalArgumentException.class,
            () -> new RequestDigestKeyring("bad id", Map.of("bad id", key(0))));
    }
}
