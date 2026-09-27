package com.github.xingjianluo.audittrailcompliancejava.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册审计拦截器，覆盖所有路径。 */
@Configuration
public class AuditWebConfig implements WebMvcConfigurer {
    private final AuditInterceptor auditInterceptor;

    public AuditWebConfig(AuditInterceptor auditInterceptor) {
        this.auditInterceptor = auditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditInterceptor).addPathPatterns("/**");
    }
}
