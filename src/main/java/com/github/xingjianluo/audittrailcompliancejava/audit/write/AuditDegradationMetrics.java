package com.github.xingjianluo.audittrailcompliancejava.audit.write;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计写入降级指标。
 *
 * <p>降级不是一个笼统的布尔值，而是由可区分的计数构成：
 * <ul>
 *   <li>{@code failed}/{@code deadLetter}：存储彻底不可用的信号；</li>
 *   <li>{@code queueRejected}：写入速度跟不上、背压丢弃的信号；</li>
 *   <li>{@code slowWrites}：存储变慢但尚未失败的预警信号；</li>
 *   <li>{@code retried}：发生过瞬时故障并自愈。</li>
 * </ul>
 */
@Component
public class AuditDegradationMetrics {

    /** 单次写入耗时超过该阈值记一次慢写（ms）。 */
    public static final long SLOW_WRITE_THRESHOLD_MS = 100L;

    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong queueRejected = new AtomicLong();
    private final AtomicLong slowWrites = new AtomicLong();
    private final AtomicLong deadLetter = new AtomicLong();

    public void recordEnqueued() {
        enqueued.incrementAndGet();
    }

    public void recordWritten() {
        written.incrementAndGet();
    }

    public void recordRetry() {
        retried.incrementAndGet();
    }

    public void recordSlowWrite(long elapsedMs) {
        slowWrites.incrementAndGet();
    }

    public void recordFailure() {
        failed.incrementAndGet();
    }

    public void recordQueueRejected() {
        queueRejected.incrementAndGet();
    }

    public void recordDeadLetter() {
        deadLetter.incrementAndGet();
    }

    public AuditDegradationSnapshot snapshot(int queueSize) {
        long f = failed.get();
        long qr = queueRejected.get();
        long dl = deadLetter.get();
        long sw = slowWrites.get();
        // 任一非健康计数大于 0 即处于降级态；慢写属于“性能降级”预警，也算可区分信号
        boolean degraded = f > 0 || qr > 0 || dl > 0 || sw > 0;
        return new AuditDegradationSnapshot(
                enqueued.get(), written.get(), retried.get(), f, qr, sw, dl, queueSize, degraded);
    }
}
