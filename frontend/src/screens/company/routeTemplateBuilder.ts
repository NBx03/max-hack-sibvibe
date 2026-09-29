import type { RoleRef, RouteOverview, RouteTemplateRequest } from '../../api/types';

// Модель редактора состава шаблона маршрута — чистые функции, покрыты routeTemplateBuilder.test.ts.
// Экран только рисует состояние и вызывает эти функции. В отличие от routeBuilder.ts (маршрут документа,
// слоты — люди), здесь слот — роль без человека: шаблон описывает, кто по должности согласует, а не кто именно.
//
// Отличие от автора документа (routeBuilder.ts, isLocked): здесь администратор убирает и переставляет любую
// роль, включая обязательную — запрет «обязательную не убрать» действует только на автора при отправке.

export interface TemplateSlot {
  id: string;
  roleId: number;
  roleName: string;
  mandatory: boolean;
}

export interface TemplateStage {
  id: string;
  slots: TemplateSlot[];
}

export interface TemplateState {
  stages: TemplateStage[];
}

export type Target = { kind: 'stage'; index: number } | { kind: 'newStage'; index: number };

let nextId = 0;
function makeId(prefix: string): string {
  nextId += 1;
  return `${prefix}-${nextId}`;
}

/** Предзаполнение — текущий шаблон компании; без маршрута (тип без шаблона) — пустой редактор. */
export function initialState(route: RouteOverview | undefined): TemplateState {
  if (!route) return { stages: [] };
  return {
    stages: [...route.stages]
      .sort((left, right) => left.stageOrder - right.stageOrder)
      .map((stage) => ({
        id: makeId('stage'),
        slots: stage.participants.map((participant) => ({
          id: makeId('role'),
          roleId: participant.role.id,
          roleName: participant.role.name,
          mandatory: participant.mandatory,
        })),
      })),
  };
}

function allSlots(state: TemplateState): TemplateSlot[] {
  return state.stages.flatMap((stage) => stage.slots);
}

/** Роли, уже стоящие в шаблоне где угодно: роль в нём — не более одного раза (сервер это тоже проверяет). */
export function usedRoleIds(state: TemplateState): ReadonlySet<number> {
  return new Set(allSlots(state).map((slot) => slot.roleId));
}

/** Пустые этапы исчезают сами — как и в конструкторе маршрута документа. */
export function normalize(state: TemplateState): TemplateState {
  return { stages: state.stages.filter((stage) => stage.slots.length > 0) };
}

function withoutSlot(state: TemplateState, slotId: string): TemplateState {
  return { stages: state.stages.map((stage) => ({ ...stage, slots: stage.slots.filter((slot) => slot.id !== slotId) })) };
}

function findSlot(state: TemplateState, slotId: string): TemplateSlot | null {
  return allSlots(state).find((slot) => slot.id === slotId) ?? null;
}

function place(state: TemplateState, slot: TemplateSlot, target: Target): TemplateState {
  if (target.kind === 'newStage') {
    const stages = [...state.stages];
    stages.splice(Math.max(0, Math.min(target.index, stages.length)), 0, { id: makeId('stage'), slots: [slot] });
    return { stages };
  }
  const stages = state.stages.map((stage, index) => (index === target.index ? { ...stage, slots: [...stage.slots, slot] } : stage));
  return { stages };
}

export function addRole(state: TemplateState, role: RoleRef, target: Target): TemplateState {
  if (usedRoleIds(state).has(role.id)) return state;
  return place(state, { id: makeId('role'), roleId: role.id, roleName: role.name, mandatory: false }, target);
}

export function moveRole(state: TemplateState, slotId: string, target: Target): TemplateState {
  const slot = findSlot(state, slotId);
  if (!slot) return state;
  // Индекс цели — в координатах до удаления: этап, из которого ушла последняя роль, исчезнет только после вставки.
  const removed = withoutSlot(state, slotId);
  return normalize(place(removed, slot, target));
}

export function removeRole(state: TemplateState, slotId: string): TemplateState {
  return normalize(withoutSlot(state, slotId));
}

export function setMandatory(state: TemplateState, slotId: string, mandatory: boolean): TemplateState {
  return {
    stages: state.stages.map((stage) => ({
      ...stage,
      slots: stage.slots.map((slot) => (slot.id === slotId ? { ...slot, mandatory } : slot)),
    })),
  };
}

export function moveStage(state: TemplateState, index: number, delta: -1 | 1): TemplateState {
  const to = index + delta;
  if (to < 0 || to >= state.stages.length) return state;
  const stages = [...state.stages];
  [stages[index], stages[to]] = [stages[to], stages[index]];
  return { stages };
}

/** Тело PUT /orgs/current/routes/{documentTypeId} — позиция этапа в списке становится его номером на сервере. */
export function toRequest(state: TemplateState): RouteTemplateRequest {
  return {
    stages: state.stages.map((stage) => ({
      participants: stage.slots.map((slot) => ({ roleId: slot.roleId, mandatory: slot.mandatory })),
    })),
  };
}

/** Есть ли несохранённые изменения — не даёт нажать «Сохранить» вхолостую. */
export function isDirty(state: TemplateState, route: RouteOverview | undefined): boolean {
  const of = (stages: { participants: { roleId: number; mandatory: boolean }[] }[]) =>
    JSON.stringify(stages.map((stage) => stage.participants.map((p) => `${p.roleId}:${p.mandatory}`).sort()));
  const current = toRequest(state).stages;
  const saved = [...(route?.stages ?? [])]
    .sort((left, right) => left.stageOrder - right.stageOrder)
    .map((stage) => ({ participants: stage.participants.map((p) => ({ roleId: p.role.id, mandatory: p.mandatory })) }));
  return of(current) !== of(saved);
}
