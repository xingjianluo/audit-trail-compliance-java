package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.github.xingjianluo.audittrailcompliancejava.audit.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditProperties;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditResult;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditRetentionService;
import com.github.xingjianluo.audittrailcompliancejava.audit.InMemoryAuditEventStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 保留策略：过期数据被清理，清理留痕保证历史可追溯。 */
class AuditRetentionServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionServiceTest.class);

    @Test
    void expiredEventsAreCleanedWithTraceableTombstone() {
        InMemoryAuditEventStore store = new InMemoryAuditEventStore();
        AuditProperties props = new AuditProperties();
        props.setRetentionDays(30);
        AuditRetentionService retention = new AuditRetentionService(store, props);

        // 3 条过期（40 天前），2 条有效（1 天前）
        store.save(event("expired-1", Instant.now().minus(40, ChronoUnit.DAYS), AuditResult.SUCCESS));
        store.save(event("expired-2", Instant.now().minus(40, ChronoUnit.DAYS), AuditResult.BUSINESS_ERROR));
        store.save(event("expired-3", Instant.now().minus(40, ChronoUnit.DAYS), AuditResult.BUSINESS_ERROR));
        store.save(event("valid-1", Instant.now().minus(1, ChronoUnit.DAYS), AuditResult.SUCCESS));
        store.save(event("valid-2", Instant.now().minus(1, ChronoUnit.DAYS), AuditResult.VALIDATION_FAILED));
        log.info("输入事件: 3 条过期(40天前) + 2 条有效(1天前), 保留策略={}天", props.getRetentionDays());

        int removed = retention.cleanup();
        List<AuditEvent> remaining = store.findByTimeRangeAndActor(null, null, null);
        List<AuditEvent> tombstones = remaining.stream()
                .filter(e -> AuditRetentionService.CLEANUP_ACTION.equals(e.getAction()))
                .toList();
        List<AuditEvent> business = remaining.stream()
                .filter(e -> !AuditRetentionService.CLEANUP_ACTION.equals(e.getAction()))
                .toList();

        log.info("判定依据: 删除数={} 应为 3; 剩余业务记录={} 应为 2; 留痕事件数={} 应为 1",
                removed, business.size(), tombstones.size());
        assertThat(removed).isEqualTo(3);
        assertThat(business).hasSize(2);
        assertThat(tombstones).hasSize(1);

        // 留痕事件包含策略、截止点、数量、分类统计与指纹，历史可追溯
        String detail = tombstones.get(0).getDetail();
        log.info("判定依据: 留痕详情应含策略/数量/指纹, 实际: {}", detail);
        assertThat(detail).contains("retentionDays=30", "removed=3", "fingerprint=");

        // 有效记录不受清理影响，仍然完整可解释
        assertThat(business.stream().map(AuditEvent::getEventId))
                .containsExactlyInAnyOrder("valid-1", "valid-2");

        // 再次清理：留痕事件本身永不被清理
        int removedAgain = retention.cleanup();
        log.info("判定依据: 二次清理删除数={} 应为 0, 留痕事件仍在", removedAgain);
        assertThat(removedAgain).isEqualTo(0);
        toBe(AuditRetentionService.CLEANUP_ACTION, store);
    }

    private void toBe(String cleanupAction, InMemoryAuditEventStore store) {
        long tombstones = store.findByTimeRangeAndActor(null, null, null).stream()
                .filter(e -> cleanupAction.equals(e.getAction())).count();
        assertThat(tombstones).isEqualTo(1);
    }

    private AuditEvent event(String id, Instant timestamp, AuditResult result) {
        AuditEvent e = new AuditEvent();
        e.setEventId(id);
        e.setTimestamp(timestamp);
        e.setActor("tester");
        e.setAction("POST /api/demo/transfer");
        e.setTarget("/api/demo/transfer");
        e.setResult(result);
        return e;
    }
}
