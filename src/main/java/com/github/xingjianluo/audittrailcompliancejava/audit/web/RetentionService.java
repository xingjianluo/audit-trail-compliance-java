package com.github.xingjianluo.audittrailcompliancejava.audit.web;

import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditOutcome;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.RetentionPolicy;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.RetentionPurgeResult;
import com.github.xingjianluo.audittrailcompliancejava.audit.storage.AuditEventRepository;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.AuditEventSink;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.EventIdGenerator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 保留策略服务。
 *
 * <p>合规要点：
 * <ul>
 *   <li>策略版本化：调整保留时长只新增一个版本，从不修改历史版本。
 *       任何一次历史清理都能回答“当时依据的是哪个版本”；</li>
 *   <li>清理动作本身也是一条审计事件，记录执行人、截止时间、删除条数、剩余条数；</li>
 *   <li>只删除 timestamp 严格早于 cutoff 的记录，且清理事件产生在 cutoff 之后，
 *       永远不会被自己或后续清理误删；有效区间内的历史结论不受影响。</li>
 * </ul>
 */
@Service
public class RetentionService {

    /** 默认保留 90 天。 */
    static final long DEFAULT_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000;

    private final AuditEventRepository repository;
    private final AuditEventSink sink;
    private final EventIdGenerator idGenerator;
    private final CopyOnWriteArrayList<RetentionPolicy> versions = new CopyOnWriteArrayList<>();

    public RetentionService(AuditEventRepository repository, AuditEventSink sink,
                            EventIdGenerator idGenerator) {
        this.repository = repository;
        this.sink = sink;
        this.idGenerator = idGenerator;
        this.versions.add(new RetentionPolicy(1, DEFAULT_RETENTION_MILLIS,
                System.currentTimeMillis(), "system", "初始化默认策略：保留90天"));
    }

    public RetentionPolicy currentPolicy() {
        return versions.get(versions.size() - 1);
    }

    public List<RetentionPolicy> policyHistory() {
        return List.copyOf(versions);
    }

    public RetentionPolicy adjustPolicy(long retentionMillis, String by, String remark) {
        if (retentionMillis <= 0) {
            throw new IllegalArgumentException("retentionMillis 必须为正数");
        }
        RetentionPolicy next = new RetentionPolicy(currentPolicy().version() + 1,
                retentionMillis, System.currentTimeMillis(),
                by == null || by.isBlank() ? "anonymous" : by,
                remark == null ? "" : remark);
        versions.add(next);
        return next;
    }

    public RetentionPurgeResult purgeExpired(String actorId, String actorName) {
        RetentionPolicy policy = currentPolicy();
        long startedAt = System.currentTimeMillis();
        long cutoff = startedAt - policy.retentionMillis();

        long purged = repository.purgeBefore(cutoff);
        long remaining = repository.totalCount();
        long finishedAt = System.currentTimeMillis();

        RetentionPurgeResult result = new RetentionPurgeResult(
                policy.version(), cutoff, purged, remaining, startedAt, finishedAt);

        // 清理动作本身留痕（系统级事件，永不会被 cutoff 清理）
        AuditEvent event = new AuditEvent(
                idGenerator.nextId(startedAt),
                startedAt,
                actorId == null ? "system" : actorId,
                actorName == null ? "system" : actorName,
                null,
                "POST",
                "/internal/audit/retention/purge",
                "PURGE",
                "RETENTION_POLICY",
                "policy-v" + policy.version(),
                AuditOutcome.SUCCESS,
                null,
                null,
                200,
                "{\"policyVersion\":" + policy.version()
                        + ",\"cutoffTime\":" + cutoff
                        + ",\"purgedCount\":" + purged
                        + ",\"remainingCount\":" + remaining + "}",
                null,
                finishedAt - startedAt,
                null
        );
        sink.publish(event);
        return result;
    }
}
