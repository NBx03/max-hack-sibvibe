package ru.sibvibe.approval.organization.dto;

import ru.sibvibe.approval.common.dto.RoleRef;

import java.util.List;

/**
 * Маршрут компании для одного типа документа — для раздела «Компания» (было непонятно,
 * кто согласует и в каком порядке). Только чтение: названия типов фронтенд берёт из {@code /document-types}.
 */
public record RouteOverview(long documentTypeId, List<Stage> stages) {

    public record Stage(int stageOrder, List<Participant> participants) {
    }

    /** {@code mandatory = false} — участник пропускается, если в компании нет носителя роли. */
    public record Participant(RoleRef role, boolean mandatory) {
    }
}
