package ru.sibvibe.approval.document.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Данные таблицы document_type_field; ссылки на другие модули хранятся как ID. */
@Entity
@Table(name = "document_type_field")
@Getter
@Setter
@NoArgsConstructor
public class DocumentTypeField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_type_id", nullable = false)
    private Long documentTypeId;

    @Column(name = "field_name", nullable = false)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_type", nullable = false, length = 32)
    private FieldType fieldType;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "hint", columnDefinition = "text")
    private String hint;

    /** Порядок поля в форме и в результате проверки — как в testdata/rules.json, не по id. */
    @Column(name = "position", nullable = false)
    private int position;

    public enum FieldType { STRING, DATE, NUMBER, PERSON, ORGANIZATION }
}
