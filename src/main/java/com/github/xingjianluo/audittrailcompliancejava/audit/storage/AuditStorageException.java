package com.github.xingjianluo.audittrailcompliancejava.audit.storage;

/**
 * 审计存储不可用时抛出，用于和业务异常明确区分，触发降级信号。
 */
public class AuditStorageException extends RuntimeException {
    public AuditStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
