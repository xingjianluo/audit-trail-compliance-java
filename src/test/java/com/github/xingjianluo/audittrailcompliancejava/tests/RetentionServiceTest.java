package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditOutcome;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.RetentionPolicy;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.RetentionPurgeResult;
import com.github.xingjianluo.audittrailcompliancejava.audit.storage.AuditEventRepository;
import com.github.xingjianluo.audittrailcompliancejava.audit.storage.InMemoryAuditEventRepository;
import com.github.xingjianluo.audittrailcompliancejava.audit.web.RetentionService;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.AuditDegradationMetrics;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.AsyncAuditEventWriter;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.EventIdGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保留策略测试：版本化、只删过期、清理自身留痕、策略调整可追溯。
 */
class RetentionServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RetentionServiceTest.class);

    private AuditEvent event(String id, long ts, String actor) {
        return new AuditEvent(id, ts, actor, actor, "127.0.0.1", "GET", "/x", "READ",
                "DOCUMENT", "o", AuditOutcome.SUCCESS, null, null, 200, "", "", 1, "t");
    }

    private RetentionService newService(AuditEventRepository repo, AsyncAuditEventWriter writer) {
        return new RetentionService(repo, writer, new EventIdGenerator());
    }

    @Test
    void policyAdjustmentsAreVersionedAndNeverOverwriteHistory() {
        InMemoryAuditEventRepository repo = new InMemoryAuditEventRepository();
        AsyncAuditEventWriter writer = new AsyncAuditEventWriter(repo, new AuditDegradationMetrics());
        RetentionService svc = newService(repo, writer);

        RetentionPolicy v1 = svc.currentPolicy();
        RetentionPolicy v2 = svc.adjustPolicy(30L * 24 * 3600 * 1000, "u-0001", "缩短到30天");
        RetentionPolicy v3 = svc.adjustPolicy(7L * 24 * 3600 * 1000, "u-0001", "缩短到7天");
        List<RetentionPolicy> history = svc.policyHistory();

        log.info("[策略版本] 输入两次调整, 最终判定依据: versions={}, 当前=v{}, 历史不可变={}",
                history.stream().map(p -> "v" + p.version() + "(" + p.retentionMillis() + "ms)").toList(),
                svc.currentPolicy().version(),
                history.get(0).retentionMillis() == v1.retentionMillis()
                        && history.get(1).version() == 2 && history.get(2).version() == 3);

        assertEquals(1, v1.version());
        assertEquals(3, svc.currentPolicy().version());
        assertEquals(7L * 24 * 3600 * 1000, svc.currentPolicy().retentionMillis());
        assertEquals(3, history.size(), "调整只能新增版本，历史版本必须保留");
        assertEquals("u-0001", history.get(2).createdBy());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.adjustPolicy(0, "u-0001", "非法"));
        log.info("[策略校验] 输入 retentionMillis=0, 最终判定依据: {}", ex.getMessage());
        writer.shutdown();
    }

    @Test
    void purgeRemovesOnlyExpired_andPurgeItselfIsAudited_andSurvives() {
        InMemoryAuditEventRepository repo = new InMemoryAuditEventRepository();
        AsyncAuditEventWriter writer = new AsyncAuditEventWriter(repo, new AuditDegradationMetrics());
        RetentionService svc = newService(repo, writer);

        long now = System.currentTimeMillis();
        long day = 24L * 3600 * 1000;
        // 2 条远超 90 天的过期数据 + 2 条近期有效数据
        repo.append(event("old-1", now - 100 * day, "alice"));
        repo.append(event("old-2", now - 95 * day, "alice"));
        repo.append(event("new-1", now - 10 * day, "bob"));
        repo.append(event("new-2", now - day, "bob"));

        RetentionPurgeResult result = svc.purgeExpired("u-0001", "admin");
        // 清理动作产生的审计事件经异步写入器落库
        await().untilAsserted(() -> assertEquals(3, repo.totalCount())); // 2 new + 1 purge-event

        List<AuditEvent> remain = repo.search(null, null, null, null, null, 100);
        boolean purgeEventPresent = remain.stream().anyMatch(e -> "PURGE".equals(e.action()));
        AuditEvent purgeEvent = remain.stream().filter(e -> "PURGE".equals(e.action())).findFirst().orElseThrow();

        log.info("[过期清理] 输入 4 条(2 过期 2 有效)+1 条清理事件, 最终判定依据: purged={}, remaining={}, 有效记录保留={}, 清理留痕={}, 留痕内容={}",
                result.purgedCount(), result.remainingCount(),
                remain.stream().filter(e -> !"PURGE".equals(e.action())).map(AuditEvent::eventId).toList(),
                purgeEventPresent, purgeEvent.requestParamsMasked());

        assertEquals(2, result.purgedCount());
        assertEquals(2, remain.stream().filter(e -> !"PURGE".equals(e.action())).count(),
                "仍在保留期内的记录绝不能被清理");
        assertTrue(purgeEventPresent, "清理动作本身必须留痕");
        assertEquals(AuditOutcome.SUCCESS, purgeEvent.outcome());
        assertEquals(1, purgeEvent.objectId() != null && purgeEvent.objectId().contains("policy-v1") ? 1 : 0,
                "清理事件必须可追溯到所依据的策略版本");

        // 再清理一次：上次的清理事件与有效数据都不能被误删
        RetentionPurgeResult second = svc.purgeExpired("u-0001", "admin");
        await().untilAsserted(() -> assertEquals(4, repo.totalCount()));
        log.info("[重复清理] 再次执行, 最终判定依据: 第二次 purged={}, 总记录={}",
                second.purgedCount(), repo.totalCount());
        assertEquals(0, second.purgedCount(), "没有新的过期数据时不应删除任何记录");

        writer.shutdown();
    }
}
