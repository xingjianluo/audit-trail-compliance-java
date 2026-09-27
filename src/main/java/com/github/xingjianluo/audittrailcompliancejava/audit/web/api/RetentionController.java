package com.github.xingjianluo.audittrailcompliancejava.audit.web.api;

import com.github.xingjianluo.audittrailcompliancejava.audit.model.RetentionPolicy;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.RetentionPurgeResult;
import com.github.xingjianluo.audittrailcompliancejava.audit.web.ActorContext;
import com.github.xingjianluo.audittrailcompliancejava.audit.web.RetentionService;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.AuditDegradationMetrics;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.AuditDegradationSnapshot;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.AsyncAuditEventWriter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 保留策略查询 / 调整 / 立即清理，以及审计写入健康检查（均需 ADMIN）。 */
@RestController
public class RetentionController {

    private final RetentionService retentionService;
    private final AuditDegradationMetrics metrics;
    private final AsyncAuditEventWriter writer;

    public RetentionController(RetentionService retentionService,
                               AuditDegradationMetrics metrics,
                               AsyncAuditEventWriter writer) {
        this.retentionService = retentionService;
        this.metrics = metrics;
        this.writer = writer;
    }

    @GetMapping("/api/audit/retention")
    public RetentionPolicy current() {
        return retentionService.currentPolicy();
    }

    @GetMapping("/api/audit/retention/history")
    public List<RetentionPolicy> history() {
        return retentionService.policyHistory();
    }

    @PostMapping("/api/audit/retention")
    public RetentionPolicy adjust(@RequestParam long retentionMillis,
                                  @RequestParam(required = false) String remark) {
        ActorContext actor = ActorContext.get();
        String by = actor == null ? "anonymous" : actor.userId();
        return retentionService.adjustPolicy(retentionMillis, by, remark);
    }

    @PostMapping("/api/audit/retention/purge")
    public RetentionPurgeResult purge() {
        ActorContext actor = ActorContext.get();
        return retentionService.purgeExpired(
                actor == null ? null : actor.userId(),
                actor == null ? null : actor.userName());
    }

    /** 审计写入健康度：任何降级类别都能在这里被区分地观测到。 */
    @GetMapping("/api/audit/retention/health")
    public Map<String, Object> health() {
        AuditDegradationSnapshot snap = metrics.snapshot(writer.queueSize());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enqueued", snap.enqueued());
        body.put("written", snap.written());
        body.put("retried", snap.retried());
        body.put("failed", snap.failed());
        body.put("queueRejected", snap.queueRejected());
        body.put("slowWrites", snap.slowWrites());
        body.put("deadLetter", snap.deadLetter());
        body.put("queueSize", snap.queueSize());
        body.put("degraded", snap.degraded());
        body.put("parkedEventIds", writer.parkedEventIds());
        return body;
    }
}
