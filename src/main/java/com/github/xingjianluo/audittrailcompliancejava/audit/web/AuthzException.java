package com.github.xingjianluo.audittrailcompliancejava.audit.web;

/** 鉴权失败（已认证但权限不足）。 */
public class AuthzException extends RuntimeException {
    public AuthzException(String message) {
        super(message);
    }
}
