package com.felix.aigatecore.governance;

import static com.felix.aigatecore.catalog.CatalogValidation.bool;
import static com.felix.aigatecore.catalog.CatalogValidation.exact;

import com.felix.aigatecore.catalog.CatalogValidation;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 纯CPU有界扫描；此类不持有Mapper、事务、Provider客户端或日志正文。 */
@Component
public class GovernanceScanner {
    private final GovernanceSettings settings;
    private final LongSupplier nanos;

    /**
     * 生产使用单调时钟，超过预算只拒绝，不将200ms宣称为性能SLO。
     *
     * @param settings 有界扫描设置
     */
    @Autowired
    public GovernanceScanner(GovernanceSettings settings) {
        this(settings, System::nanoTime);
    }

    GovernanceScanner(GovernanceSettings settings, LongSupplier nanos) {
        this.settings = settings;
        this.nanos = nanos;
    }

    record Scan(RuleRegistry.DataClass floor, boolean secret, List<RuleRegistry.DataClass> markerClasses,
        List<String> codes, boolean stream, String failure) {
    }

    private void checkTime(long start) {
        if (nanos.getAsLong() - start > settings.budgetNanos()) {
            throw new IllegalStateException("Scanner deadline exceeded");
        }
    }

    private String decode(byte[] input) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();
    }

    Scan input(RuleRegistry.Compiled policy, byte[] bytes, String verifiedSourceClass) {
        var floor = RuleRegistry.DataClass.INTERNAL;
        long start = nanos.getAsLong();
        try {
            floor = floor.max(verifiedSourceClass == null
                ? RuleRegistry.DataClass.INTERNAL
                : RuleRegistry.classification(verifiedSourceClass));
            if (bytes == null || bytes.length == 0 || bytes.length > 65536) {
                throw new IllegalArgumentException();
            }
            var root = CatalogValidation.json(decode(bytes));
            exact(root, "messages", "stream");
            boolean stream = bool(root, "stream");
            var messages = root.get("messages");
            if (!messages.isArray() || messages.isEmpty() || messages.size() > 64) {
                throw new IllegalArgumentException();
            }
            var codes = new java.util.TreeSet<String>();
            codes.add(RuleRegistry.FLOOR);
            var hits = new HashSet<String>();
            var classes = new ArrayList<RuleRegistry.DataClass>();
            boolean secret = false;
            for (var message : messages) {
                checkTime(start);
                exact(message, "role", "content");
                if (!message.get("role").isString()
                    || !Set.of("system", "user", "assistant").contains(message.get("role").asString())
                    || !message.get("content").isString()) {
                    throw new IllegalArgumentException();
                }
                String content = message.get("content").asString();
                if (!StandardCharsets.UTF_8.newEncoder().canEncode(content)) {
                    throw new IllegalArgumentException();
                }
                secret |= CredentialSignals.contains(content);
                checkTime(start);
                String searchable = policy.ignoreCase() ? content.toLowerCase(Locale.ROOT) : content;
                for (var marker : policy.markers()) {
                    checkTime(start);
                    String target = policy.ignoreCase()
                        ? marker.matchText().toLowerCase(Locale.ROOT)
                        : marker.matchText();
                    if (searchable.contains(target) && hits.add(marker.code())) {
                        classes.add(marker.classification());
                        codes.add(RuleRegistry.MARKERS);
                    }
                }
            }
            if (secret) {
                codes.add(RuleRegistry.SECRET);
            }
            checkTime(start);
            return new Scan(floor, secret, List.copyOf(classes), List.copyOf(codes), stream, null);
        } catch (RuntimeException | java.nio.charset.CharacterCodingException failure) {
            return new Scan(floor, false, List.of(), List.of(RuleRegistry.FLOOR), false,
                "GOVERNANCE_SCANNER_UNAVAILABLE");
        }
    }

    String output(RuleRegistry.Compiled policy, byte[] bytes) {
        long start = nanos.getAsLong();
        try {
            if (bytes == null || bytes.length > policy.outputLimit()) {
                return "OUTPUT_LIMIT_EXCEEDED";
            }
            String content = decode(bytes);
            checkTime(start);
            boolean secret = CredentialSignals.contains(content);
            checkTime(start);
            return secret ? "OUTPUT_SECRET_DENIED" : null;
        } catch (RuntimeException | java.nio.charset.CharacterCodingException failure) {
            return "GOVERNANCE_SCANNER_UNAVAILABLE";
        }
    }
}
