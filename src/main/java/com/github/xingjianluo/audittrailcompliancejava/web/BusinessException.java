package com.github.xingjianluo.audittrailcompliancejava.web;

/** 业务异常：与参数非法、认证/鉴权失败区分开。 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
