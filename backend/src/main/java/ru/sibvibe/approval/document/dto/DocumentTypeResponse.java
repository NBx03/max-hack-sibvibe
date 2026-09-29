package ru.sibvibe.approval.document.dto;

import java.util.List;

public record DocumentTypeResponse(
        long id,
        String code,
        String name,
        boolean isGeneric,
        List<Field> fields
) {
    public record Field(String name, String type, String label, String hint) {}
}
