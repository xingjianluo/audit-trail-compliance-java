package com.github.xingjianluo.audittrailcompliancejava.audit;

/** 审计结果分类：成功与各类失败可区分。 */
public enum AuditResult {
    SUCCESS,
    AUTHENTICATION_FAILED,
    AUTHORIZATION_FAILED,
    VALIDATION_FAILED,
    BUSINESS_ERROR,
    SYSTEM_ERROR
}
