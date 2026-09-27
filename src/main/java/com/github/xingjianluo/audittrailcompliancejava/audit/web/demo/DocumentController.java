package com.github.xingjianluo.audittrailcompliancejava.audit.web.demo;

import com.github.xingjianluo.audittrailcompliancejava.audit.web.ActorContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对外演示接口：所有方法都处于 AuditFilter 包裹范围内，成功/失败都会被留痕。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<Document> create(@Valid @RequestBody DocumentRequest request) {
        ActorContext actor = ActorContext.get();
        String ownerId = actor == null ? "anonymous" : actor.userId();
        Document doc = documentService.create(request.title(), request.content(), ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(doc);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> get(@PathVariable String id) {
        return ResponseEntity.ok(documentService.get(id));
    }

    @GetMapping
    public List<Document> list() {
        return documentService.list();
    }
}
