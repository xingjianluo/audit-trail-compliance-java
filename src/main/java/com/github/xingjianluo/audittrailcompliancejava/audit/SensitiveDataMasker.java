package com.github.xingjianluo.audittrailcompliancejava.audit;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏器：审计落库前对请求参数中的敏感字段脱敏。
 * 覆盖 query string（k=v&k=v）与 JSON 文本（"k":"v"）两种形态。
 */
@Component
public class SensitiveDataMasker {

    /** 敏感字段名（小写匹配）。 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "secret", "token", "accesstoken",
            "authorization", "credential", "idcard", "idnumber", "phone",
            "mobile", "bankcard", "cardno");

    public static final String MASK = "******";

    /** query string 形态：key=value（& 分隔）。 */
    private static final Pattern KV_PATTERN =
            Pattern.compile("(?i)([a-z0-9_]+)=([^&\\s]*)");

    /** JSON 形态："key":"value" 或 "key":123。 */
    private static final Pattern JSON_PATTERN =
            Pattern.compile("(?i)\"([a-z0-9_]+)\"\\s*:\\s*(\"[^\"]*\"|[^,}\\]]+)");

    public String mask(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String masked = maskJson(raw);
        return maskKeyValue(masked);
    }

    private String maskJson(String raw) {
        Matcher m = JSON_PATTERN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (isSensitive(m.group(1))) {
                m.appendReplacement(sb,
                        Matcher.quoteReplacement("\"" + m.group(1) + "\":\"" + MASK + "\""));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String maskKeyValue(String raw) {
        Matcher m = KV_PATTERN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (isSensitive(m.group(1))) {
                m.appendReplacement(sb,
                        Matcher.quoteReplacement(m.group(1) + "=" + MASK));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private boolean isSensitive(String key) {
        return key != null && SENSITIVE_KEYS.contains(key.toLowerCase());
    }
}
