package com.oms.order.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.oms.order.domain.DocumentStore;
import com.oms.order.repository.DocumentStoreRepository;

@Service
public class DocumentStoreService {

    private final DocumentStoreRepository documentStoreRepository;

    public DocumentStoreService(DocumentStoreRepository documentStoreRepository) {
        this.documentStoreRepository = documentStoreRepository;
    }

    public UUID store(String type, String content) {
        DocumentStore document = DocumentStore.builder()
                .type(type)
                .content(content)
                .build();
        return documentStoreRepository.save(document).getId();
    }

    public String retrieve(UUID documentId) {
        return documentStoreRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId))
                .getContent();
    }
}
