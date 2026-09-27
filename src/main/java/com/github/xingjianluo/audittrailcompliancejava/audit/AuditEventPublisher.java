package com.github.xingjianluo.audittrailcompliancejava.audit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步审计写入器：审计写入与业务处理解耦。
 * <p>
 * 主请求线程只做非阻塞入队，绝不因审计存储不可用或变慢而改变主请求结果；
 * 降级时通过专用日志标记 [AUDIT-DEGRADED] 与降级计数器留下可区分信号，不静默吞掉。
 */
@Component
public class AuditEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditEventPublisher.class);
    private static final int FALLBACK_BUFFER_CAPACITY = 1000;

    private final BlockingQueue<AuditEvent> queue;
    private final AuditEventStore store;
    private final List<AuditEvent> fallbackBuffer = new CopyOnWriteArrayList<>();
    private final AtomicLong degradedCount = new AtomicLong(0);
    private final Thread worker;
    private volatile boolean running = true;

    public AuditEventPublisher(AuditEventStore store, AuditProperties properties) {
        this.store = store;
        this.queue = new ArrayBlockingQueue<>(properties.getQueueCapacity());
        this.worker = new Thread(this::drainLoop, "audit-event-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 非阻塞发布：入队失败（队列满）时降级并留下信号，绝不抛给调用方。
     */
    public AuditWriteOutcome publish(AuditEvent event) {
        if (!running) {
            return degrade(event, "publisher-shutting-down");
        }
        if (queue.offer(event)) {
            return new AuditWriteOutcome(AuditWriteStatus.PERSISTED, "queued");
        }
        return degrade(event, "queue-full");
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            try {
                AuditEvent event = queue.poll(200, TimeUnit.MILLISECONDS);
                if (event != null) {
                    writeToStore(event);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // 工作线程自身不能死，记录后继续
                log.error("[AUDIT-DEGRADED] writer-loop-error reason={}", t.toString());
            }
        }
    }

    private void writeToStore(AuditEvent event) {
        try {
            store.save(event);
        } catch (Throwable t) {
            degrade(event, "store-unavailable: " + t.getMessage());
        }
    }

    private AuditWriteOutcome degrade(AuditEvent event, String reason) {
        degradedCount.incrementAndGet();
        AuditWriteStatus status;
        if (fallbackBuffer.size() < FALLBACK_BUFFER_CAPACITY) {
            fallbackBuffer.add(event);
            status = AuditWriteStatus.DEGRADED_BUFFERED;
        } else {
            status = AuditWriteStatus.DEGRADED_DROPPED;
        }
        log.warn("[AUDIT-DEGRADED] status={} reason={} eventId={} actor={} action={} path={}",
                status, reason, event.getEventId(), event.getActor(),
                event.getAction(), event.getPath());
        return new AuditWriteOutcome(status, reason);
    }

    public long getDegradedCount() {
        return degradedCount.get();
    }

    public int getPendingCount() {
        return queue.size();
    }

    public int getFallbackBufferedCount() {
        return fallbackBuffer.size();
    }

    /** 供测试等待队列排空。 */
    public boolean awaitDrain(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty()) {
                return true;
            }
            Thread.sleep(10);
        }
        return queue.isEmpty();
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        worker.interrupt();
    }
}
