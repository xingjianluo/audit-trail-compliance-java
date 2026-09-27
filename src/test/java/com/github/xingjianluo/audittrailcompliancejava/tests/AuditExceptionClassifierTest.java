package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.github.xingjianluo.audittrailcompliancejava.audit.AuditResult;
import com.github.xingjianluo.audittrailcompliancejava.web.AuditExceptionClassifier;
import com.github.xingjianluo.audittrailcompliancejava.web.AuthenticationException;
import com.github.xingjianluo.audittrailcompliancejava.web.AuthorizationException;
import com.github.xingjianluo.audittrailcompliancejava.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** 失败分类：各类异常必须归入可区分的类别。 */
class AuditExceptionClassifierTest {

    private static final Logger log = LoggerFactory.getLogger(AuditExceptionClassifierTest.class);

    private final AuditExceptionClassifier classifier = new AuditExceptionClassifier();

    @Test
    void classifiesDistinctFailureCategories() {
        assertClassification(new AuthenticationException("bad credential"),
                AuditResult.AUTHENTICATION_FAILED);
        assertClassification(new AuthorizationException("no permission"),
                AuditResult.AUTHORIZATION_FAILED);
        assertClassification(new IllegalArgumentException("bad param"),
                AuditResult.VALIDATION_FAILED);
        assertClassification(new BusinessException("insufficient balance"),
                AuditResult.BUSINESS_ERROR);
        assertClassification(new IllegalStateException("db down"),
                AuditResult.SYSTEM_ERROR);
        assertClassification(null, AuditResult.SUCCESS);
    }

    private void assertClassification(Throwable ex, AuditResult expected) {
        AuditResult actual = classifier.classify(ex);
        log.info("输入事件: exception={}", ex == null ? "null" : ex.getClass().getSimpleName());
        log.info("判定依据: 期望类别={}, 实际类别={}", expected, actual);
        assertThat(actual).isEqualTo(expected);
    }
}
