package com.github.xingjianluo.audittrailcompliancejava.audit;

/** 一次审计写入的结果，携带降级信号与原因。 */
public class AuditWriteOutcome {
    private final AuditWriteStatus status;
    private final String reason;

    public AuditWriteOutcome(AuditWriteStatus status, String reason) {
        this.status = status;
        this.reason = reason;
    }

    public AuditWriteStatus getStatus() { return status; }
    public String getReason() { return reason; }
}
