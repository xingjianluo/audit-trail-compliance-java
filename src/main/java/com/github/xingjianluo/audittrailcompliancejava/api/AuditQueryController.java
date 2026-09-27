package com.github.xingjianluo.audittrailcompliancejava.api;

import com.github.xingjianluo.audittrailcompliancejava.audit.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 审计查询接口：按时间范围与操作者分页查询，分页结果稳定可复现。 */
@RestController
@RequestMapping("/api/audit")
public class AuditQueryController {

    private final AuditQueryService queryService;

    public AuditQueryController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/events")
    public Map<String, Object> query(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<AuditEvent> events = queryService.query(from, to, actor, page, size);
        return Map.of(
                "page", page,
                "size", size,
                "total", queryService.total(from, to, actor),
                "events", events.stream().map(this::toView).toList());
    }

    private Map<String, Object> toView(AuditEvent e) {
        return Map.of(
                "eventId", e.getEventId(),
                "sequence", e.getSequence(),
                "timestamp", e.getTimestamp().toString(),
                "actor", e.getActor(),
                "action", e.getAction(),
                "target", e.getTarget(),
                "result", e.getResult().name(),
                "durationMs", e.getDurationMs(),
                "clientIp", e.getClientIp() == null ? "" : e.getClientIp(),
                "detail", e.getDetail() == null ? "" : e.getDetail());
    }
}
