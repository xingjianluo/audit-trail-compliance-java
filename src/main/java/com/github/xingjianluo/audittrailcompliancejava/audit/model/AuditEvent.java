package com.github.xingjianluo.audittrailcompliancejava.audit.model;

/**
 * 一条不可变的审计事件。字段模型详见 docs/audit.md。
 */
public record AuditEvent(
        String eventId,
        long timestamp,
        String actorId,
        String actorName,
        String clientIp,
        String method,
        String path,
        String action,
        String objectType,
        String objectId,
        AuditOutcome outcome,
        String errorCode,
        String errorMessage,
        int httpStatus,
        String requestParamsMasked,
        String requestBodyMasked,
        long durationMs,
        String traceId
) {
}
