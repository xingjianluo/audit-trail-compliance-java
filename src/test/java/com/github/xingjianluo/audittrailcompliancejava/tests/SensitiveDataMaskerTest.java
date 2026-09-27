package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.github.xingjianluo.audittrailcompliancejava.audit.mask.JacksonSensitiveDataMasker;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感信息脱敏测试：断言原始敏感值绝不出现，且保留可辨认的结构用于解释。
 */
class SensitiveDataMaskerTest {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataMaskerTest.class);

    private final JacksonSensitiveDataMasker masker = new JacksonSensitiveDataMasker();

    @Test
    void masksSensitiveJsonFieldsAndPatterns() {
        String raw = """
                {"userName":"alice","password":"SuperSecret123","token":"abcdef0123456789",
                 "mobile":"13812345678","email":"alice@example.com",
                 "idCard":"110101199003078888","bankCard":"6222020200011112222",
                 "nested":{"oldPassword":"p@ssw0rd"},"content":"普通文本保留可见"}
                """;

        String masked = masker.maskJson(raw);

        log.info("[脱敏-JSON] 输入事件请求体: {}", raw);
        log.info("[脱敏-JSON] 最终判定依据 masked={}", masked);

        // 原始敏感值一律不得出现
        assertFalse(masked.contains("SuperSecret123"), "密码原样落库");
        assertFalse(masked.contains("abcdef0123456789"), "token 原样落库");
        assertFalse(masked.contains("p@ssw0rd"), "嵌套密码原样落库");
        assertFalse(masked.contains("13812345678"), "手机号原样落库");
        assertFalse(masked.contains("alice@example.com"), "邮箱原样落库");
        assertFalse(masked.contains("110101199003078888"), "身份证原样落库");
        assertFalse(masked.contains("6222020200011112222"), "银行卡原样落库");

        // 可解释性：敏感字段整体掩码、模式值保留部分首尾
        assertTrue(masked.contains("\"password\":\"***\""), "敏感字段应整体掩码");
        assertTrue(masked.contains("\"oldPassword\":\"***\""), "嵌套敏感字段应整体掩码");
        assertTrue(masked.contains("138****5678"), "手机号应保留首尾可辨认");
        assertTrue(masked.contains("a***@example.com"), "邮箱应局部掩码");
        assertTrue(masked.contains("普通文本保留可见"), "非敏感内容不应被误伤");
    }

    @Test
    void masksQueryParametersByKeyAndBearerHeader() {
        String params = "name=alice&password=SuperSecret123&token=abcdef0123456789&page=1";
        String maskedParams = masker.maskParameters(params);
        log.info("[脱敏-参数] 输入: {}", params);
        log.info("[脱敏-参数] 最终判定依据 masked={}", maskedParams);

        assertFalse(maskedParams.contains("SuperSecret123"));
        assertFalse(maskedParams.contains("abcdef0123456789"));
        assertTrue(maskedParams.contains("name=alice"));
        assertTrue(maskedParams.contains("page=1"));

        String bearer = "Authorization: Bearer abcdef0123456789xyz";
        String maskedBearer = masker.maskJson(bearer);
        log.info("[脱敏-Bearer] 输入: {}", bearer);
        log.info("[脱敏-Bearer] 最终判定依据 masked={}", maskedBearer);
        assertFalse(maskedBearer.contains("abcdef0123456789xyz"));
        assertTrue(maskedBearer.contains("Bearer ***"));
    }

    @Test
    void nonJsonInputFallsBackToRegexMasking() {
        String raw = "free text mobile=13812345678 and password=Hunter2!! end";
        String masked = masker.maskJson(raw);
        log.info("[脱敏-非JSON兜底] 输入: {}", raw);
        log.info("[脱敏-非JSON兜底] 最终判定依据 masked={}", masked);

        assertFalse(masked.contains("13812345678"));
        assertFalse(masked.contains("Hunter2!!"), "键值对密码应被掩码");
    }
}
