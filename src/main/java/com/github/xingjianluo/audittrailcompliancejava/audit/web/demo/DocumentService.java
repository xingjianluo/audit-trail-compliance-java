package com.github.xingjianluo.audittrailcompliancejava.audit.web.demo;

import com.github.xingjianluo.audittrailcompliancejava.audit.web.BusinessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 演示业务服务：用内存 Map 模拟文档库。
 * get 一个不存在的 id 会抛出 {@link BusinessException}，供审计区分“业务异常”类别。
 */
@Service
public class DocumentService {

    private final ConcurrentHashMap<String, Document> store = new ConcurrentHashMap<>();

    public Document create(String title, String content, String ownerId) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Document doc = new Document(id, title, content == null ? "" : content, ownerId, System.currentTimeMillis());
        store.put(id, doc);
        return doc;
    }

    public Document get(String id) {
        Document doc = store.get(id);
        if (doc == null) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "文档不存在: " + id);
        }
        return doc;
    }

    public List<Document> list() {
        return store.values().stream()
                .sorted((a, b) -> Long.compare(b.createdAt(), a.createdAt()))
                .toList();
    }
}
