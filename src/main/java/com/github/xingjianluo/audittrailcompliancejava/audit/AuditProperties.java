package com.github.xingjianluo.audittrailcompliancejava.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 审计配置：队列容量、保留天数等。 */
@Component
@ConfigurationProperties(prefix = "audit")
public class AuditProperties {
    private int queueCapacity = 10000;
    private int retentionDays = 30;

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
}
