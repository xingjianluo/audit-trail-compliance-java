package com.github.xingjianluo.audittrailcompliancejava.audit.web;

/** 可预期的业务异常，与参数非法、系统错误区分。 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
