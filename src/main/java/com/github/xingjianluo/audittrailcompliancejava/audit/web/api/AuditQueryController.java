package com.github.xingjianluo.audittrailcompliancejava.audit.web.api;

import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEventPage;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEventQuery;
import com.github.xingjianluo.audittrailcompliancejava.audit.web.AuditQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审计记录查询接口（需要 ADMIN，鉴权在 AuditFilter 完成）。 */
@RestController
public class AuditQueryController {

    private final AuditQueryService queryService;

    public AuditQueryController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/api/audit/events")
    public AuditEventPage query(@RequestParam(required = false) Long startTime,
                                @RequestParam(required = false) Long endTime,
                                @RequestParam(required = false) String actorId,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(defaultValue = "20") int pageSize) {
        if (startTime != null && endTime != null && startTime > endTime) {
            throw new IllegalArgumentException("startTime 不能晚于 endTime");
        }
        return queryService.query(new AuditEventQuery(startTime, endTime, actorId, cursor, pageSize));
    }
}
