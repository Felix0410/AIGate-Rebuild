package com.felix.aigatecore.governance;

import static com.felix.aigatecore.catalog.CatalogValidation.bool;
import static com.felix.aigatecore.catalog.CatalogValidation.exact;
import static com.felix.aigatecore.catalog.CatalogValidation.integer;
import static com.felix.aigatecore.catalog.CatalogValidation.text;

import com.felix.aigatecore.catalog.CatalogValidation;
import com.felix.aigatecore.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 四个固定内置RuleCode及全八格出站矩阵；不执行上传代码、脚本或正则。 */
public final class RuleRegistry {
    static final String FLOOR = "TRUSTED_CLASSIFICATION_FLOOR_V1";
    static final String SECRET = "CREDENTIAL_PREFIX_HARD_DENY_V1";
    static final String MARKERS = "APPROVED_SENSITIVE_MARKERS_V1";
    static final String OUTPUT = "CONFIDENTIAL_OUTPUT_SECRET_REVIEW_V1";
    static final String DETECTORS = "AIGATE_AND_PROVIDER_CREDENTIAL_V1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RuleRegistry() {
    }

    enum DataClass {
        PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED;

        DataClass max(DataClass other) {
            return ordinal() >= other.ordinal() ? this : other;
        }
    }

    record Rule(String code, String kind, String severity, DataClass detectedClass, boolean exemptible,
        boolean enabled, int order, JsonNode parameters) {
    }

    record Marker(String code, String matchText, DataClass classification) {
    }

    record Egress(DataClass classification, String hosting, String decision, boolean agentApproval,
        boolean deploymentBoundary) {
    }

    record Compiled(String canonical, byte[] digest, List<Rule> rules, List<Marker> markers, boolean ignoreCase,
        List<Egress> egress, int outputLimit) {
    }

    static ApiException invalid() {
        return new ApiException(400, "INVALID_GOVERNANCE_POLICY", "Invalid bounded governance policy");
    }

    static DataClass classification(String value) {
        try {
            return DataClass.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw ApiException.invalid();
        }
    }

    static String canonical(JsonNode node) {
        return JSON.writeValueAsString(tree(node));
    }

    private static Object tree(JsonNode node) {
        if (node.isObject()) {
            var values = new TreeMap<String, Object>();
            node.properties().forEach(entry -> values.put(entry.getKey(), tree(entry.getValue())));
            return values;
        }
        if (node.isArray()) {
            var values = new ArrayList<Object>();
            node.forEach(value -> values.add(tree(value)));
            return values;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue();
        }
        if (node.isNull()) {
            return null;
        }
        throw invalid();
    }

    static byte[] hash(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Governance digest unavailable");
        }
    }

    static Compiled stored(String config) {
        JsonNode node = CatalogValidation.json(config);
        exact(node, "classificationRules", "egressMatrix", "outputReviewRules");
        return compile(node.get("classificationRules"), node.get("egressMatrix"), node.get("outputReviewRules"));
    }

    static Compiled compile(JsonNode classification, JsonNode matrix, JsonNode output) {
        try {
            return compileChecked(classification, matrix, output);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static Compiled compileChecked(JsonNode classification, JsonNode matrix, JsonNode output) {
        if (classification == null || !classification.isArray() || classification.size() < 2
            || classification.size() > 64 || matrix == null || !matrix.isArray() || matrix.size() != 8
            || output == null || !output.isArray() || output.size() != 1) {
            throw invalid();
        }
        var outputRule = output.get(0);
        exact(outputRule, "dataClass", "requiredMode", "ruleCode", "maxBufferedBytes");
        if (!"CONFIDENTIAL".equals(text(outputRule, "dataClass", 16))
            || !"BUFFERED_JSON".equals(text(outputRule, "requiredMode", 24))
            || !OUTPUT.equals(text(outputRule, "ruleCode", 64))) {
            throw invalid();
        }
        int outputLimit = integer(outputRule, "maxBufferedBytes", 1, 262144);
        var rules = new ArrayList<Rule>();
        var markers = new ArrayList<Marker>();
        var codes = new HashSet<String>();
        var orders = new HashSet<Integer>();
        boolean ignoreCase = false;
        for (var value : classification) {
            exact(value, "ruleCode", "ruleKind", "severity", "detectedClass", "exemptible", "enabled", "ruleOrder",
                "safeParameters");
            String code = text(value, "ruleCode", 64);
            String kind = text(value, "ruleKind", 32);
            String severity = text(value, "severity", 16);
            DataClass dataClass = classification(text(value, "detectedClass", 16));
            int order = integer(value, "ruleOrder", 0, Integer.MAX_VALUE);
            boolean exemptible = bool(value, "exemptible");
            boolean enabled = bool(value, "enabled");
            JsonNode params = value.get("safeParameters");
            if (!codes.add(code) || !orders.add(order) || !Set.of("INFO", "WARN", "DENY").contains(severity)) {
                throw invalid();
            }
            switch (code) {
                case FLOOR -> {
                    exact(params, "defaultClass", "honorVerifiedSourceTags");
                    if (!"CLASSIFY".equals(kind) || exemptible || !enabled || dataClass != DataClass.INTERNAL
                        || !"INTERNAL".equals(text(params, "defaultClass", 16))
                        || !bool(params, "honorVerifiedSourceTags")) {
                        throw invalid();
                    }
                }
                case SECRET -> {
                    exact(params, "detectorSet");
                    if (!"SECRET_HARD_DENY".equals(kind) || !"DENY".equals(severity) || exemptible || !enabled
                        || dataClass != DataClass.RESTRICTED || !DETECTORS.equals(text(params, "detectorSet", 64))) {
                        throw invalid();
                    }
                }
                case MARKERS -> {
                    exact(params, "markers", "ignoreCase");
                    if (!"CLASSIFY".equals(kind) || !exemptible) {
                        throw invalid();
                    }
                    ignoreCase = bool(params, "ignoreCase");
                    var entries = params.get("markers");
                    if (!entries.isArray() || entries.size() > 64) {
                        throw invalid();
                    }
                    var markerCodes = new HashSet<String>();
                    for (var marker : entries) {
                        exact(marker, "code", "matchText", "classification");
                        String markerCode = text(marker, "code", 64);
                        String match = text(marker, "matchText", 512);
                        int length = match.codePointCount(0, match.length());
                        var level = classification(text(marker, "classification", 16));
                        if (!markerCode.matches("[A-Z][A-Z0-9_-]{0,63}") || !markerCodes.add(markerCode)
                            || length < 4 || length > 128 || match.indexOf('\n') >= 0 || match.indexOf('\r') >= 0
                            || CredentialSignals.contains(match) || level == DataClass.PUBLIC) {
                            throw invalid();
                        }
                        if (enabled) {
                            markers.add(new Marker(markerCode, match, level));
                        }
                    }
                }
                case OUTPUT -> {
                    exact(params, "detectorSet", "maxBufferedBytes");
                    if (!"OUTPUT_REVIEW".equals(kind) || exemptible || !enabled
                        || !DETECTORS.equals(text(params, "detectorSet", 64))
                        || integer(params, "maxBufferedBytes", 1, 262144) != outputLimit) {
                        throw invalid();
                    }
                }
                default -> throw invalid();
            }
            rules.add(new Rule(code, kind, severity, dataClass, exemptible, enabled, order, params.deepCopy()));
        }
        if (!codes.containsAll(Set.of(FLOOR, SECRET))) {
            throw invalid();
        }
        if (!codes.contains(OUTPUT)) {
            int order = 0;
            while (orders.contains(order)) {
                order++;
            }
            var params = JSON.createObjectNode().put("detectorSet", DETECTORS).put("maxBufferedBytes", outputLimit);
            rules.add(new Rule(OUTPUT, "OUTPUT_REVIEW", "DENY", DataClass.CONFIDENTIAL, false, true, order, params));
        }
        rules.sort(Comparator.comparingInt(Rule::order));
        var egress = new ArrayList<Egress>();
        var cells = new HashSet<String>();
        for (var value : matrix) {
            exact(value, "dataClass", "hostingMode", "decision", "requiresAgentApproval", "requiresDeploymentBoundary");
            var dataClass = classification(text(value, "dataClass", 16));
            String host = text(value, "hostingMode", 24);
            String decision = text(value, "decision", 24);
            boolean agent = bool(value, "requiresAgentApproval");
            boolean boundary = bool(value, "requiresDeploymentBoundary");
            if (!Set.of("THIRD_PARTY_API", "SELF_HOSTED").contains(host)
                || !Set.of("ALLOW_IF_APPROVED", "DENY").contains(decision)
                || !cells.add(dataClass + ":" + host)) {
                throw invalid();
            }
            boolean hardDenied = dataClass == DataClass.RESTRICTED
                || dataClass == DataClass.CONFIDENTIAL && "THIRD_PARTY_API".equals(host);
            if (hardDenied && !"DENY".equals(decision)
                || dataClass == DataClass.INTERNAL && "THIRD_PARTY_API".equals(host)
                    && "ALLOW_IF_APPROVED".equals(decision) && (!agent || !boundary)
                || dataClass == DataClass.CONFIDENTIAL && "SELF_HOSTED".equals(host)
                    && "ALLOW_IF_APPROVED".equals(decision) && !boundary) {
                throw invalid();
            }
            egress.add(new Egress(dataClass, host, decision, agent, boundary));
        }
        var config = JSON.createObjectNode();
        config.set("classificationRules", classification.deepCopy());
        config.set("egressMatrix", matrix.deepCopy());
        config.set("outputReviewRules", output.deepCopy());
        String canonical = canonical(config);
        return new Compiled(canonical, hash(canonical), List.copyOf(rules), List.copyOf(markers), ignoreCase,
            List.copyOf(egress), outputLimit);
    }

    static void verify(GovernanceRows.Revision revision, List<GovernanceRows.Rule> stored, Compiled compiled) {
        if (!MessageDigest.isEqual(revision.contentDigest(), compiled.digest())
            || stored.size() != compiled.rules().size()) {
            throw invalid();
        }
        for (int i = 0; i < stored.size(); i++) {
            var left = stored.get(i);
            var right = compiled.rules().get(i);
            if (!left.ruleCode().equals(right.code()) || !left.ruleKind().equals(right.kind())
                || !left.severity().equals(right.severity())
                || !left.detectedClass().equals(right.detectedClass().name())
                || left.exemptible() != right.exemptible() || left.enabled() != right.enabled()
                || left.ruleOrder() != right.order()
                || !canonical(CatalogValidation.json(left.safeParameterJson())).equals(canonical(right.parameters()))) {
                throw invalid();
            }
        }
    }
}
