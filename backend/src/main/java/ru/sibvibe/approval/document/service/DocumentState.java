package ru.sibvibe.approval.document.service;

import ru.sibvibe.approval.document.entity.Document;

/**
 * Состояние документа для других модулей: они вызывают только {@code service/} и сущность {@code Document}
 * не видят (ARCHITECTURE.md, раздел 2). Значения совпадают со статусами из раздела 4.
 */
public enum DocumentState {
    DRAFT, IN_APPROVAL, APPROVED, RETURNED, REJECTED;

    static DocumentState of(Document.Status status) {
        return valueOf(status.name());
    }
}
