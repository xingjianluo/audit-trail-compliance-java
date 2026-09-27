package com.github.xingjianluo.audittrailcompliancejava.audit.mask;

/**
 * 敏感信息脱敏器：任何请求内容写入审计库之前必须经过脱敏。
 */
public interface SensitiveDataMasker {

    /** 对 JSON 请求体脱敏，返回脱敏后的 JSON 字符串。 */
    String maskJson(String raw);

    /** 对 query string / 表单参数脱敏。 */
    String maskParameters(String raw);
}
