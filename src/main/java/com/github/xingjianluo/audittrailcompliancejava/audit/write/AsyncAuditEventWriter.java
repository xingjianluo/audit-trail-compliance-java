package com.github.xingjianluo.audittrailcompliancejava.audit.write;

import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.storage.AuditEventRepository;
import com.github.xingjianluo.audittrailcompliancejava.audit.storage.AuditStorageException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 异步审计写入器。
 *
 * <p>解耦与可靠性设计：
 * <ul>
 *   <li>业务线程只调用 {@link #publish}，仅做有界队列入队，快速返回且绝不抛异常，
 *       审计存储故障/变慢不会改变主请求的返回结果；</li>
 *   <li>单线程按 FIFO 顺序写库：单写者 + 存储侧 eventId 幂等，保证并发下不乱序、不重复；</li>
 *   <li>写入失败先退避重试；重试用尽后事件进入“挂起点”，写库线程在它成功前不消费
 *       后续事件（队头阻塞），因此恢复后严格保序，事件不丢；</li>
 *   <li>队列积压到上限时按超时背压，拒绝计入 queueRejected 并打印 ERROR 日志，
 *       是可区分的降级信号，绝不静默吞掉；</li>
 *   <li>单次存储调用耗时超阈值记 slowWrites，与“彻底失败”区分开。</li>
 * </ul>
 */
@Component
public class AsyncAuditEventWriter implements AuditEventSink {

    private static final Logger log = LoggerFactory.getLogger(AsyncAuditEventWriter.class);

    static final int QUEUE_CAPACITY = 4096;
    static final long ENQUEUE_TIMEOUT_MS = 100L;
    static final int MAX_ATTEMPTS = 5;
    static final long[] BACKOFF_MS = {10L, 20L, 50L, 100L, 200L};
    static final long PARK_RETRY_MS = 500L;

    private final AuditEventRepository repository;
    private final AuditDegradationMetrics metrics;
    private final BlockingQueue<AuditEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    /** 写库失败后被挂起的队头事件；非空时优先重试它，保证严格保序。 */
    private final AtomicReference<AuditEvent> parked = new AtomicReference<>();
    /** 曾经被挂起过的事件 id（便于健康检查时解释降级原因），只保留最近 200 个。 */
    private final ConcurrentLinkedQueue<String> parkedEventIds = new ConcurrentLinkedQueue<>();
    private static final int PARK_HISTORY_LIMIT = 200;

    private volatile boolean running = true;
    private final Thread worker;

    public AsyncAuditEventWriter(AuditEventRepository repository, AuditDegradationMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
        this.worker = new Thread(this::runLoop, "audit-event-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void publish(AuditEvent event) {
        if (!running) {
            // 关闭期间兜底直接尝试一次，尽量不丢
            tryWriteOnce(event);
            return;
        }
        boolean offered;
        try {
            offered = queue.offer(event, ENQUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            offered = false;
        }
        if (offered) {
            metrics.recordEnqueued();
            return;
        }
        // 背压：队列持续积压说明存储写入变慢/不可用。显式计数 + ERROR 日志，不静默丢弃。
        metrics.recordQueueRejected();
        log.error("审计队列已满，事件背压拒绝（降级信号）: eventId={}, actor={}, action={}, path={}, queueSize={}",
                event.eventId(), event.actorId(), event.action(), event.path(), queue.size());
    }

    private void runLoop() {
        while (running || !queue.isEmpty()) {
            AuditEvent event = parked.get();
            if (event == null) {
                try {
                    event = queue.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (event == null) {
                    continue;
                }
            }
            boolean ok = writeWithRetry(event);
            if (ok) {
                parked.compareAndSet(event, null);
            } else {
                // 重试用尽：挂起队头，退避后优先重试它，期间不消费后续事件 -> 不丢、不乱序
                if (parked.compareAndSet(null, event)) {
                    metrics.recordFailure();
                    metrics.recordDeadLetter();
                    parkedEventIds.add(event.eventId());
                    while (parkedEventIds.size() > PARK_HISTORY_LIMIT) {
                        parkedEventIds.poll();
                    }
                }
                log.error("审计事件写入失败已挂起，将持续重试: eventId={}, actor={}, action={}",
                        event.eventId(), event.actorId(), event.action());
                sleep(PARK_RETRY_MS);
            }
        }
    }

    private boolean writeWithRetry(AuditEvent event) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long start = System.nanoTime();
            try {
                repository.append(event);
                long elapsed = (System.nanoTime() - start) / 1_000_000L;
                if (elapsed >= AuditDegradationMetrics.SLOW_WRITE_THRESHOLD_MS) {
                    metrics.recordSlowWrite(elapsed);
                    log.warn("审计写入变慢（降级信号）: eventId={}, elapsedMs={}", event.eventId(), elapsed);
                }
                metrics.recordWritten();
                if (attempt > 1) {
                    log.info("审计事件第 {} 次尝试后写入成功: eventId={}", attempt, event.eventId());
                }
                return true;
            } catch (AuditStorageException e) {
                long elapsed = (System.nanoTime() - start) / 1_000_000L;
                if (elapsed >= AuditDegradationMetrics.SLOW_WRITE_THRESHOLD_MS) {
                    metrics.recordSlowWrite(elapsed);
                }
                if (attempt < MAX_ATTEMPTS) {
                    metrics.recordRetry();
                    log.warn("审计写入第 {}/{} 次失败: eventId={}, reason={}",
                            attempt, MAX_ATTEMPTS, event.eventId(), e.getMessage());
                    sleep(BACKOFF_MS[attempt - 1]);
                }
            }
        }
        return false;
    }

    private void tryWriteOnce(AuditEvent event) {
        try {
            repository.append(event);
            metrics.recordWritten();
        } catch (AuditStorageException e) {
            metrics.recordFailure();
            log.error("关闭兜底写入仍失败: eventId={}", event.eventId());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        worker.interrupt();
        try {
            // 给残留事件最多 2 秒刷盘时间
            worker.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 当前队列深度（健康检查用）。 */
    public int queueSize() {
        return queue.size();
    }

    /** 最近被挂起过的事件 id（健康检查用）。 */
    public java.util.List<String> parkedEventIds() {
        return java.util.List.copyOf(parkedEventIds);
    }
}
