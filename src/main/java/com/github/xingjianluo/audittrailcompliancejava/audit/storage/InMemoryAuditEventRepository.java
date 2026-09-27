package com.github.xingjianluo.audittrailcompliancejava.audit.storage;

import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存审计存储，模拟审计库。
 *
 * <p>并发保证：
 * <ul>
 *   <li>{@link #append} 使用 putIfAbsent：eventId 重复时拒绝，绝不重复记账；</li>
 *   <li>读取侧统一按 (timestamp, eventId) 升序排序，写入顺序无论怎样都不会错乱；</li>
 *   <li>{@link #purgeBefore} 逐项原子删除，清理期间的新写入要么完整可见、要么尚未提交，
 *       不会产生半条记录。</li>
 * </ul>
 *
 * <p>fault / slowMillis 为测试支持开关，用于模拟审计库不可用和写入变慢。
 */
@Repository
public class InMemoryAuditEventRepository implements AuditEventRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAuditEventRepository.class);

    private static final Comparator<AuditEvent> ORDER =
            Comparator.comparingLong(AuditEvent::timestamp)
                    .thenComparing(AuditEvent::eventId);

    private final ConcurrentHashMap<String, AuditEvent> byId = new ConcurrentHashMap<>();

    private volatile boolean fault = false;
    private volatile long slowMillis = 0L;

    /** 测试支持：打开后 append 抛异常，模拟审计库不可用。 */
    public void setFault(boolean fault) {
        this.fault = fault;
    }

    /** 测试支持：让每次 append 睡眠指定毫秒，模拟写入变慢。 */
    public void setSlowMillis(long slowMillis) {
        this.slowMillis = slowMillis;
    }

    @Override
    public void append(AuditEvent event) {
        if (event == null || event.eventId() == null) {
            throw new AuditStorageException("审计事件或 eventId 为空", null);
        }
        long sleep = slowMillis;
        if (sleep > 0) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuditStorageException("写入被中断", e);
            }
        }
        if (fault) {
            throw new AuditStorageException("审计存储不可用（注入故障）", null);
        }
        AuditEvent existing = byId.putIfAbsent(event.eventId(), event);
        if (existing != null) {
            // 幂等防重：同一个 eventId 只能记一次账
            log.warn("重复的审计事件 eventId={} 被拒绝", event.eventId());
            throw new AuditStorageException("重复的审计事件: " + event.eventId(), null);
        }
    }

    @Override
    public List<AuditEvent> search(Long startTime, Long endTime, String actorId,
                                   Long afterTimestamp, Long afterSequence, int limit) {
        long start = startTime == null ? Long.MIN_VALUE : startTime;
        long end = endTime == null ? Long.MAX_VALUE : endTime;
        List<AuditEvent> matched = new ArrayList<>();
        for (AuditEvent e : byId.values()) {
            if (e.timestamp() < start || e.timestamp() > end) {
                continue;
            }
            if (actorId != null && !actorId.isEmpty() && !actorId.equals(e.actorId())) {
                continue;
            }
            if (afterTimestamp != null && afterSequence != null) {
                long seq = sequenceOf(e.eventId());
                int cmp = Long.compare(e.timestamp(), afterTimestamp);
                if (cmp < 0 || (cmp == 0 && Long.compare(seq, afterSequence) <= 0)) {
                    continue;
                }
            }
            matched.add(e);
        }
        matched.sort(ORDER);
        if (matched.size() > limit) {
            return new ArrayList<>(matched.subList(0, limit));
        }
        return matched;
    }

    @Override
    public long count(Long startTime, Long endTime, String actorId) {
        long start = startTime == null ? Long.MIN_VALUE : startTime;
        long end = endTime == null ? Long.MAX_VALUE : endTime;
        long n = 0;
        for (AuditEvent e : byId.values()) {
            if (e.timestamp() < start || e.timestamp() > end) {
                continue;
            }
            if (actorId != null && !actorId.isEmpty() && !actorId.equals(e.actorId())) {
                continue;
            }
            n++;
        }
        return n;
    }

    @Override
    public long purgeBefore(long cutoff) {
        long[] removed = {0L};
        byId.values().removeIf(e -> {
            if (e.timestamp() < cutoff) {
                removed[0]++;
                return true;
            }
            return false;
        });
        log.info("审计保留策略清理: cutoff={}, purged={}, remaining={}", cutoff, removed[0], byId.size());
        return removed[0];
    }

    /** 从 eventId 中解析全局序号部分。 */
    static long sequenceOf(String eventId) {
        int sep = eventId.indexOf('-');
        return Long.parseUnsignedLong(eventId.substring(sep + 1), 16);
    }

    @Override
    public long totalCount() {
        return byId.size();
    }
}
