package com.github.xingjianluo.audittrailcompliancejava.audit.model;

/**
 * 保留策略（带版本号的不可变定义）。策略调整只新增版本，历史清理结论可追溯。
 */
public record RetentionPolicy(
        int version,
        long retentionMillis,
        long createdAt,
        String createdBy,
        String remark
) {
}
