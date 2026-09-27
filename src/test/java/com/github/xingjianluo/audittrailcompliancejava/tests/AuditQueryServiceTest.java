package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.github.xingjianluo.audittrailcompliancejava.audit.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditQueryService;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditResult;
import com.github.xingjianluo.audittrailcompliancejava.audit.InMemoryAuditEventStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 查询：按时间范围与操作者过滤，分页结果稳定且可复现。 */
class AuditQueryServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AuditQueryServiceTest.class);

    @Test
    void paginationIsStableAndReproducible() {
        InMemoryAuditEventStore store = new InMemoryAuditEventStore();
        AuditQueryService query = new AuditQueryService(store);
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        // 同一时刻多条记录，验证 tie-break 也稳定
        for (int i = 0; i < 25; i++) {
            store.save(event("e-" + i, base, i % 2 == 0 ? "alice" : "bob"));
        }
        log.info("输入事件: 25 条相同时间戳的记录(alice/bob 交替), 按 page=0..2, size=10 查询");

        List<AuditEvent> page0 = query.query(null, null, null, 0, 10);
        List<AuditEvent> page1 = query.query(null, null, null, 1, 10);
        List<AuditEvent> page2 = query.query(null, null, null, 2, 10);
        log.info("判定依据: 三页大小应为 10/10/5, 实际 {}/{}/{}", page0.size(), page1.size(), page2.size());
        assertThat(page0).hasSize(10);
        assertThat(page1).hasSize(10);
        assertThat(page2).hasSize(5);

        // 页间无重叠、无遗漏，且按 sequence 单调
        List<String> allIds = new java.util.ArrayList<>();
        page0.forEach(e -> allIds.add(e.getEventId()));
        page1.forEach(e -> allIds.add(e.getEventId()));
        page2.forEach(e -> allIds.add(e.getEventId()));
        log.info("判定依据: 三页合并去重后应含 25 个不同 eventId, 实际 {}", new java.util.HashSet<>(allIds).size());
        assertThat(allIds).doesNotHaveDuplicates().hasSize(25);

        // 可复现：同样查询再跑一次，结果完全一致
        List<AuditEvent> page1Again = query.query(null, null, null, 1, 10);
        log.info("判定依据: 重复查询 page=1 的 eventId 序列应完全一致: {} vs {}",
                page1.stream().map(AuditEvent::getEventId).toList(),
                page1Again.stream().map(AuditEvent::getEventId).toList());
        assertThat(page1Again.stream().map(AuditEvent::getEventId).toList())
                .containsExactlyElementsOf(page1.stream().map(AuditEvent::getEventId).toList());
    }

    @Test
    void filtersByTimeRangeAndActor() {
        InMemoryAuditEventStore store = new InMemoryAuditEventStore();
        AuditQueryService query = new AuditQueryService(store);
        Instant now = Instant.now();
        store.save(event("old", now.minus(2, ChronoUnit.HOURS), "alice"));
        store.save(event("in-range-alice", now.minus(30, ChronoUnit.MINUTES), "alice"));
        store.save(event("in-range-bob", now.minus(10, ChronoUnit.MINUTES), "bob"));

        Instant from = now.minus(1, ChronoUnit.HOURS);
        List<AuditEvent> inRange = query.query(from, now, null, 0, 10);
        List<AuditEvent> aliceOnly = query.query(from, now, "alice", 0, 10);
        log.info("输入事件: 3 条记录(old/in-range-alice/in-range-bob), 查询窗口=最近1小时");
        log.info("判定依据: 时间窗内应有 2 条(实际 {}), alice 过滤后应有 1 条(实际 {})",
                inRange.size(), aliceOnly.size());
        assertThat(inRange.stream().map(AuditEvent::getEventId))
                .containsExactly("in-range-alice", "in-range-bob");
        assertThat(aliceOnly.stream().map(AuditEvent::getEventId))
                .containsExactly("in-range-alice");
    }

    private AuditEvent event(String id, Instant timestamp, String actor) {
        AuditEvent e = new AuditEvent();
        e.setEventId(id);
        e.setTimestamp(timestamp);
        e.setActor(actor);
        e.setAction("GET /api/demo/ok");
        e.setTarget("/api/demo/ok");
        e.setResult(AuditResult.SUCCESS);
        return e;
    }
}
