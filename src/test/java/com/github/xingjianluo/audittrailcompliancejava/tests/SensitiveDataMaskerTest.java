package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.github.xingjianluo.audittrailcompliancejava.audit.SensitiveDataMasker;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** 敏感信息脱敏：输入事件与最终判定依据打印到日志。 */
class SensitiveDataMaskerTest {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataMaskerTest.class);

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void masksSensitiveKeysInQueryString() {
        String input = "name=alice&password=s3cret&token=abc123&amount=100";
        String masked = masker.mask(input);
        log.info("输入事件: {}", input);
        log.info("判定依据: 敏感字段 password/token 应被替换为 {}, 非敏感字段 name/amount 保持原样; 实际输出: {}",
                SensitiveDataMasker.MASK, masked);

        assertThat(masked).contains("name=alice", "amount=100");
        assertThat(masked).contains("password=" + SensitiveDataMasker.MASK,
                "token=" + SensitiveDataMasker.MASK);
        assertThat(masked).doesNotContain("s3cret", "abc123");
    }

    @Test
    void masksSensitiveKeysInJson() {
        String input = "{\"user\":\"bob\",\"idCard\":\"110101199001011234\",\"phone\":\"13800001111\"}";
        String masked = masker.mask(input);
        log.info("输入事件: {}", input);
        log.info("判定依据: JSON 中 idCard/phone 应脱敏, user 保持原样; 实际输出: {}", masked);

        assertThat(masked).contains("\"user\":\"bob\"");
        assertThat(masked).doesNotContain("110101199001011234", "13800001111");
    }

    @Test
    void leavesNonSensitiveContentUntouched() {
        String input = "action=transfer&amount=500";
        String masked = masker.mask(input);
        log.info("输入事件: {}", input);
        log.info("判定依据: 无敏感字段, 输出应与输入一致; 实际输出: {}", masked);

        assertThat(masked).isEqualTo(input);
    }

    @Test
    void handlesNullAndEmpty() {
        log.info("输入事件: null 与空串");
        log.info("判定依据: 应原样返回, 不抛异常");
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }
}
