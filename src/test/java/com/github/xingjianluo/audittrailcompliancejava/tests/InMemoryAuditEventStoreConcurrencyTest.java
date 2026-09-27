package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.github.xingjianluo.audittrailcompliancejava.audit.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditResult;
import com.github.xingjianluo.audittrailcompliancejava.audit.InMemoryAuditEventStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 并发写入：高并发下不丢失、不错乱、不重复记账。 */
class InMemoryAuditEventStoreConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAuditEventStoreConcurrencyTest.class);

    private static final int THREADS = 16;
    private static final int PER_THREAD = 250;

    @Test
    void concurrentWritesAreNotLostDuplicatedOrDisordered() throws InterruptedException {
        InMemoryAuditEventStore store = new InMemoryAuditEventStore();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < PER_THREAD; i++) {
                        AuditEvent e = new AuditEvent();
                        e.setEventId("thread-" + threadId + "-event-" + i);
                        e.setTimestamp(Instant.now());
                        e.setActor("user-" + threadId);
                        e.setAction("POST /api/demo/transfer");
                        e.setTarget("/api/demo/transfer");
                        e.setResult(AuditResult.SUCCESS);
                        store.save(e);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        go.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        int expected = THREADS * PER_THREAD;
        List<AuditEvent> all = store.findByTimeRangeAndActor(null, null, null);
        log.info("输入事件: {} 个线程各写入 {} 条, 共 {} 条", THREADS, PER_THREAD, expected);
        log.info("判定依据: 实际总数={} 应等于 {}", all.size(), expected);
        assertThat(all).hasSize(expected);
        assertThat(store.count()).isEqualTo(expected);

        // 无重复记账：eventId 与 sequence 均唯一
        Set<String> ids = new HashSet<>();
        Set<Long> seqs = new HashSet<>();
        for (AuditEvent e : all) {
            ids.add(e.getEventId());
            seqs.add(e.getSequence());
        }
        log.info("判定依据: 唯一 eventId 数={}, 唯一 sequence 数={}, 均应等于 {}", ids.size(), seqs.size(), expected);
        assertThat(ids).hasSize(expected);
        assertThat(seqs).hasSize(expected);

        // 按操作者过滤结果完整
        List<AuditEvent> user3 = store.findByTimeRangeAndActor(null, null, "user-3");
        log.info("判定依据: actor=user-3 的记录数={}, 应等于 {}", user3.size(), PER_THREAD);
        assertThat(user3).hasSize(PER_THREAD);
    }
}
