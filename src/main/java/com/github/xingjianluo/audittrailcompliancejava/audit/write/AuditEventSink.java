package com.github.xingjianluo.audittrailcompliancejava.audit.write;

import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEvent;

/**
 * 审计事件入口。业务侧只面向该接口发布事件，与实际存储写入解耦。
 */
public interface AuditEventSink {

    /**
     * 发布一条审计事件。该方法必须快速返回、绝不向调用方抛出异常；
     * 存储故障只能体现为可区分的降级信号，不得改变主请求结果。
     */
    void publish(AuditEvent event);
}
