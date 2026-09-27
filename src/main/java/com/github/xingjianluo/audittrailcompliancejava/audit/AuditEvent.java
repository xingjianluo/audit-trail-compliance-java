package com.github.xingjianluo.audittrailcompliancejava.audit;

import java.time.Instant;

/** 审计事件模型：谁、何时、做了什么、针对谁、结果、耗时。 */
public class AuditEvent {
    private String eventId;
    private long sequence;
    private Instant timestamp;
    private String actor;
    private String action;
    private String target;
    private AuditResult result;
    private long durationMs;
    private String clientIp;
    private String httpMethod;
    private String path;
    private String detail;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public AuditResult getResult() { return result; }
    public void setResult(AuditResult result) { this.result = result; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
