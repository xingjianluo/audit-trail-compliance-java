package com.github.xingjianluo.audittrailcompliancejava.audit.mask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏器。
 *
 * <p>两类规则：
 * <ol>
 *   <li>字段名规则：key 命中 {@link #SENSITIVE_KEYS} 时值整体掩码为 ***；</li>
 *   <li>值模式规则：无论字段名，只要值长得像手机号/邮箱/身份证/银行卡，就做部分掩码。</li>
 * </ol>
 * 无法解析为 JSON 时使用正则兜底，保证原始敏感串绝不会原样落库。
 */
@Component
public class JacksonSensitiveDataMasker implements SensitiveDataMasker {

    private static final Logger log = LoggerFactory.getLogger(JacksonSensitiveDataMasker.class);

    /** 命中即整体掩码的字段名（小写比较）。 */
    static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "oldpassword", "newpassword",
            "token", "accesstoken", "refreshtoken", "authorization",
            "secret", "apikey", "api_key", "appsecret",
            "idcard", "id_card", "idnumber",
            "bankcard", "bank_card", "cardno", "cvv", "cvv2"
    );

    /** 脱敏后单条内容上限，避免超大请求把审计库撑爆；截断有显式标记。 */
    static final int MAX_CONTENT_LENGTH = 4096;
    private static final String TRUNCATED_MARK = "...<truncated>";

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE =
            Pattern.compile("(?<![0-9])1[3-9]\\d{9}(?![0-9])");
    private static final Pattern ID_CARD =
            Pattern.compile("(?<![0-9Xx])[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?![0-9Xx])");
    private static final Pattern BANK_CARD =
            Pattern.compile("(?<![0-9])(?:62|4[0-9]|5[1-5])\\d{14,17}(?![0-9])");
    private static final Pattern BEARER =
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern KV =
            Pattern.compile("(?i)(\"?)([A-Za-z0-9_.-]*(?:password|passwd|pwd|token|secret|apikey|api_key|idcard|id_card|bankcard|bank_card|cvv)[A-Za-z0-9_.-]*)(\"?)\\s*[=:]\\s*(\"?)([^\"&,\\s}]+)(\"?)");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String maskJson(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String out;
        try {
            JsonNode root = objectMapper.readTree(raw);
            maskNode(root, null);
            out = objectMapper.writeValueAsString(root);
        } catch (Exception parseEx) {
            // 非合法 JSON（例如纯文本/表单片段）：正则兜底
            log.debug("请求体不是合法 JSON，使用正则脱敏兜底: {}", parseEx.getMessage());
            out = maskByRegex(raw);
        }
        return truncate(out);
    }

    @Override
    public String maskParameters(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String masked = maskByRegex(raw);
        // query/form 常见的 key=value 再按字段名规则处理一遍
        StringBuilder sb = new StringBuilder(masked.length());
        for (String pair : masked.split("&")) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq);
                String value = pair.substring(eq + 1);
                if (SENSITIVE_KEYS.contains(key.toLowerCase())) {
                    value = "***";
                }
                sb.append(key).append('=').append(value);
            } else {
                sb.append(pair);
            }
        }
        return truncate(sb.toString());
    }

    private void maskNode(JsonNode node, String keyName) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            obj.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode child = entry.getValue();
                if (child == null || child.isNull()) {
                    return;
                }
                if (child.isTextual()) {
                    String masked = isSensitiveKey(name)
                            ? "***" : maskValuePatterns(child.asText());
                    obj.set(name, TextNode.valueOf(masked));
                } else {
                    maskNode(child, name);
                }
            });
        } else if (node.isArray() && node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (child == null || child.isNull()) {
                    continue;
                }
                if (child.isTextual()) {
                    arr.set(i, TextNode.valueOf(maskValuePatterns(child.asText())));
                } else {
                    maskNode(child, keyName);
                }
            }
        }
    }

    /** 数组/对象内文本值的模式脱敏入口（在遍历时就地替换）。 */
    static String maskValuePatterns(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String out = value;
        Matcher m;
        m = ID_CARD.matcher(out);
        out = m.replaceAll(mr -> maskKeepEdges(mr.group(), 4, 4));
        m = BANK_CARD.matcher(out);
        out = m.replaceAll(mr -> maskKeepEdges(mr.group(), 0, 4));
        m = PHONE.matcher(out);
        out = m.replaceAll(mr -> maskKeepEdges(mr.group(), 3, 4));
        m = EMAIL.matcher(out);
        out = m.replaceAll(mr -> maskEmail(mr.group()));
        m = BEARER.matcher(out);
        out = m.replaceAll("Bearer ***");
        return out;
    }

    static boolean isSensitiveKey(String name) {
        return name != null && SENSITIVE_KEYS.contains(name.toLowerCase());
    }

    private static String maskByRegex(String raw) {
        String out = raw;
        out = ID_CARD.matcher(out).replaceAll(mr -> maskKeepEdges(mr.group(), 4, 4));
        out = BANK_CARD.matcher(out).replaceAll(mr -> maskKeepEdges(mr.group(), 0, 4));
        out = PHONE.matcher(out).replaceAll(mr -> maskKeepEdges(mr.group(), 3, 4));
        out = EMAIL.matcher(out).replaceAll(mr -> maskEmail(mr.group()));
        out = BEARER.matcher(out).replaceAll("Bearer ***");
        out = KV.matcher(out).replaceAll(mr -> mr.group(1) + mr.group(2) + mr.group(3)
                + "=" + mr.group(4) + "***" + mr.group(6));
        return out;
    }

    private static String maskKeepEdges(String value, int keepHead, int keepTail) {
        int len = value.length();
        if (len <= keepHead + keepTail) {
            return "***";
        }
        return value.substring(0, keepHead)
                + "*".repeat(len - keepHead - keepTail)
                + value.substring(len - keepTail);
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_CONTENT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_CONTENT_LENGTH - TRUNCATED_MARK.length()) + TRUNCATED_MARK;
    }
}
