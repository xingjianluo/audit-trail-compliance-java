package com.github.xingjianluo.audittrailcompliancejava.audit.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 各类异常 -> 确定 HTTP 状态 + 统一错误体 {code,message}。
 * AuditFilter 根据最终状态码/异常类型把失败归入可区分的审计类别。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorBody(String code, String message) {
    }

    @ExceptionHandler(AuthnException.class)
    public ResponseEntity<Map<String, Object>> handleAuthn(AuthnException ex) {
        return build(HttpStatus.UNAUTHORIZED, "AUTHN_FAILED", ex.getMessage());
    }

    @ExceptionHandler(AuthzException.class)
    public ResponseEntity<Map<String, Object>> handleAuthz(AuthzException ex) {
        return build(HttpStatus.FORBIDDEN, "AUTHZ_FAILED", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("参数校验失败");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        // 业务异常属于可预期失败，与参数非法(400)、系统错误(500)区分
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(org.springframework.web.servlet.NoHandlerFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "接口不存在: " + ex.getRequestURL());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        log.error("未预期的系统异常", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR", "系统内部错误");
    }

    private static ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", message == null ? "" : message,
                "httpStatus", status.value()
        ));
    }
}
