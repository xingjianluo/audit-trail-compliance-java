package com.github.xingjianluo.audittrailcompliancejava.web;

import com.github.xingjianluo.audittrailcompliancejava.audit.AuditResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理：把异常映射为 HTTP 响应，同时把审计分类结果
 * 写入请求属性，供审计拦截器精确归类（认证/鉴权/参数/业务/系统）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final AuditExceptionClassifier classifier;

    public GlobalExceptionHandler(AuditExceptionClassifier classifier) {
        this.classifier = classifier;
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> authentication(AuthenticationException ex,
                                                              HttpServletRequest request) {
        return error(ex, request, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<Map<String, Object>> authorization(AuthorizationException ex,
                                                             HttpServletRequest request) {
        return error(ex, request, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> validation(RuntimeException ex,
                                                          HttpServletRequest request) {
        return error(ex, request, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessException ex,
                                                        HttpServletRequest request) {
        return error(ex, request, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> system(Exception ex, HttpServletRequest request) {
        return error(ex, request, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<Map<String, Object>> error(Exception ex, HttpServletRequest request,
                                                      HttpStatus status) {
        AuditResult result = classifier.classify(ex);
        request.setAttribute(AuditInterceptor.ATTR_AUDIT_RESULT, result);
        return ResponseEntity.status(status).body(Map.of(
                "error", ex.getClass().getSimpleName(),
                "message", String.valueOf(ex.getMessage()),
                "auditCategory", result.name()));
    }
}
