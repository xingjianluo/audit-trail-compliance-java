package com.github.xingjianluo.audittrailcompliancejava.audit.model;

/**
 * 审计结果类别。成功与各类失败必须可区分，不能笼统地记成一次失败。
 */
public enum AuditOutcome {
    SUCCESS,
    AUTHN_FAILED,
    AUTHZ_FAILED,
    VALIDATION_FAILED,
    BUSINESS_FAILED,
    SERVER_ERROR
}
