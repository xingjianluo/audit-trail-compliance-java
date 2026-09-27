package com.github.xingjianluo.audittrailcompliancejava.audit.model;

import java.util.List;

/**
 * 游标分页结果。
 */
public record AuditEventPage(
        List<AuditEvent> items,
        String nextCursor,
        boolean hasMore,
        long totalMatched
) {
}
