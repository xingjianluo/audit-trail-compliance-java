package com.github.xingjianluo.audittrailcompliancejava.audit.model;

/**
 * 一次过期清理的结果（本身也会被留痕）。
 */
public record RetentionPurgeResult(
        int policyVersion,
        long cutoffTime,
        long purgedCount,
        long remainingCount,
        long startedAt,
        long finishedAt
) {
}
