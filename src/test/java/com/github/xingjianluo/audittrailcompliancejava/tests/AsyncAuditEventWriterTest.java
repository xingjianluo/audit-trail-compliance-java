package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditOutcome;
import com.github.xingjianluo.audittrailcompliancejava.audit.storage.InMemoryAuditEventRepository;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.AuditDegradationMetrics;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.AsyncAuditEventWriter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步审计写入器测试：
 * 1) 并发发布全部落库、保持发布顺序；
 * 2) 存储故障期间 publish 不影响业务（不抛异常），恢复后不丢事件、不乱序，
 *    且 failed/retried 等降级信号可区分；
 * 3) 写入变慢只产生 slowWrites 信号，不与失败混淆。
 */
class AsyncAuditEventWriterTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncAuditEventWriterTest.class);

    private AuditEvent event(String id, long ts) {
        return new AuditEvent(id, ts, "u1", "alice", "127.0.0.1", "GET", "/x", "READ",
                "DOCUMENT", "o1", AuditOutcome.SUCCESS, null, null, 200, "", "", 1, "t");
    }

    @Test
    void concurrentPublish_allWritten_inOrder() throws Exception {
        InMemoryAuditEventRepository repo = new InMemoryAuditEventRepository();
        AuditDegradationMetrics metrics = new AuditDegradationMetrics();
        AsyncAuditEventWriter writer = new AsyncAuditEventWriter(repo, metrics);

        int threads = 8;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        long n = base + i;
                        // 单线程视角 id 按发布顺序递增；全局 ts 唯一
                        writer.publish(event(String.format("evt-%08d", n), 10_000_000L + n));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        int expected = threads * perThread;
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals(expected, repo.totalCount()));

        List<AuditEvent> all = repo.search(null, null, null, null, null, expected + 5);
        boolean sorted = all.stream().sorted(java.util.Comparator
                        .comparingLong(AuditEvent::timestamp).thenComparing(AuditEvent::eventId))
                .toList().equals(all);
        var snap = metrics.snapshot(writer.queueSize());
        log.info("[异步并发] 输入事件: {}, 落库: {}, queueRejected={}, failed={}",
                expected, repo.totalCount(), snap.queueRejected(), snap.failed());
        log.info("[异步并发] 最终判定依据: 不丢失={}, 排序确定={}, 健康={}",
                repo.totalCount() == expected, sorted, !snap.degraded());

        assertEquals(expected, repo.totalCount(), "高并发发布不能丢失");
        assertTrue(sorted, "存储读取序必须确定");
        assertEquals(0, snap.queueRejected());
        assertEquals(0, snap.failed());

        writer.shutdown();
    }

    @Test
    void storageOutage_doesNotChangeBusinessResult_andRecoversWithoutLoss() {
        InMemoryAuditEventRepository repo = new InMemoryAuditEventRepository();
        AuditDegradationMetrics metrics = new AuditDegradationMetrics();
        AsyncAuditEventWriter writer = new AsyncAuditEventWriter(repo, metrics);

        // 1) 先发一批正常事件
        for (int i = 0; i < 20; i++) {
            writer.publish(event(String.format("a-%03d", i), 1_000L + i));
        }
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(20, repo.totalCount()));

        // 2) 存储故障：publish 必须快速返回且不抛异常（模拟业务请求完全不受影响）
        repo.setFault(true);
        long t0 = System.currentTimeMillis();
        for (int i = 20; i < 40; i++) {
            writer.publish(event(String.format("a-%03d", i), 1_000L + i));
        }
        long publishElapsed = System.currentTimeMillis() - t0;

        // 3) 再发后续事件，它们必须排在故障期事件之后，恢复后一起补写（不丢、不乱序）
        for (int i = 40; i < 60; i++) {
            writer.publish(event(String.format("a-%03d", i), 1_000L + i));
        }
        await().pollDelay(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertTrue(repo.totalCount() < 60, "故障期间不应有新增落库"));

        // 4) 恢复
        repo.setFault(false);
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(60, repo.totalCount()));
        // 优雅关闭，强制刷盘完成后再做最终校验（daemon 线程不会提前被测试 JVM 回收）
        writer.shutdown();

        List<AuditEvent> all = repo.search(null, null, null, null, null, 100);
        List<String> ids = all.stream().map(AuditEvent::eventId).sorted().toList();
        var snap = metrics.snapshot(writer.queueSize());

        log.info("[故障降级] 输入事件 60 条（20 正常 + 20 故障期 + 20 后续）, publish 耗时={}ms", publishElapsed);
        log.info("[故障降级] 最终判定依据: 恢复后落库={}, retried={}, failed={}, deadLetter={}, queueRejected={}, degraded={}, 保序={}",
                repo.totalCount(), snap.retried(), snap.failed(), snap.deadLetter(),
                snap.queueRejected(), snap.degraded(), ids.get(0).equals("a-000") && ids.get(59).equals("a-059"));

        assertEquals(60, repo.totalCount(), "存储故障期间的事件恢复后必须补齐，不丢");
        assertEquals("a-000", all.get(0).eventId());
        assertEquals("a-059", all.get(all.size() - 1).eventId(), "故障期事件先于后续事件，严格保序");
        assertTrue(snap.failed() > 0, "必须留下失败类降级信号");
        assertTrue(snap.retried() > 0, "必须能观察到重试");
        assertEquals(0, snap.queueRejected(), "队头挂起期间不应丢弃事件");
        assertTrue(publishElapsed < 1000, "存储故障不能拖慢/改变业务主请求");
    }

    @Test
    void slowStorage_emitsDistinctSlowWriteSignal() {
        InMemoryAuditEventRepository repo = new InMemoryAuditEventRepository();
        repo.setSlowMillis(120); // 超过 100ms 阈值
        AuditDegradationMetrics metrics = new AuditDegradationMetrics();
        AsyncAuditEventWriter writer = new AsyncAuditEventWriter(repo, metrics);

        writer.publish(event("slow-001", 5_000L));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(1, repo.totalCount()));

        var snap = metrics.snapshot(writer.queueSize());
        log.info("[慢写降级] 输入: 存储每次写入延迟 120ms, 最终判定依据: slowWrites={}, failed={}, degraded={}",
                snap.slowWrites(), snap.failed(), snap.degraded());
        assertTrue(snap.slowWrites() >= 1, "变慢要产生独立的 slowWrites 信号");
        assertEquals(0, snap.failed(), "慢写不等于失败");
        assertTrue(snap.degraded(), "慢写属于性能降级，健康检查应可见");

        writer.shutdown();
    }
}
