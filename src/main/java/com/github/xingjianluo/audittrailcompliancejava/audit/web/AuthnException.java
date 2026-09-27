package com.github.xingjianluo.audittrailcompliancejava.audit.web;

/** 认证失败（未登录 / token 无效）。 */
public class AuthnException extends RuntimeException {
    public AuthnException(String message) {
        super(message);
    }
}
