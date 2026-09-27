package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.github.xingjianluo.audittrailcompliancejava.audit.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditEventPublisher;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditEventStore;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditProperties;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditResult;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditWriteOutcome;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditWriteStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 存储不可用时：主流程不受影响，且留下可区分的降级信号。 */
class AuditEventPublisherDegradationTest {

    private static final Logger log = LoggerFactory.getLogger(AuditEventPublisherDegradationTest.class);

    /** 模拟不可用的审计库：写入必抛异常。 */
    static class UnavailableStore implements AuditEventStore {
        @Override
        public void save(AuditEvent event) {
            throw new IllegalStateException("audit store unavailable");
        }

        @Override
        public List<AuditEvent> findByTimeRangeAndActor(Instant from, Instant to, String actor) {
            return List.of();
        }

        @Override
        public boolean remove(AuditEvent event) {
            return false;
        }

        @Override
        public int deleteExpired(Instant cutoff) {
            return 0;
        }

        @Override
        public long count() {
            return 0;
        }
    }

    @Test
    void storeUnavailableTriggersDegradedSignalWithoutBreakingCaller() throws Exception {
        AuditProperties props = new AuditProperties();
        props.setQueueCapacity(100);
        AuditEventPublisher publisher = new AuditEventPublisher(new UnavailableStore(), props);
        try {
            int total = 20;
            log.info("输入事件: 向不可用的审计库发布 {} 条审计事件", total);
            for (int i = 0; i < total; i++) {
                AuditEvent e = event("degraded-" + i);
                // 主请求线程发布绝不能抛异常
                assertThatCode(() -> publisher.publish(e)).doesNotThrowAnyException();
            }
            publisher.awaitDrain(5000);
            // 等待工作线程把队列事件全部降级处理
            long deadline = System.currentTimeMillis() + 5000;
            while (publisher.getDegradedCount() < total && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            log.info("判定依据: 降级计数={} 应等于 {}, 降级缓冲={} 应等于 {}",
                    publisher.getDegradedCount(), total, publisher.getFallbackBufferedCount(), total);
            assertThat(publisher.getDegradedCount()).isEqualTo(total);
            assertThat(publisher.getFallbackBufferedCount()).isEqualTo(total);
        } finally {
            publisher.shutdown();
        }
    }

    @Test
    void queueFullReturnsDistinguishableDegradedOutcome() throws InterruptedException {
        AuditProperties props = new AuditProperties();
        props.setQueueCapacity(1);
        // 写入缓慢的存储：占住工作线程，让队列填满
        AuditEventStore slowStore = new AuditEventStore() {
            @Override
            public void save(AuditEvent event) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public List<AuditEvent> findByTimeRangeAndActor(Instant from, Instant to, String actor) {
                return List.of();
            }

            @Override
            public boolean remove(AuditEvent event) {
                return false;
            }

            @Override
            public int deleteExpired(Instant cutoff) {
                return 0;
            }

            @Override
            public long count() {
                return 0;
            }
        };
        AuditEventPublisher publisher = new AuditEventPublisher(slowStore, props);
        try {
            publisher.publish(event("first"));      // 被工作线程取走，写入阻塞
            Thread.sleep(100);
            publisher.publish(event("second"));     // 占满容量为 1 的队列
            AuditWriteOutcome outcome = publisher.publish(event("third")); // 队列已满 → 降级

            log.info("输入事件: 队列容量=1, 连续发布 3 条事件, 第 3 条触发降级");
            log.info("判定依据: 第 3 条写入状态={} 应为 DEGRADED_BUFFERED, 原因={}",
                    outcome.getStatus(), outcome.getReason());
            assertThat(outcome.getStatus()).isEqualTo(AuditWriteStatus.DEGRADED_BUFFERED);
            assertThat(outcome.getReason()).isEqualTo("queue-full");
            assertThat(publisher.getDegradedCount()).isGreaterThanOrEqualTo(1);
        } finally {
            publisher.shutdown();
        }
    }

    private AuditEvent event(String id) {
        AuditEvent e = new AuditEvent();
        e.setEventId(UUID.randomUUID().toString());
        e.setTimestamp(Instant.now());
        e.setActor("tester");
        e.setAction("POST /api/demo/transfer");
        e.setTarget("/api/demo/transfer");
        e.setResult(AuditResult.SUCCESS);
        e.setDetail("case=" + id);
        return e;
    }
}
