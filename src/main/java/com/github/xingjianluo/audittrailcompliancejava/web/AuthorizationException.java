package com.github.xingjianluo.audittrailcompliancejava.web;

/** 鉴权失败异常（已认证但无权限）。 */
public class AuthorizationException extends RuntimeException {
    public AuthorizationException(String message) {
        super(message);
    }
}
