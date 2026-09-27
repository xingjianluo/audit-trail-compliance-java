package com.github.xingjianluo.audittrailcompliancejava.audit.write;

/**
 * 审计写入健康度快照：降级信号必须可观测、可区分，不能静默吞掉。
 */
public record AuditDegradationSnapshot(
        long enqueued,
        long written,
        long retried,
        long failed,
        long queueRejected,
        long slowWrites,
        long deadLetter,
        int queueSize,
        boolean degraded
) {
}
