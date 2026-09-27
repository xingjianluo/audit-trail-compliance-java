package com.github.xingjianluo.audittrailcompliancejava.audit.web.demo;

/** 演示业务对象。 */
public record Document(String id, String title, String content, String ownerId, long createdAt) {
}
