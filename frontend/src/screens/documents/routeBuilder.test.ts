import { describe, expect, it } from 'vitest';
import type { MemberView, RoleRef, RoutePreview, RoutePreviewParticipant } from '../../api/types';
import {
  addPerson,
  contextOf,
  initialState,
  isLocked,
  moveSlot,
  moveStage,
  placeOf,
  needsApprover,
  problemsOf,
  removeSlot,
  resolveChoice,
  toSubmit,
  type BuilderState,
} from './routeBuilder';

// Конструктор маршрута: предзаполнение, перемещения, замки и тело отправки.

const HEAD: RoleRef = { id: 20, code: 'DEPARTMENT_HEAD', name: 'Руководитель отдела' };
const LAWYER: RoleRef = { id: 21, code: 'LAWYER', name: 'Юрист' };
const ACCOUNTANT: RoleRef = { id: 23, code: 'ACCOUNTANT', name: 'Бухгалтер' };
const DIRECTOR: RoleRef = { id: 22, code: 'DIRECTOR', name: 'Директор' };

const member = (id: number, name: string, ...roles: RoleRef[]): MemberView => ({
  memberId: id * 10, user: { id, fullName: name }, status: 'ACTIVE', roles, isAdmin: false, joinedAt: '2026-09-01T00:00:00Z',
});
const BOB = member(2, 'Боб', HEAD);
const CAROL = member(3, 'Карол', LAWYER);
const DAVE = member(4, 'Дейв', LAWYER);
const ERIN = member(5, 'Эрин', ACCOUNTANT);
const ALICE = member(6, 'Алиса', DIRECTOR);
const COLLEAGUES = [BOB, CAROL, DAVE, ERIN, ALICE];

const auto = (role: RoleRef, who: MemberView, mandatory = false): RoutePreviewParticipant => ({
  role, mandatory, candidates: [who.user], selectedUserId: who.user.id, resolution: 'AUTO',
});
const select = (role: RoleRef, ...who: MemberView[]): RoutePreviewParticipant => ({
  role, mandatory: false, candidates: who.map((item) => item.user), selectedUserId: null, resolution: 'SELECT',
});

/** Мера поддержки: юрист (выбор из двух) и бухгалтер параллельно → директор (обязательный). */
const SUPPORT: RoutePreview = {
  stages: [
    { stageOrder: 1, participants: [select(LAWYER, CAROL, DAVE), auto(ACCOUNTANT, ERIN)] },
    { stageOrder: 2, participants: [auto(DIRECTOR, ALICE, true)] },
  ],
  problems: [],
  carryOver: null,
  previous: null,
};

const context = contextOf(SUPPORT, COLLEAGUES, 1);
const who = (state: BuilderState) => ({
  stages: state.stages.map((stage) => stage.slots.map((slot) => (slot.kind === 'person' ? slot.name : `?${slot.roleName}`))),
  endorser: state.endorser?.kind === 'person' ? state.endorser.name : state.endorser ? `?${state.endorser.roleName}` : null,
});

describe('предзаполнение', () => {
  it('шаблон: роль с выбором — место «выберите», последний этап из одного человека — утверждающий', () => {
    expect(who(initialState(SUPPORT, context))).toEqual({ stages: [['?Юрист', 'Эрин']], endorser: 'Алиса' });
  });

  it('повторная отправка — маршрутом прошлой версии, выбывшие выпадают', () => {
    const preview: RoutePreview = {
      ...SUPPORT,
      previous: {
        versionNo: 1,
        stages: [{ participants: [{ userId: 4, roleId: 21 }, { userId: 99, roleId: 21 }] }, { participants: [{ userId: 2, roleId: 20 }] }],
        endorser: { userId: 6, roleId: 22 },
      },
    };
    expect(who(initialState(preview, context))).toEqual({ stages: [['Дейв'], ['Боб']], endorser: 'Алиса' });
  });

  it('один человек — один раз: при повторе в шаблоне остаётся более позднее место', () => {
    const chief = member(7, 'Шеф', LAWYER, DIRECTOR);
    const preview: RoutePreview = {
      ...SUPPORT,
      stages: [
        { stageOrder: 1, participants: [auto(LAWYER, chief), auto(ACCOUNTANT, ERIN)] },
        { stageOrder: 2, participants: [auto(DIRECTOR, chief, true)] },
      ],
    };
    const state = initialState(preview, contextOf(preview, [chief, ERIN], 1));
    expect(who(state)).toEqual({ stages: [['Эрин']], endorser: 'Шеф' });
  });
});

describe('правки автора', () => {
  const base = resolveChoice(initialState(SUPPORT, context), 'c:1:21', CAROL.user, context);

  it('обязательного можно двигать, но не удалить; необязательного — удалить', () => {
    const director = base.endorser!;
    expect(isLocked(base, director, context)).toBe(true);
    expect(removeSlot(base, director.id, context)).toBe(base);
    const moved = moveSlot(base, director.id, { kind: 'newStage', index: 0 });
    expect(who(moved)).toEqual({ stages: [['Алиса'], ['Карол', 'Эрин']], endorser: null });
    expect(who(removeSlot(base, 'u:5', context))).toEqual({ stages: [['Карол']], endorser: 'Алиса' });
  });

  it('ушёл последний из этапа — этап исчезает, нумерация сдвигается', () => {
    const split = moveSlot(base, 'u:5', { kind: 'newStage', index: 1 });
    expect(who(split).stages).toEqual([['Карол'], ['Эрин']]);
    expect(who(moveSlot(split, 'u:5', { kind: 'stage', index: 0 })).stages).toEqual([['Карол', 'Эрин']]);
  });

  it('новый утверждающий не выбрасывает прежнего: тот становится последним этапом', () => {
    const state = moveSlot(base, 'u:3', { kind: 'endorser' });
    expect(who(state)).toEqual({ stages: [['Эрин'], ['Алиса']], endorser: 'Карол' });
  });

  it('добавить можно только того, кого в маршруте ещё нет', () => {
    expect(placeOf(base, 3)).toBe('уже в маршруте, этап 1');
    expect(placeOf(base, 6)).toBe('уже утверждает');
    expect(addPerson(base, CAROL, { kind: 'stage', index: 0 }, context)).toBe(base);
    const added = addPerson(base, BOB, { kind: 'newStage', index: 1 }, context);
    expect(who(added).stages).toEqual([['Карол', 'Эрин'], ['Боб']]);
  });

  it('этапы меняются местами', () => {
    const two = addPerson(base, BOB, { kind: 'newStage', index: 1 }, context);
    expect(who(moveStage(two, 1, -1)).stages).toEqual([['Боб'], ['Карол', 'Эрин']]);
  });
});

describe('отправка', () => {
  it('пока не выбран человек для роли — отправить нельзя', () => {
    expect(toSubmit(initialState(SUPPORT, context), context)).toBeNull();
  });

  it('тело — этапы по порядку и утверждающий с ролями', () => {
    const state = resolveChoice(initialState(SUPPORT, context), 'c:1:21', DAVE.user, context);
    expect(toSubmit(state, context)).toEqual({
      stages: [{ participants: [{ userId: 4, roleId: 21 }, { userId: 5, roleId: 23 }] }],
      endorser: { userId: 6, roleId: 22 },
    });
  });
});

describe('дополнительные случаи', () => {
  const authorIsDirector = (role: RoleRef): RoutePreviewParticipant => ({
    role, mandatory: true, candidates: [], selectedUserId: null, resolution: 'AUTHOR_HOLDS_ROLE',
  });

  it('последний этап шаблона — сам автор: утверждающего по умолчанию нет, предпоследний им не становится', () => {
    // Заявление на отпуск у директора: Кадровик → Директор (это автор)
    const vacation: RoutePreview = {
      stages: [
        { stageOrder: 1, participants: [auto(ACCOUNTANT, ERIN)] },
        { stageOrder: 2, participants: [authorIsDirector(DIRECTOR)] },
      ],
      problems: [], carryOver: null, previous: null,
    };
    const ctx = contextOf(vacation, COLLEAGUES, 6);
    expect(who(initialState(vacation, ctx))).toEqual({ stages: [['Эрин']], endorser: null });
    expect(ctx.authorRoles).toEqual([DIRECTOR]);
    // шаг автора закрывает маршрут — пустой маршрут не ошибка, и пустой карточки «Этап 1» нет (было два «Этапа 1»)
    expect(problemsOf({ stages: [], endorser: null }, ctx)).toEqual([]);
    expect(needsApprover({ stages: [], endorser: null }, ctx)).toBe(false);
  });

  it('пустой маршрут при коллегах с ролями — отправить нельзя, с подсказкой', () => {
    const generic: RoutePreview = { stages: [], problems: [], carryOver: null, previous: null };
    const ctx = contextOf(generic, COLLEAGUES, 1);
    expect(problemsOf(initialState(generic, ctx), ctx)).toEqual(['Добавьте хотя бы одного согласующего']);
    expect(needsApprover(initialState(generic, ctx), ctx)).toBe(true);
    expect(problemsOf(initialState(generic, contextOf(generic, [], 1)), contextOf(generic, [], 1))).toEqual([]);
  });

  it('обязательная роль, которой в маршруте ни у кого нет, — подсказка до отправки', () => {
    // прошлый маршрут без директора (ушёл из компании), а директор обязателен
    const withPrevious: RoutePreview = {
      ...SUPPORT,
      previous: { versionNo: 1, stages: [{ participants: [{ userId: 5, roleId: 23 }] }], endorser: { userId: 99, roleId: 22 } },
    };
    const state = initialState(withPrevious, context);
    expect(problemsOf(state, context)).toEqual(['Нужен обязательный согласующий: «Директор»']);
    const fixed = addPerson(state, ALICE, { kind: 'endorser' }, context);
    expect(problemsOf(fixed, context)).toEqual([]);
  });
});

describe('дополнительные случаи', () => {
  it('обязательная роль без носителей — не «выберите сотрудника», а «нет сотрудника» с подсказкой', () => {
    const empty: RoutePreviewParticipant = { role: HEAD, mandatory: true, candidates: [], selectedUserId: null, resolution: 'SELECT' };
    const memo: RoutePreview = {
      stages: [{ stageOrder: 1, participants: [empty, auto(LAWYER, CAROL)] }],
      problems: [], carryOver: null, previous: null,
    };
    const ctx = contextOf(memo, COLLEAGUES, 1);
    const problems = problemsOf(initialState(memo, ctx), ctx);
    expect(problems.some((problem) => problem.startsWith('Выберите сотрудника'))).toBe(false);
    expect(problems).toContain('Нет сотрудника с ролью «Руководитель отдела» — попросите администратора назначить её или сделать необязательной');
  });
});
