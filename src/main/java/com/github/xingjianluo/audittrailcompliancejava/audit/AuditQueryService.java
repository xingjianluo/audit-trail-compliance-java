package com.github.xingjianluo.audittrailcompliancejava.audit;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 审计查询：按时间范围与操作者分页查询。
 * 底层存储按 (timestamp, sequence) 排序，分页结果稳定且可复现。
 */
@Service
public class AuditQueryService {

    private final AuditEventStore store;

    public AuditQueryService(AuditEventStore store) {
        this.store = store;
    }

    public List<AuditEvent> query(Instant from, Instant to, String actor, int page, int size) {
        if (page < 0 || size <= 0) {
            return List.of();
        }
        List<AuditEvent> all = store.findByTimeRangeAndActor(from, to, actor);
        int fromIndex = Math.min(page * size, all.size());
        int toIndex = Math.min(fromIndex + size, all.size());
        return Collections.unmodifiableList(all.subList(fromIndex, toIndex));
    }

    public long total(Instant from, Instant to, String actor) {
        return store.findByTimeRangeAndActor(from, to, actor).size();
    }
}
