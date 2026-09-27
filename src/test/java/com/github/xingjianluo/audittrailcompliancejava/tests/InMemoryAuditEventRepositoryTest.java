package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditOutcome;
import com.github.xingjianluo.audittrailcompliancejava.audit.storage.AuditEventRepository;
import com.github.xingjianluo.audittrailcompliancejava.audit.storage.AuditStorageException;
import com.github.xingjianluo.audittrailcompliancejava.audit.storage.InMemoryAuditEventRepository;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.EventIdGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储层测试：并发 append 不丢不重、确定性排序、过期清理安全。
 */
class InMemoryAuditEventRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAuditEventRepositoryTest.class);

    private AuditEvent event(String id, long ts, String actor) {
        return new AuditEvent(id, ts, actor, actor, "127.0.0.1", "GET", "/x", "READ",
                "DOCUMENT", "o1", AuditOutcome.SUCCESS, null, null, 200, "", "", 1, "t");
    }

    @Test
    void concurrentAppends_loseNothing_duplicateNothing_stayOrdered() throws Exception {
        InMemoryAuditEventRepository repo = new InMemoryAuditEventRepository();
        EventIdGenerator idGen = new EventIdGenerator();
        int threads = 16;
        int perThread = 500;
        int total = threads * perThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> duplicateRejections = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 每线程独立时间区间：模拟真实调用各自取当前时间，eventId 全局唯一
                        long ts = 1_000_000L + threadIdx * (perThread + 1L) + i;
                        String id = idGen.nextId(ts);
                        try {
                            repo.append(event(id, ts, "u-" + threadIdx));
                        } catch (AuditStorageException dup) {
                            duplicateRejections.add(id);
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        long stored = repo.totalCount();
        List<AuditEvent> all = repo.search(null, null, null, null, null, total + 10);

        log.info("[并发写入] 输入事件数: {} ({} 线程 x {}), 实际落库: {}", total, threads, perThread, stored);
        log.info("[并发写入] 最终判定依据: 不重复={}, 不丢失={}, 排序确定={}",
                duplicateRejections.isEmpty(), stored == total, isSorted(all));

        assertEquals(0, duplicateRejections.size(), "eventId 生成与存储去重不应拒绝任何唯一事件");
        assertEquals(total, stored, "高并发下不能丢失审计事件");
        assertEquals(total, all.size());
        assertTrue(isSorted(all), "结果必须按 (timestamp,eventId) 确定性排序");

        // 同 id 再写一次必须被拒绝（幂等防重，不重复记账）
        AuditStorageException ex = assertThrows(AuditStorageException.class,
                () -> repo.append(event(all.get(0).eventId(), all.get(0).timestamp(), "u-x")));
        log.info("[重复记账] 输入重复 eventId={}, 最终判定依据: 拒绝原因={}",
                all.get(0).eventId(), ex.getMessage());
    }

    @Test
    void purgeDeletesOnlyExpiredAndKeepsRemainingExplainable() {
        AuditEventRepository repo = new InMemoryAuditEventRepository();
        long now = 10_000_000L;
        repo.append(event("e-old-1", now - 200, "u1"));
        repo.append(event("e-old-2", now - 100, "u1"));
        repo.append(event("e-keep-1", now, "u1"));
        repo.append(event("e-keep-2", now + 50, "u2"));

        long cutoff = now; // 严格小于 cutoff 才删
        long purged = repo.purgeBefore(cutoff);
        List<AuditEvent> remaining = repo.search(null, null, null, null, null, 100);

        log.info("[保留清理] 输入: 4 条 (2 条早于 cutoff={}), 最终判定依据 purged={}, remaining={}",
                cutoff, purged, remaining.stream().map(AuditEvent::eventId).collect(Collectors.toList()));

        assertEquals(2L, purged);
        assertEquals(2L, repo.totalCount());
        assertEquals(List.of("e-keep-1", "e-keep-2"),
                remaining.stream().map(AuditEvent::eventId).collect(Collectors.toList()));
    }

    @Test
    void searchFiltersByTimeRangeAndActorWithStableOrder() {
        InMemoryAuditEventRepository repo = new InMemoryAuditEventRepository();
        repo.append(event("0192-a", 100, "alice"));
        repo.append(event("0192-b", 200, "bob"));
        repo.append(event("0192-c", 200, "alice"));
        repo.append(event("0192-d", 300, "alice"));

        List<AuditEvent> page = repo.search(150L, 300L, "alice", null, null, 10);
        List<String> ids = new ArrayList<>(page.stream().map(AuditEvent::eventId).toList());

        log.info("[条件查询] 输入: 时间范围[150,300] actor=alice, 最终判定依据 ids={}", ids);

        assertEquals(List.of("0192-c", "0192-d"), ids, "过滤与确定性排序必须正确");
    }

    private static boolean isSorted(List<AuditEvent> events) {
        for (int i = 1; i < events.size(); i++) {
            AuditEvent a = events.get(i - 1);
            AuditEvent b = events.get(i);
            int cmp = Long.compare(a.timestamp(), b.timestamp());
            if (cmp > 0 || (cmp == 0 && a.eventId().compareTo(b.eventId()) >= 0)) {
                return false;
            }
        }
        return true;
    }
}
