package com.github.xingjianluo.audittrailcompliancejava.audit;

/** 审计写入状态：持久化成功 / 降级缓冲 / 降级丢弃（带信号，不静默）。 */
public enum AuditWriteStatus {
    PERSISTED,
    DEGRADED_BUFFERED,
    DEGRADED_DROPPED
}
