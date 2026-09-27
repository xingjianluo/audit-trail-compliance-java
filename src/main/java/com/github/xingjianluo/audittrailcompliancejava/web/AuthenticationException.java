package com.github.xingjianluo.audittrailcompliancejava.web;

/** 认证失败异常（如缺少/非法凭证）。 */
public class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
        super(message);
    }
}
