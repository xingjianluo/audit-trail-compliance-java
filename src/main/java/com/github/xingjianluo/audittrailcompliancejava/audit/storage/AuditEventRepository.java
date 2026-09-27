package com.github.xingjianluo.audittrailcompliancejava.audit.storage;

import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEvent;

import java.util.List;

/**
 * 审计存储（本地实现模拟审计库）。
 * 要求：append 在并发下不丢、不乱序、不重复；query 排序确定；purge 只删过期数据。
 */
public interface AuditEventRepository {

    /** 追加一条审计事件，eventId 重复时拒绝（幂等防重）。 */
    void append(AuditEvent event);

    /**
     * 确定性查询：按 (timestamp, eventId) 升序。
     * 时间范围闭区间；afterTimestamp/afterSequence 为游标（不含游标位置）。
     */
    List<AuditEvent> search(Long startTime, Long endTime, String actorId,
                            Long afterTimestamp, Long afterSequence, int limit);

    /** 统计时间范围内的匹配总数（用于分页稳定复现）。 */
    long count(Long startTime, Long endTime, String actorId);

    /** 删除 timestamp 严格小于 cutoff 的事件，返回删除条数。 */
    long purgeBefore(long cutoff);

    /** 当前事件总数。 */
    long totalCount();
}
