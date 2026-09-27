package com.github.xingjianluo.audittrailcompliancejava.audit.model;

/**
 * 审计查询条件：时间范围 + 操作者，配合游标分页保证结果稳定可复现。
 */
public record AuditEventQuery(
        Long startTime,
        Long endTime,
        String actorId,
        String cursor,
        int pageSize
) {
}
