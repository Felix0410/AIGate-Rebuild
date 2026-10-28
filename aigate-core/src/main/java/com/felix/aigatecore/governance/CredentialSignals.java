package com.felix.aigatecore.governance;

import java.util.Map;

/** 固定长度/字符集的高置信前缀检测；没有用户可配置正则或回溯表达式。 */
final class CredentialSignals {
    private static final Map<String, Integer> PREFIXES = Map.of("agk_", 43, "empk_", 43, "mgt_", 43,
        "bst_", 43, "svc_", 43, "sk-", 20, "AIza", 35, "AKIA", 16);

    private CredentialSignals() {
    }

    static boolean contains(String text) {
        for (int index = 0; index < text.length(); index++) {
            for (var prefix : PREFIXES.entrySet()) {
                if (!text.startsWith(prefix.getKey(), index)) {
                    continue;
                }
                int begin = index + prefix.getKey().length();
                if (text.length() - begin < prefix.getValue()) {
                    continue;
                }
                boolean match = true;
                for (int i = 0; i < prefix.getValue(); i++) {
                    char value = text.charAt(begin + i);
                    boolean valid = "AKIA".equals(prefix.getKey())
                        ? value >= 'A' && value <= 'Z' || value >= '0' && value <= '9'
                        : value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                            || value >= '0' && value <= '9' || value == '_' || value == '-';
                    if (!valid) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return true;
                }
            }
        }
        return false;
    }
}
