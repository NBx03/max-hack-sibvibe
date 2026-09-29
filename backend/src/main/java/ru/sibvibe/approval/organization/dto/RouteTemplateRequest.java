package ru.sibvibe.approval.organization.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Новый состав шаблона маршрута вида документа: этапы по порядку — позиция в списке становится
 * {@code stage_order}, внутри этапа роли параллельны. Замена полная: сервер удаляет прежние строки шаблона
 * и создаёт эти. Пустой список этапов отвергается: без строк шаблон неотличим от «ещё
 * не создан» и был бы молча восстановлен по умолчанию на следующем же обращении.
 */
public record RouteTemplateRequest(@NotNull List<@Valid StageInput> stages) {

    public record StageInput(@NotNull List<@Valid RoleInput> participants) {
    }

    public record RoleInput(@NotNull Long roleId, boolean mandatory) {
    }
}
