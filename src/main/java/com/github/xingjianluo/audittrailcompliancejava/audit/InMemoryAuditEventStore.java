package com.github.xingjianluo.audittrailcompliancejava.audit;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全的本地内存审计库实现（模拟审计库）。
 * 写入时分配单调递增序号，保证高并发下查询排序稳定、可复现。
 */
@Component
public class InMemoryAuditEventStore implements AuditEventStore {

    private final ConcurrentLinkedDeque<AuditEvent> events = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public void save(AuditEvent event) {
        event.setSequence(sequence.incrementAndGet());
        events.addLast(event);
    }

    /**
     * 按时间范围与操作者过滤，按 (timestamp, sequence) 排序，保证分页稳定。
     */
    @Override
    public List<AuditEvent> findByTimeRangeAndActor(Instant from, Instant to, String actor) {
        List<AuditEvent> matched = new ArrayList<>();
        for (AuditEvent e : events) {
            if (from != null && e.getTimestamp().isBefore(from)) {
                continue;
            }
            if (to != null && e.getTimestamp().isAfter(to)) {
                continue;
            }
            if (actor != null && !actor.isBlank() && !actor.equals(e.getActor())) {
                continue;
            }
            matched.add(e);
        }
        matched.sort(Comparator.comparing(AuditEvent::getTimestamp)
                .thenComparingLong(AuditEvent::getSequence));
        return matched;
    }

    @Override
    public boolean remove(AuditEvent event) {
        return events.remove(event);
    }

    @Override
    public int deleteExpired(Instant cutoff) {
        int[] removed = {0};
        events.removeIf(e -> {
            if (e.getTimestamp().isBefore(cutoff)) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }

    @Override
    public long count() {
        return events.size();
    }
}
