package com.aigate.contracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** P7请求攻击面与规范字节回归，不执行Provider发送。 */
class ChatRequestV1Tests {
    private static final String BASE = """
        {"model":"TEXT-Q4-STABLE","stream":false,"messages":[{"role":"user","content":" Hello 水 "}],
        "max_tokens":128}
        """;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ChatRequestV1 parse(String body) {
        return ChatRequestV1.parse(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void canonicalBytesHaveFixedOrderDefaultsAndAnActualDomainNewline() {
        var request = parse(BASE);
        String expected = "{\"model\":\"TEXT-Q4-STABLE\",\"stream\":false,\"messages\":[{\"role\":\"user\","
            + "\"content\":\" Hello 水 \"}],\"max_tokens\":128,\"temperature\":1,\"top_p\":1}";
        assertEquals(expected, new String(request.canonicalBytes(), StandardCharsets.UTF_8));
        assertArrayEquals(("AIGATE:INVOCATION:V1\n" + expected).getBytes(StandardCharsets.UTF_8),
            request.signingBytes());
        assertArrayEquals(request.canonicalBytes(), parse("""
            {"top_p":1e0,"temperature":1.000,"max_tokens":128,"messages":[{"content":" Hello 水 ","role":"user"}],
            "stream":false,"model":"TEXT-Q4-STABLE"}
            """).canonicalBytes());
    }

    @Test
    void noTrimUnicodeNormalizationOrMutableTreeAliasing() {
        var request = parse(BASE);
        assertFalse(Arrays.equals(request.canonicalBytes(), parse(BASE.replace(" Hello 水 ", "Hello 水"))
            .canonicalBytes()));
        assertFalse(Arrays.equals(parse(BASE.replace("水", "é")).canonicalBytes(),
            parse(BASE.replace("水", "e\u0301")).canonicalBytes()));
        request.providerBody().put("model", "MUTATED");
        byte[] bytes = request.canonicalBytes();
        bytes[0] = 0;
        assertEquals("TEXT-Q4-STABLE", request.providerBody().get("model").asString());
        assertTrue(request.canonicalBytes()[0] != 0);
        assertFalse(request.toString().contains("Hello"));
    }

    @Test
    void everyBusinessFieldChangesCanonicalBytes() {
        var baseline = parse(BASE).canonicalBytes();
        for (String body : List.of(BASE.replace("STABLE", "OTHER"), BASE.replace("false", "true"),
            BASE.replace("Hello", "Goodbye"), BASE.replace("user", "system"), BASE.replace("128", "129"),
            BASE.replace("128}", "128,\"temperature\":0.5}"),
            BASE.replace("128}", "128,\"top_p\":0.9}"))) {
            assertFalse(Arrays.equals(baseline, parse(body).canonicalBytes()));
        }
    }

    @Test
    void rejectsUnknownDuplicateNullAndFutureAuthorizationFields() {
        for (String extra : List.of("\"model\":\"OTHER\"", "\"employeeId\":1", "\"deploymentId\":1",
            "\"providerURL\":\"https://example.org\"", "\"serviceClass\":\"LOW\"", "\"n\":1",
            "\"tools\":[]", "\"fallback_mode\":\"NONE\"", "\"emergencyAuthorizationId\":1",
            "\"temperature\":null", "\"top_p\":null")) {
            var failure = assertThrows(IllegalArgumentException.class,
                () -> parse(BASE.replace("128}", "128," + extra + "}")));
            assertEquals("Invalid ChatRequestV1", failure.getMessage());
            assertEquals(null, failure.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> parse(BASE + "{}"));
        assertThrows(IllegalArgumentException.class,
            () -> parse(BASE.replace("\"content\":", "\"tools\":[],\"content\":")));
    }

    @Test
    void validatesStrictTypesRangesAndExactDecimals() {
        for (String value : List.of("0", "-1", "1.0", "1e2", "2147483648", "\"128\"", "null")) {
            assertThrows(IllegalArgumentException.class, () -> parse(BASE.replace("128", value)));
        }
        for (String value : List.of("-0.1", "2.00000000000000000001", "\"1\"", "true")) {
            assertThrows(IllegalArgumentException.class,
                () -> parse(BASE.replace("128}", "128,\"temperature\":" + value + "}")));
        }
        for (String value : List.of("0", "-1", "1.00000000000000000001", "false")) {
            assertThrows(IllegalArgumentException.class,
                () -> parse(BASE.replace("128}", "128,\"top_p\":" + value + "}")));
        }
        assertArrayEquals(parse(BASE.replace("128}", "128,\"temperature\":0}")).canonicalBytes(),
            parse(BASE.replace("128}", "128,\"temperature\":-0.0}")).canonicalBytes());
        assertThrows(IllegalArgumentException.class, () -> parse(BASE.replace("false", "\"false\"")));
        assertThrows(IllegalArgumentException.class, () -> parse(BASE.replace("user", "tool")));
        assertThrows(IllegalArgumentException.class, () -> parse(BASE.replace("\" Hello 水 \"", "[]")));
    }

    @Test
    void boundsMessageCountAndRawUtf8AndRejectsBrokenUnicode() {
        var root = JSON.readTree(BASE);
        var messages = (tools.jackson.databind.node.ArrayNode) root.get("messages");
        var message = messages.get(0).deepCopy();
        messages.removeAll();
        assertThrows(IllegalArgumentException.class, () -> parse(root.toString()));
        for (int i = 0; i < 64; i++) {
            messages.add(message);
        }
        parse(root.toString());
        messages.add(message);
        assertThrows(IllegalArgumentException.class, () -> parse(root.toString()));
        assertThrows(IllegalArgumentException.class, () -> parse(BASE.replace("Hello", "水".repeat(22000))));
        assertThrows(IllegalArgumentException.class, () -> parse(BASE.replace("Hello", "\\uD800")));
        assertThrows(IllegalArgumentException.class, () -> ChatRequestV1.parse(new byte[]{(byte) 0xff}));
    }

    @Test
    void streamOptionsNeverChangeBillingSemantics() {
        String stream = BASE.replace("false", "true");
        var request = parse(stream);
        var explicit = parse(stream.replace("128}", "128,\"stream_options\":{\"include_usage\":true}}"));
        assertArrayEquals(request.canonicalBytes(), explicit.canonicalBytes());
        assertTrue(request.providerBody().get("stream_options").get("include_usage").booleanValue());
        assertFalse(parse(BASE).providerBody().has("stream_options"));
        for (String options : List.of("{}", "{\"include_usage\":false}", "{\"include_usage\":null}",
            "{\"include_usage\":true,\"extra\":true}")) {
            assertThrows(IllegalArgumentException.class,
                () -> parse(stream.replace("128}", "128,\"stream_options\":" + options + "}")));
        }
        assertThrows(IllegalArgumentException.class,
            () -> parse(BASE.replace("128}", "128,\"stream_options\":{\"include_usage\":true}}")));
    }
}
