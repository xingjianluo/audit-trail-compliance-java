package com.github.xingjianluo.audittrailcompliancejava.audit;

import java.time.Instant;
import java.util.List;

/** 审计存储抽象（本地模拟审计库）。 */
public interface AuditEventStore {
    void save(AuditEvent event);
    List<AuditEvent> findByTimeRangeAndActor(Instant from, Instant to, String actor);
    /** 按事件身份精确删除一条记录。 */
    boolean remove(AuditEvent event);
    int deleteExpired(Instant cutoff);
    long count();
}
