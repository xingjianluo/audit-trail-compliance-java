package com.github.xingjianluo.audittrailcompliancejava.web;

import com.github.xingjianluo.audittrailcompliancejava.audit.AuditResult;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * 异常分类器：把异常映射到可区分的审计结果类别，
 * 认证失败、鉴权失败、参数非法、业务异常分别归类，不笼统记为一次失败。
 */
@Component
public class AuditExceptionClassifier {

    public AuditResult classify(Throwable ex) {
        if (ex == null) {
            return AuditResult.SUCCESS;
        }
        if (ex instanceof AuthenticationException) {
            return AuditResult.AUTHENTICATION_FAILED;
        }
        if (ex instanceof AuthorizationException) {
            return AuditResult.AUTHORIZATION_FAILED;
        }
        if (ex instanceof MethodArgumentNotValidException
                || ex instanceof MissingServletRequestParameterException
                || ex instanceof MethodArgumentTypeMismatchException
                || ex instanceof HttpMessageNotReadableException
                || ex instanceof IllegalArgumentException) {
            return AuditResult.VALIDATION_FAILED;
        }
        if (ex instanceof BusinessException) {
            return AuditResult.BUSINESS_ERROR;
        }
        return AuditResult.SYSTEM_ERROR;
    }
}
