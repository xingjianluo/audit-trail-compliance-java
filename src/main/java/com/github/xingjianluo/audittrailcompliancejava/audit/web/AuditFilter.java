package com.github.xingjianluo.audittrailcompliancejava.audit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditOutcome;
import com.github.xingjianluo.audittrailcompliancejava.audit.mask.SensitiveDataMasker;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.AuditEventSink;
import com.github.xingjianluo.audittrailcompliancejava.audit.write.EventIdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.UUID;

/**
 * 审计主过滤器：每一次进入系统的接口调用都在这里被完整、可解释地记录。
 *
 * <p>记录内容（谁/何时/做了什么/对哪个对象/结果/耗时），以及失败分类规则，
 * 详见 docs/audit.md。过滤器自身完成认证与鉴权，失败也会落审计事件。
 */
@Component
@Order(1)
public class AuditFilter extends OncePerRequestFilter {

    private final AuditEventSink sink;
    private final SensitiveDataMasker masker;
    private final EventIdGenerator idGenerator;
    private final AuthTokenTable tokenTable;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditFilter(AuditEventSink sink, SensitiveDataMasker masker,
                       EventIdGenerator idGenerator, AuthTokenTable tokenTable) {
        this.sink = sink;
        this.masker = masker;
        this.idGenerator = idGenerator;
        this.tokenTable = tokenTable;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put("traceId", traceId);

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        AuditOutcome outcome = null;
        String errorCode = null;
        String errorMessage = null;
        ActorContext actor = null;

        String rawToken = extractBearerToken(request);
        boolean rawTokenSeen = rawToken != null;

        try {
            // 1) 认证
            var principalOpt = tokenTable.authenticate(rawToken);
            if (principalOpt.isEmpty()) {
                outcome = AuditOutcome.AUTHN_FAILED;
                errorCode = "AUTHN_FAILED";
                errorMessage = rawToken == null ? "缺少认证信息" : "认证凭据无效";
                writeError(wrappedResponse, HttpServletResponse.SC_UNAUTHORIZED,
                        errorCode, errorMessage, traceId);
            } else {
                var principal = principalOpt.get();
                actor = new ActorContext(principal.userId(), principal.userName(),
                        principal.role(), clientIp(request));
                // 2) 鉴权：审计/保留策略管理接口仅 ADMIN
                if (isAdminOnlyPath(request.getRequestURI()) && !"ADMIN".equals(principal.role())) {
                    outcome = AuditOutcome.AUTHZ_FAILED;
                    errorCode = "AUTHZ_FAILED";
                    errorMessage = "需要 ADMIN 权限";
                    writeError(wrappedResponse, HttpServletResponse.SC_FORBIDDEN,
                            errorCode, errorMessage, traceId);
                } else {
                    ActorContext.set(actor);
                    try {
                        filterChain.doFilter(cachedRequest, wrappedResponse);
                    } catch (AuthnException a) {
                        outcome = AuditOutcome.AUTHN_FAILED;
                        errorCode = "AUTHN_FAILED";
                        errorMessage = a.getMessage();
                        writeError(wrappedResponse, HttpServletResponse.SC_UNAUTHORIZED,
                                errorCode, errorMessage, traceId);
                    } catch (AuthzException z) {
                        outcome = AuditOutcome.AUTHZ_FAILED;
                        errorCode = "AUTHZ_FAILED";
                        errorMessage = z.getMessage();
                        writeError(wrappedResponse, HttpServletResponse.SC_FORBIDDEN,
                                errorCode, errorMessage, traceId);
                    } catch (IllegalArgumentException v) {
                        outcome = AuditOutcome.VALIDATION_FAILED;
                        errorCode = "VALIDATION_FAILED";
                        errorMessage = v.getMessage();
                        writeError(wrappedResponse, HttpServletResponse.SC_BAD_REQUEST,
                                errorCode, errorMessage, traceId);
                    } catch (BusinessException b) {
                        outcome = AuditOutcome.BUSINESS_FAILED;
                        errorCode = b.getCode();
                        errorMessage = b.getMessage();
                        writeError(wrappedResponse, 422, errorCode, errorMessage, traceId);
                    } catch (Exception e) {
                        outcome = AuditOutcome.SERVER_ERROR;
                        errorCode = "SERVER_ERROR";
                        errorMessage = "系统内部错误";
                        logger.error("请求处理异常 traceId=" + traceId, e);
                        writeError(wrappedResponse, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                errorCode, errorMessage, traceId);
                    } finally {
                        ActorContext.clear();
                    }
                }
            }
        } finally {
            int status = wrappedResponse.getStatus();
            // 正常返回或由 @RestControllerAdvice 处理（异常已被翻译成响应体）时在此分类
            if (outcome == null) {
                outcome = classifyByStatus(status);
                if (outcome != AuditOutcome.SUCCESS) {
                    JsonNode err = readErrorBody(wrappedResponse);
                    if (err != null) {
                        errorCode = textOr(err, "code", outcome.name());
                        errorMessage = textOr(err, "message", "");
                    }
                }
            }

            String objectId = resolveObjectId(request, wrappedResponse, outcome);
            long duration = System.currentTimeMillis() - startedAt;

            AuditEvent event = new AuditEvent(
                    idGenerator.nextId(startedAt),
                    startedAt,
                    actor == null
                            ? (rawTokenSeen ? "unauthenticated" : "anonymous")
                            : actor.userId(),
                    actor == null
                            ? (rawTokenSeen ? "unauthenticated" : "anonymous")
                            : actor.userName(),
                    clientIp(request),
                    request.getMethod(),
                    request.getRequestURI(),
                    resolveAction(request),
                    resolveObjectType(request),
                    objectId,
                    outcome,
                    errorCode,
                    errorMessage,
                    status,
                    masker.maskParameters(request.getQueryString()),
                    masker.maskJson(cachedRequest.getBodyAsString()),
                    duration,
                    traceId
            );
            sink.publish(event);

            wrappedResponse.copyBodyToResponse();
            MDC.remove("traceId");
        }
    }

    private AuditOutcome classifyByStatus(int status) {
        if (status >= 200 && status < 300) {
            return AuditOutcome.SUCCESS;
        }
        return switch (status) {
            case 401 -> AuditOutcome.AUTHN_FAILED;
            case 403 -> AuditOutcome.AUTHZ_FAILED;
            case 400, 404, 405 -> AuditOutcome.VALIDATION_FAILED;
            case 422 -> AuditOutcome.BUSINESS_FAILED;
            default -> AuditOutcome.SERVER_ERROR;
        };
    }

    private JsonNode readErrorBody(ContentCachingResponseWrapper resp) {
        byte[] buf = resp.getContentAsByteArray();
        if (buf.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(buf);
            return node != null && node.has("code") ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveObjectId(HttpServletRequest request,
                                   ContentCachingResponseWrapper resp, AuditOutcome outcome) {
        // 路径变量优先：/api/documents/{id}
        String uri = request.getRequestURI();
        int marker = uri.indexOf("/api/documents/");
        if (marker >= 0) {
            String tail = uri.substring(marker + "/api/documents/".length());
            if (!tail.isEmpty() && !tail.contains("/")) {
                return tail;
            }
        }
        // 创建成功时从响应体取新对象 id
        if ("POST".equalsIgnoreCase(request.getMethod()) && outcome == AuditOutcome.SUCCESS) {
            byte[] buf = resp.getContentAsByteArray();
            if (buf.length > 0) {
                try {
                    JsonNode node = objectMapper.readTree(buf);
                    JsonNode id = node.get("id");
                    if (id != null) {
                        return id.asText();
                    }
                } catch (Exception ignored) {
                    // 响应不是 JSON 时对象 id 留空，不影响审计
                }
            }
        }
        return null;
    }

    private String resolveAction(HttpServletRequest request) {
        String m = request.getMethod().toUpperCase();
        return switch (m) {
            case "GET" -> "READ";
            case "POST" -> request.getRequestURI().contains("/purge") ? "PURGE"
                    : request.getRequestURI().contains("/retention") ? "CONFIG_CHANGE"
                    : "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> m;
        };
    }

    private String resolveObjectType(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/documents")) {
            return "DOCUMENT";
        }
        if (uri.startsWith("/api/audit/events")) {
            return "AUDIT_EVENT";
        }
        if (uri.startsWith("/api/audit/retention")) {
            return "RETENTION_POLICY";
        }
        return "UNKNOWN";
    }

    private boolean isAdminOnlyPath(String uri) {
        return uri.startsWith("/api/audit/");
    }

    private void writeError(HttpServletResponse response, int status, String code,
                            String message, String traceId) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String json = objectMapper.writeValueAsString(java.util.Map.of(
                "code", code, "message", message == null ? "" : message,
                "httpStatus", status, "traceId", traceId));
        response.getWriter().write(json);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }
}
