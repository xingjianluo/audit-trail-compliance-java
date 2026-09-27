package com.github.xingjianluo.audittrailcompliancejava.web;

import com.github.xingjianluo.audittrailcompliancejava.audit.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditEventPublisher;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditResult;
import com.github.xingjianluo.audittrailcompliancejava.audit.SensitiveDataMasker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计拦截器：覆盖全部对外接口，成功与失败均留痕。
 * 记录谁、何时、做了什么、针对哪个对象、结果如何、耗时多久；
 * 请求中的敏感信息脱敏后才写入审计事件。
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    /** 全局异常处理器写入的分类结果，优先于状态码推断。 */
    public static final String ATTR_AUDIT_RESULT = "audit.result";
    private static final String ATTR_START_NANOS = "audit.startNanos";
    private static final String HEADER_USER = "X-User";

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    private final AuditEventPublisher publisher;
    private final AuditExceptionClassifier classifier;
    private final SensitiveDataMasker masker;

    public AuditInterceptor(AuditEventPublisher publisher,
                            AuditExceptionClassifier classifier,
                            SensitiveDataMasker masker) {
        this.publisher = publisher;
        this.classifier = classifier;
        this.masker = masker;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START_NANOS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            AuditEvent event = buildEvent(request, response, ex);
            publisher.publish(event);
        } catch (Throwable t) {
            // 审计逻辑自身出错也不能影响主请求
            log.error("[AUDIT-DEGRADED] interceptor-error reason={}", t.toString());
        }
    }

    private AuditEvent buildEvent(HttpServletRequest request, HttpServletResponse response, Exception ex) {
        long startNanos = startNanos(request);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        AuditEvent event = new AuditEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setTimestamp(Instant.now());
        event.setActor(actor(request));
        event.setAction(request.getMethod() + " " + request.getRequestURI());
        event.setTarget(request.getRequestURI());
        event.setHttpMethod(request.getMethod());
        event.setPath(request.getRequestURI());
        event.setClientIp(clientIp(request));
        event.setResult(resolveResult(request, response, ex));
        event.setDurationMs(durationMs);
        event.setDetail(buildDetail(request, ex));
        return event;
    }

    private AuditResult resolveResult(HttpServletRequest request, HttpServletResponse response, Exception ex) {
        Object classified = request.getAttribute(ATTR_AUDIT_RESULT);
        if (classified instanceof AuditResult result) {
            return result;
        }
        if (ex != null) {
            return classifier.classify(ex);
        }
        int status = response.getStatus();
        if (status == 401) {
            return AuditResult.AUTHENTICATION_FAILED;
        }
        if (status == 403) {
            return AuditResult.AUTHORIZATION_FAILED;
        }
        if (status >= 400 && status < 500) {
            return AuditResult.VALIDATION_FAILED;
        }
        if (status >= 500) {
            return AuditResult.SYSTEM_ERROR;
        }
        return AuditResult.SUCCESS;
    }

    private String buildDetail(HttpServletRequest request, Exception ex) {
        StringBuilder detail = new StringBuilder();
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            detail.append("query=").append(masker.mask(query));
        }
        String params = maskedParams(request);
        if (!params.isEmpty()) {
            if (!detail.isEmpty()) {
                detail.append(' ');
            }
            detail.append("params=").append(params);
        }
        if (ex != null) {
            if (!detail.isEmpty()) {
                detail.append(' ');
            }
            detail.append("exception=").append(ex.getClass().getSimpleName())
                    .append(":").append(masker.mask(String.valueOf(ex.getMessage())));
        }
        return detail.toString();
    }

    /** 表单/查询参数统一脱敏后拼接为 k=v&k=v。 */
    private String maskedParams(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder();
        request.getParameterMap().forEach((name, values) -> {
            for (String value : values) {
                if (!sb.isEmpty()) {
                    sb.append('&');
                }
                sb.append(name).append('=').append(value == null ? "" : value);
            }
        });
        return masker.mask(sb.toString());
    }

    private String actor(HttpServletRequest request) {
        String user = request.getHeader(HEADER_USER);
        return (user == null || user.isBlank()) ? "anonymous" : user;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private long startNanos(HttpServletRequest request) {
        Object start = request.getAttribute(ATTR_START_NANOS);
        return start instanceof Long l ? l : System.nanoTime();
    }
}
