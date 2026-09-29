package ru.sibvibe.approval.approval.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Отправка на согласование.
 *
 * <p><b>Маршрут, составленный автором</b> (конструктор, RTE-1, D3): {@code stages} — этапы по порядку, в каждом —
 * люди с ролью, под которой они согласуют; {@code endorser} — утверждающий, последний шаг, может отсутствовать.
 * Шаблон вида документа только предзаполняет маршрут: автор двигает любых, убирает всех, кроме обязательных.
 * Сервер проверяет: все обязательные роли на месте, один человек — один раз, нет пустых этапов.
 *
 * <p><b>Прежний формат</b>: {@code selections} — выбор человека там, где кандидатов несколько,
 * {@code extraApprovers} — добавленные к шаблону. Сервер переводит его в тот же маршрут; смешивать форматы нельзя.
 * Если выбирать не из чего, тело можно не передавать.
 */
public record SubmitRequest(
        @Size(max = 50) List<@Valid @NotNull Choice> selections,
        @Size(max = 20) List<@Valid @NotNull Choice> extraApprovers,
        @Size(max = 20) List<@Valid @NotNull Stage> stages,
        @Valid Participant endorser
) {

    public SubmitRequest(List<Choice> selections, List<Choice> extraApprovers) {
        this(selections, extraApprovers, null, null);
    }

    public List<Choice> selectionsOrEmpty() {
        return selections == null ? List.of() : selections;
    }

    public List<Choice> extraApproversOrEmpty() {
        return extraApprovers == null ? List.of() : extraApprovers;
    }

    /** Маршрут составлен в конструкторе: этапы или утверждающий переданы явно. */
    public boolean hasExplicitRoute() {
        return stages != null || endorser != null;
    }

    public List<Stage> stagesOrEmpty() {
        return stages == null ? List.of() : stages;
    }

    /** Этап маршрута: люди, которые согласуют одновременно. */
    public record Stage(@NotNull @Size(max = 20) List<@Valid @NotNull Participant> participants) {
    }

    /** Человек в маршруте и роль, под которой он согласует (одна из его ролей). */
    public record Participant(@NotNull Long userId, @NotNull Long roleId) {
    }

    /**
     * Прежний формат. {@code newStage} — только у добавленных согласующих: {@code true} — отдельный новый этап
     * <b>перед</b> этапом шаблона {@code stageOrder}; {@code stageOrder} на единицу больше последнего этапа — новый
     * последний этап. {@code false} или нет — вместе с этапом {@code stageOrder}.
     */
    public record Choice(@NotNull Integer stageOrder, @NotNull Long roleId, @NotNull Long userId, Boolean newStage) {

        public Choice(Integer stageOrder, Long roleId, Long userId) {
            this(stageOrder, roleId, userId, null);
        }

        public boolean isNewStage() {
            return Boolean.TRUE.equals(newStage);
        }
    }
}
