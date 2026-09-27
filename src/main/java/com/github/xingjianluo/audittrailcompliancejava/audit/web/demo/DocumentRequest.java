package com.github.xingjianluo.audittrailcompliancejava.audit.web.demo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建/更新文档的请求体（含可被校验拒绝的字段）。 */
public record DocumentRequest(
        @NotBlank(message = "title 不能为空")
        @Size(max = 100, message = "title 最长 100")
        String title,
        @Size(max = 5000, message = "content 最长 5000")
        String content
) {
}
