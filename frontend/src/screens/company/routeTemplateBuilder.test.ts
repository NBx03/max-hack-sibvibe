import { describe, expect, it } from 'vitest';
import type { RoleRef, RouteOverview } from '../../api/types';
import {
  addRole,
  initialState,
  isDirty,
  moveRole,
  moveStage,
  removeRole,
  setMandatory,
  toRequest,
  usedRoleIds,
} from './routeTemplateBuilder';

// Редактор состава шаблона маршрута: предзаполнение, перемещения, обязательность, тело сохранения.

const HEAD: RoleRef = { id: 20, code: 'DEPARTMENT_HEAD', name: 'Руководитель отдела' };
const LAWYER: RoleRef = { id: 21, code: 'LAWYER', name: 'Юрист' };
const ACCOUNTANT: RoleRef = { id: 23, code: 'ACCOUNTANT', name: 'Бухгалтер' };
const DIRECTOR: RoleRef = { id: 22, code: 'DIRECTOR', name: 'Директор' };

const MEMO_ROUTE: RouteOverview = {
  documentTypeId: 100,
  stages: [
    { stageOrder: 1, participants: [{ role: HEAD, mandatory: false }] },
    { stageOrder: 2, participants: [{ role: DIRECTOR, mandatory: true }] },
  ],
};

const names = (state: ReturnType<typeof initialState>) =>
  state.stages.map((stage) => stage.slots.map((slot) => slot.roleName));

describe('предзаполнение', () => {
  it('читает этапы шаблона по порядку', () => {
    const state = initialState(MEMO_ROUTE);
    expect(names(state)).toEqual([['Руководитель отдела'], ['Директор']]);
    expect(state.stages[1].slots[0].mandatory).toBe(true);
  });

  it('без шаблона — пустой редактор', () => {
    expect(initialState(undefined).stages).toEqual([]);
  });
});

describe('добавление и перемещение ролей', () => {
  it('добавляет роль новым этапом', () => {
    const state = addRole(initialState(MEMO_ROUTE), LAWYER, { kind: 'newStage', index: 0 });
    expect(names(state)).toEqual([['Юрист'], ['Руководитель отдела'], ['Директор']]);
  });

  it('добавляет роль в существующий этап параллельно', () => {
    const state = addRole(initialState(MEMO_ROUTE), ACCOUNTANT, { kind: 'stage', index: 1 });
    expect(names(state)).toEqual([['Руководитель отдела'], ['Директор', 'Бухгалтер']]);
  });

  it('роль в шаблоне только один раз — повторное добавление ничего не меняет', () => {
    const state = initialState(MEMO_ROUTE);
    const again = addRole(state, DIRECTOR, { kind: 'newStage', index: 0 });
    expect(again).toEqual(state);
    expect(usedRoleIds(state).has(DIRECTOR.id)).toBe(true);
  });

  it('перемещает роль в другой этап, пустой этап исчезает', () => {
    const state = initialState(MEMO_ROUTE);
    const headSlotId = state.stages[0].slots[0].id;
    // индекс цели — до удаления (комментарий в routeTemplateBuilder.ts): этап 1 (директор) остаётся на своём месте
    const moved = moveRole(state, headSlotId, { kind: 'stage', index: 1 });
    // после переноса руководителя в этап директора первый этап опустел и пропал
    expect(names(moved)).toEqual([['Директор', 'Руководитель отдела']]);
  });
});

describe('обязательность и удаление', () => {
  it('администратор может убрать обязательную роль — не только автор документа может её оставить', () => {
    const state = initialState(MEMO_ROUTE);
    const directorSlotId = state.stages[1].slots[0].id;
    const removed = removeRole(state, directorSlotId);
    expect(names(removed)).toEqual([['Руководитель отдела']]);
  });

  it('переключает обязательность роли', () => {
    const state = initialState(MEMO_ROUTE);
    const headSlotId = state.stages[0].slots[0].id;
    const updated = setMandatory(state, headSlotId, true);
    expect(updated.stages[0].slots[0].mandatory).toBe(true);
    // директор не тронут
    expect(updated.stages[1].slots[0].mandatory).toBe(true);
  });
});

describe('порядок этапов', () => {
  it('меняет этапы местами', () => {
    const state = initialState(MEMO_ROUTE);
    const swapped = moveStage(state, 0, 1);
    expect(names(swapped)).toEqual([['Директор'], ['Руководитель отдела']]);
  });

  it('не двигает за границы списка', () => {
    const state = initialState(MEMO_ROUTE);
    expect(moveStage(state, 0, -1)).toEqual(state);
    expect(moveStage(state, 1, 1)).toEqual(state);
  });
});

describe('тело запроса и изменения', () => {
  it('позиция этапа определяет номер, а не сохранённый stageOrder', () => {
    const state = moveStage(initialState(MEMO_ROUTE), 0, 1);
    expect(toRequest(state)).toEqual({
      stages: [
        { participants: [{ roleId: DIRECTOR.id, mandatory: true }] },
        { participants: [{ roleId: HEAD.id, mandatory: false }] },
      ],
    });
  });

  it('без изменений — не «грязно»', () => {
    expect(isDirty(initialState(MEMO_ROUTE), MEMO_ROUTE)).toBe(false);
  });

  it('изменение обязательности помечает состояние изменённым', () => {
    const state = initialState(MEMO_ROUTE);
    const updated = setMandatory(state, state.stages[0].slots[0].id, true);
    expect(isDirty(updated, MEMO_ROUTE)).toBe(true);
  });

  it('можно опустошить локально до нуля ролей — toRequest честно отдаёт пустой список', () => {
    // Сохранить такое состояние нельзя (сервер отвергает пустой шаблон) — экран блокирует
    // «Сохранить», когда stages пуст; сама функция при этом не должна врать о состоянии.
    const state = initialState(MEMO_ROUTE);
    const withoutHead = removeRole(state, state.stages[0].slots[0].id);
    const cleared = removeRole(withoutHead, withoutHead.stages[0].slots[0].id);
    expect(toRequest(cleared)).toEqual({ stages: [] });
  });
});
