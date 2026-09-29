import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import { getRoutePreview, submitDocument } from '../../api/documents';
import { ApiError, errorMessage } from '../../api/errors';
import { getColleagues } from '../../api/organization';
import type { MemberView, RoutePreview, StepKind } from '../../api/types';
import { useSession } from '../../app/SessionContext';
import { useAsync } from '../../hooks/useAsync';
import { documentCardPath, documentCheckPath } from '../../routes/paths';
import {
  BottomSheet,
  Button,
  ErrorState,
  Icon,
  IconButton,
  InlineError,
  List,
  Loading,
  Muted,
  Notice,
  Page,
  PageHeader,
  Row,
  SelectSheet,
  Stepper,
  type SelectOption,
} from '../../ui';
import { CREATE_STEPS } from './uploadFormats';
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
  type BuilderContext,
  type BuilderState,
  type Slot,
  type Target,
} from './routeBuilder';

/**
 * Текст тоста после отправки — по статусу из ответа: если решать некому (обязательные роли только у автора), документ
 * сразу согласован, и «согласующие получили уведомление» было бы неправдой.
 */
function submitToastMessage(status: string): string {
  return status === 'APPROVED' ? 'Документ согласован — решать было некому' : 'Отправлено. Согласующие получили уведомление';
}

function targetKey(target: Target): string {
  return target.kind === 'endorser' ? 'endorser' : `${target.kind}:${target.index}`;
}

function parseTarget(key: string): Target | null {
  if (key === 'endorser') return { kind: 'endorser' };
  const [kind, index] = key.split(':');
  if (kind === 'stage' || kind === 'newStage') return { kind, index: Number(index) };
  return null;
}

function allSlotsOf(state: BuilderState): Slot[] {
  return [...state.stages.flatMap((stage) => stage.slots), ...(state.endorser ? [state.endorser] : [])];
}

/**
 * 12. Маршрут — конструктор. Этапы — карточки, в каждом — люди; внизу — «Утверждение» с одним
 * утверждающим. Шаблон вида документа только предзаполняет маршрут: людей можно перетаскивать за ⋮⋮ или переносить
 * через меню человека, этапы — менять местами, убирать — всех, кроме обязательных (замок). Логика — routeBuilder.ts.
 */
export function RoutePreviewScreen() {
  const { id } = useParams<{ id: string }>();
  const documentId = Number(id);
  // С экрана проверки «назад» ведёт туда же, а не через него на карточку.
  const fromCheck = (useLocation().state as { from?: string } | null)?.from === 'check';
  const { me } = useSession();
  const authorId = me?.actingAs ? me.actingAs.user.id : me?.user.id;

  const query = useAsync(() => Promise.all([getRoutePreview(documentId), getColleagues()]), [documentId]);
  const back = fromCheck
    ? { to: documentCheckPath(documentId), label: 'К проверке' }
    : { to: documentCardPath(documentId), label: 'К документу' };

  if (query.status === 'loading') {
    return (
      <Page header={<PageHeader back={back} title="Маршрут" />}>
        <Loading />
      </Page>
    );
  }
  // Документ уже отправлен (старая ссылка, «назад» после отправки) — маршрут не нужен, ведём на карточку, а не в
  // «Не получилось загрузить… Повторить», где повтор не поможет.
  if (query.status === 'error' && query.error instanceof ApiError && query.error.code === 'INVALID_STATE') {
    return <Navigate to={documentCardPath(documentId)} replace />;
  }
  if (query.status === 'error') {
    return (
      <Page header={<PageHeader back={back} title="Маршрут" />}>
        <ErrorState message={errorMessage(query.error)} onRetry={query.refresh} />
      </Page>
    );
  }
  const [preview, colleagues] = query.data;
  return <RouteBuilder documentId={documentId} preview={preview} colleagues={colleagues} authorId={authorId} back={back} fromCheck={fromCheck} />;
}

type AddTarget = Target | { kind: 'choice'; slotId: string };

function RouteBuilder({
  documentId,
  preview,
  colleagues,
  authorId,
  back,
  fromCheck,
}: {
  documentId: number;
  preview: RoutePreview;
  colleagues: MemberView[];
  authorId: number | undefined;
  back: { to: string; label: string };
  /** Пришли с шага проверки — показываем шаги создания. */
  fromCheck: boolean;
}) {
  const navigate = useNavigate();
  const context = useMemo(() => contextOf(preview, colleagues, authorId), [preview, colleagues, authorId]);
  const [state, setState] = useState<BuilderState>(() => initialState(preview, context));
  const [adding, setAdding] = useState<AddTarget | null>(null);
  const [menuSlotId, setMenuSlotId] = useState<string | null>(null);
  const [stageMenu, setStageMenu] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const drag = useDragAndDrop((slotId, target) => setState((current) => moveSlot(current, slotId, target)));

  const serverProblems = preview.problems;
  const localProblems = problemsOf(state, context);
  const authorRoles = context.authorRoles;
  const carried = (slot: Slot, kind: StepKind) =>
    slot.kind === 'person'
    && (preview.carryOver?.approvals.some((approval) =>
      approval.userId === slot.userId && approval.roleId === slot.roleId && approval.kind === kind) ?? false);
  const canSubmit = serverProblems.length === 0 && localProblems.length === 0 && !submitting;
  const menuSlot = menuSlotId === null ? null : allSlotsOf(state).find((slot) => slot.id === menuSlotId) ?? null;

  async function submit() {
    const body = toSubmit(state, context);
    if (!body) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const submitted = await submitDocument(documentId, body);
      navigate(documentCardPath(documentId), { state: { toast: submitToastMessage(submitted.status) } });
    } catch (err) {
      setSubmitError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  const renderSlot = (slot: Slot, kind: StepKind = 'APPROVAL') => (
    <SlotRow
      key={slot.id}
      slot={slot}
      locked={isLocked(state, slot, context)}
      carried={carried(slot, kind) ? (kind === 'ENDORSEMENT' ? 'утверждение сохранится — документ не менялся' : 'согласие сохранится — документ не менялся') : null}
      onOpen={() => {
        if (slot.kind === 'choice') {
          // Без кандидатов выбирать некого — панель выбора не открываем.
          if (slot.candidates.length > 0) setAdding({ kind: 'choice', slotId: slot.id });
        } else {
          setMenuSlotId(slot.id);
        }
      }}
      onRemove={() => setState((current) => removeSlot(current, slot.id, context))}
      onDragStart={(event) => drag.start(event, slot)}
    />
  );

  return (
    <Page
      header={
        <>
          <PageHeader
            back={back}
            title="Маршрут"
            subtitle="Этапы идут по очереди, люди внутри этапа согласуют одновременно. Последним документ утверждают."
          />
          {fromCheck && <Stepper steps={CREATE_STEPS} current={2} />}
        </>
      }
      footer={
        <>
          {localProblems.map((problem) => (
            <p key={problem} className="ds-section__hint" style={{ margin: 0 }}>{problem}</p>
          ))}
          {submitError && <InlineError>{submitError}</InlineError>}
          <Button variant="primary" stretched loading={submitting} disabled={!canSubmit} onClick={() => void submit()}>
            Отправить на согласование
          </Button>
        </>
      }
    >
      {/* Роль без носителей конструктор показывает сам — строкой в этапе и подсказкой у кнопки; сверху не повторяем. */}
      {serverProblems.filter((problem) => problem.code !== 'ROUTE_ROLE_EMPTY').map((problem) => (
        <Notice key={problem.code} tone="danger">{problem.message}</Notice>
      ))}
      {/* Подсказка о перетаскивании — только когда есть кого перетаскивать. */}
      {allSlotsOf(state).length > 0 && (
        <Muted>
          Перетащите человека за <Icon name="drag" size={14} /> или нажмите на него, чтобы перенести. Замок — обязательный
          по правилам компании: перенести можно, убрать нельзя.
        </Muted>
      )}
      {state.stages.length === 0 && needsApprover(state, context) && (
        // Без согласующего не отправить — карточка «Этап 1» с «+ Согласующий», а не пунктирная заглушка.
        // Если маршрут и так проходит (есть утверждающий или шаг автора), ниже — пунктирная зона: добавить можно, не нужно.
        <section
          className="ds-card ds-drop-target"
          data-drop={targetKey({ kind: 'newStage', index: 0 })}
          data-hover={drag.hover === targetKey({ kind: 'newStage', index: 0 })}
        >
          <h2 className="ds-section__title">Этап 1</h2>
          <p className="ds-section__hint" style={{ margin: 0 }}>Пока никого — добавьте, кто согласует документ.</p>
          <Button compact icon="plus" onClick={() => setAdding({ kind: 'newStage', index: 0 })}>
            Согласующий
          </Button>
        </section>
      )}

      {state.stages.map((stage, index) => (
        <section
          key={stage.id}
          className="ds-card ds-drop-target"
          data-drop={targetKey({ kind: 'stage', index })}
          data-hover={drag.hover === targetKey({ kind: 'stage', index })}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minHeight: 32 }}>
            <h2 className="ds-section__title" style={{ flex: 1 }}>
              Этап {index + 1}
              {stage.slots.length > 1 && <span style={{ fontWeight: 400, color: 'var(--text-secondary)' }}> — одновременно</span>}
            </h2>
            {state.stages.length > 1 && (
              <IconButton icon="more" label={`Порядок этапа ${index + 1}`} onClick={() => setStageMenu(index)} />
            )}
          </div>
          <List label={`Этап ${index + 1}`}>{stage.slots.map((slot) => renderSlot(slot))}</List>
          <Button compact icon="plus" onClick={() => setAdding({ kind: 'stage', index })}>
            Согласующий
          </Button>
        </section>
      ))}

      {(state.stages.length > 0 || !needsApprover(state, context)) && (
        <button
          type="button"
          className="ds-dropzone"
          data-drop={targetKey({ kind: 'newStage', index: state.stages.length })}
          data-hover={drag.hover === targetKey({ kind: 'newStage', index: state.stages.length })}
          onClick={() => setAdding({ kind: 'newStage', index: state.stages.length })}
        >
          <Icon name="plus" size={20} />
          {drag.active ? 'Отпустите — будет новый этап' : state.stages.length > 0 ? 'Новый этап' : 'Согласующий'}
        </button>
      )}

      {authorRoles.length > 0 && (
        // Обязательная роль, которая есть только у автора: шаг согласуется сам. Сервер ставит его после согласующих и
        // перед утверждением — здесь он там же, строкой, а не только подсказкой.
        <section className="ds-card">
          <h2 className="ds-section__title">
            Этап {state.stages.length + 1}
            <span style={{ fontWeight: 400, color: 'var(--text-secondary)' }}> — автоматически</span>
          </h2>
          <List label="Согласуется автоматически">
            <div className="ds-row" role="listitem">
              <span className="ds-row__body">
                {/* В истории шаг хранит одну роль — первую; остальные названы ниже, чтобы конструктор и карточка не расходились. */}
                <span className="ds-row__title">Вы ({authorRoles[0].name})</span>
                <span className="ds-row__subtitle">
                  {authorRoles.length > 1
                    ? `Роли ${authorRoles.map((role) => `«${role.name}»`).join(', ')} в компании только у вас — согласуются автоматически одним шагом.`
                    : 'Согласуется автоматически: эта роль в компании только у вас.'}{' '}
                  Будет видно в истории.
                </span>
              </span>
              <span style={{ display: 'inline-flex', width: 44, justifyContent: 'center', color: 'var(--status-success-fg)' }}>
                <Icon name="check" size={20} />
              </span>
            </div>
          </List>
        </section>
      )}

      <section
        className="ds-card ds-drop-target"
        data-drop="endorser"
        data-hover={drag.hover === 'endorser'}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ color: 'var(--status-endorse-fg)', display: 'inline-flex' }}><Icon name="endorse" size={20} /></span>
          <h2 className="ds-section__title" style={{ flex: 1 }}>Утверждение</h2>
        </div>
        <p className="ds-section__hint" style={{ margin: 0 }}>
          Последний шаг: один утверждающий, как гриф «УТВЕРЖДАЮ». После него документ — «Утверждён».
        </p>
        {state.endorser ? (
          <List label="Утверждающий">{renderSlot(state.endorser, 'ENDORSEMENT')}</List>
        ) : (
          <>
            <button type="button" className="ds-dropzone" onClick={() => setAdding({ kind: 'endorser' })}>
              <Icon name="plus" size={20} />
              Утверждающий
            </button>
            <p className="ds-section__hint" style={{ margin: 0 }}>Без утверждения итоговый статус — «Согласован».</p>
          </>
        )}
      </section>

      {drag.ghost && (
        <div className="ds-drag-ghost" style={{ left: drag.ghost.x, top: drag.ghost.y }}>
          <Icon name="drag" size={18} />
          {drag.ghost.label}
        </div>
      )}

      <AddSheet
        target={adding}
        state={state}
        context={context}
        onClose={() => setAdding(null)}
        onPick={(member) => {
          const target = adding;
          if (!target) return;
          setState((current) =>
            target.kind === 'choice'
              ? resolveChoice(current, target.slotId, member.user, context)
              : addPerson(current, member, target, context));
          setAdding(null);
        }}
      />

      <SlotMenu
        slot={menuSlot}
        state={state}
        locked={menuSlot !== null && isLocked(state, menuSlot, context)}
        onClose={() => setMenuSlotId(null)}
        onMove={(target) => {
          if (menuSlotId) setState((current) => moveSlot(current, menuSlotId, target));
          setMenuSlotId(null);
        }}
        onRemove={() => {
          if (menuSlotId) setState((current) => removeSlot(current, menuSlotId, context));
          setMenuSlotId(null);
        }}
      />

      <BottomSheet open={stageMenu !== null} onClose={() => setStageMenu(null)} title={`Этап ${(stageMenu ?? 0) + 1}`}>
        <List label="Порядок этапа">
          {stageMenu !== null && stageMenu > 0 && (
            <Row
              title="Поставить раньше"
              subtitle={`станет этапом ${stageMenu}`}
              onClick={() => {
                setState((current) => moveStage(current, stageMenu, -1));
                setStageMenu(null);
              }}
            />
          )}
          {stageMenu !== null && stageMenu < state.stages.length - 1 && (
            <Row
              title="Поставить позже"
              subtitle={`станет этапом ${stageMenu + 2}`}
              onClick={() => {
                setState((current) => moveStage(current, stageMenu, 1));
                setStageMenu(null);
              }}
            />
          )}
        </List>
      </BottomSheet>
    </Page>
  );
}

function SlotRow({
  slot,
  locked,
  carried,
  onOpen,
  onRemove,
  onDragStart,
}: {
  slot: Slot;
  locked: boolean;
  /** Прежнее решение переносится — подпись под именем; null — нет. */
  carried: string | null;
  onOpen: () => void;
  onRemove: () => void;
  onDragStart: (event: ReactPointerEvent<HTMLSpanElement>) => void;
}) {
  const nobody = slot.kind === 'choice' && slot.candidates.length === 0;
  const title = slot.kind === 'person'
    ? slot.name
    : nobody ? `${slot.roleName} — нет сотрудника с этой ролью` : `${slot.roleName}: выберите сотрудника`;
  const subtitle = slot.kind === 'person'
    ? [slot.roleName, carried].filter(Boolean).join(', ')
    : nobody
      ? 'Назначить роль или сделать её необязательной может администратор'
      : `Роль есть у нескольких: ${slot.candidates.map((candidate) => candidate.fullName).join(', ')}`;
  return (
    <div className="ds-row" role="listitem" style={{ paddingLeft: 8 }}>
      {/* Только для пальца и мыши: с клавиатуры и экранного диктора человек переносится через меню (нажатие на него). */}
      <span className="ds-drag-handle" aria-hidden="true" onPointerDown={onDragStart}>
        <Icon name="drag" size={20} />
      </span>
      <button
        type="button"
        onClick={onOpen}
        style={{
          flex: 1,
          minWidth: 0,
          minHeight: 44,
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          gap: 2,
          padding: 0,
          border: 'none',
          background: 'none',
          color: 'inherit',
          font: 'inherit',
          textAlign: 'left',
          cursor: 'pointer',
        }}
      >
        <span className="ds-row__title" style={slot.kind === 'choice' ? { color: nobody ? 'var(--status-danger-fg)' : 'var(--status-warning-fg)' } : undefined}>{title}</span>
        <span className="ds-row__subtitle">{subtitle}</span>
      </button>
      {locked ? (
        <span
          title="Обязательный по правилам компании — убрать нельзя"
          aria-label="Обязательный, убрать нельзя"
          style={{ display: 'inline-flex', width: 44, justifyContent: 'center', color: 'var(--text-tertiary)' }}
        >
          <Icon name="lock" size={20} />
        </span>
      ) : (
        <IconButton icon="close" label={`Убрать: ${title}`} onClick={onRemove} />
      )}
    </div>
  );
}

/** Кого добавить или выбрать для роли: уже стоящие в маршруте — неактивны с подписью, где стоят. */
function AddSheet({
  target,
  state,
  context,
  onClose,
  onPick,
}: {
  target: AddTarget | null;
  state: BuilderState;
  context: BuilderContext;
  onClose: () => void;
  onPick: (member: MemberView) => void;
}) {
  const choice = target?.kind === 'choice' ? allSlotsOf(state).find((slot) => slot.id === target.slotId) ?? null : null;
  const pool = choice && choice.kind === 'choice'
    ? context.colleagues.filter((member) => choice.candidates.some((candidate) => candidate.id === member.user.id))
    : context.colleagues;
  const options: SelectOption<number>[] = pool.map((member) => ({
    value: member.user.id,
    label: member.user.fullName,
    description: member.roles.map((role) => role.name).join(', '),
    disabledReason: placeOf(state, member.user.id) ?? undefined,
  }));
  let title = 'Добавить согласующего';
  if (choice && choice.kind === 'choice') title = `Кто будет «${choice.roleName}»`;
  else if (target?.kind === 'endorser') title = 'Кто утверждает';
  // В пустом маршруте «+ Согласующий» — это первый этап, а не «новый».
  else if (target?.kind === 'newStage') title = state.stages.length === 0 ? 'Добавить согласующего' : 'Новый этап';
  else if (target?.kind === 'stage') title = `Добавить в этап ${target.index + 1}`;
  return (
    <SelectSheet
      open={target !== null}
      onClose={onClose}
      title={title}
      options={options}
      searchable={options.length > 6}
      emptyText="Добавить некого: у других сотрудников нет ролей. Роли назначает администратор."
      onSelect={(userId) => {
        const member = pool.find((item) => item.user.id === userId);
        if (member) onPick(member);
      }}
    />
  );
}

/** Меню человека: перенести в любой этап, в новый этап, в утверждение — дублирует перетаскивание. */
function SlotMenu({
  slot,
  state,
  locked,
  onClose,
  onMove,
  onRemove,
}: {
  slot: Slot | null;
  state: BuilderState;
  locked: boolean;
  onClose: () => void;
  onMove: (target: Target) => void;
  onRemove: () => void;
}) {
  const where = slot ? state.stages.findIndex((stage) => stage.slots.some((item) => item.id === slot.id)) : -1;
  const isEndorser = slot !== null && state.endorser?.id === slot.id;
  return (
    <BottomSheet open={slot !== null} onClose={onClose} title={slot?.kind === 'person' ? slot.name : slot?.roleName ?? ''}>
      <p className="ds-section__hint" style={{ margin: 0 }}>Перенести</p>
      <List label="Перенести">
        {state.stages.map((stage, index) =>
          index === where ? null : (
            <Row
              key={stage.id}
              title={`В этап ${index + 1}`}
              subtitle={`вместе с: ${stage.slots.map((item) => (item.kind === 'person' ? item.name : item.roleName)).join(', ')}`}
              onClick={() => onMove({ kind: 'stage', index })}
            />
          ))}
        <Row title="Отдельным этапом в начале" onClick={() => onMove({ kind: 'newStage', index: 0 })} />
        <Row title="Отдельным этапом в конце" subtitle="перед утверждением" onClick={() => onMove({ kind: 'newStage', index: state.stages.length })} />
        {!isEndorser && (
          <Row
            title="Сделать утверждающим"
            subtitle={state.endorser?.kind === 'person' ? `${state.endorser.name} станет последним этапом согласования` : 'последний шаг маршрута'}
            onClick={() => onMove({ kind: 'endorser' })}
          />
        )}
      </List>
      {locked ? (
        <Notice icon="lock">Обязательный по правилам компании для этого вида документа — перенести можно, убрать нельзя.</Notice>
      ) : (
        <Button variant="danger" stretched icon="close" onClick={onRemove}>
          Убрать из маршрута
        </Button>
      )}
    </BottomSheet>
  );
}

/**
 * Перетаскивание за ручку ⋮⋮: указатель захватывается ручкой, поэтому прокрутка страницы не мешает; цель —
 * элемент с data-drop под пальцем. У края экрана список прокручивается сам. Только за ручку: так перетаскивание не
 * спорит со скроллом, а нажатие на человека открывает меню с тем же переносом.
 */
function useDragAndDrop(onDrop: (slotId: string, target: Target) => void) {
  const [ghost, setGhost] = useState<{ x: number; y: number; label: string } | null>(null);
  const [hover, setHover] = useState<string | null>(null);
  const dragged = useRef<string | null>(null);
  const hoverRef = useRef<string | null>(null);
  const pointerY = useRef(0);
  const onDropRef = useRef(onDrop);
  onDropRef.current = onDrop;
  const active = ghost !== null;

  // Автопрокрутка у края: пока тянут у верхнего или нижнего края области, она прокручивается.
  useEffect(() => {
    if (!active) return undefined;
    const scroller = document.querySelector('[data-scroll-root]');
    const timer = window.setInterval(() => {
      if (!scroller) return;
      const rect = scroller.getBoundingClientRect();
      if (pointerY.current < rect.top + 64) scroller.scrollBy({ top: -14 });
      else if (pointerY.current > rect.bottom - 64) scroller.scrollBy({ top: 14 });
    }, 30);
    return () => window.clearInterval(timer);
  }, [active]);

  function targetAt(x: number, y: number): string | null {
    for (const element of document.elementsFromPoint(x, y)) {
      const key = element instanceof HTMLElement ? element.closest<HTMLElement>('[data-drop]')?.dataset.drop : undefined;
      if (key) return key;
    }
    return null;
  }

  function start(event: ReactPointerEvent<HTMLSpanElement>, slot: Slot) {
    event.preventDefault();
    const handle = event.currentTarget;
    handle.setPointerCapture(event.pointerId);
    dragged.current = slot.id;
    pointerY.current = event.clientY;
    const label = slot.kind === 'person' ? slot.name : slot.roleName;
    setGhost({ x: event.clientX, y: event.clientY, label });

    const move = (moveEvent: PointerEvent) => {
      pointerY.current = moveEvent.clientY;
      setGhost({ x: moveEvent.clientX, y: moveEvent.clientY, label });
      const key = targetAt(moveEvent.clientX, moveEvent.clientY);
      hoverRef.current = key;
      setHover(key);
    };
    // Отмена жеста системой (звонок, системный жест, перехват прокрутки) — не перенос: человек остаётся на месте
    //. Переносит только отпускание пальца над целью.
    const finish = (dropped: boolean) => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', end);
      handle.removeEventListener('pointercancel', cancel);
      const target = dropped && hoverRef.current ? parseTarget(hoverRef.current) : null;
      if (dragged.current && target) onDropRef.current(dragged.current, target);
      dragged.current = null;
      hoverRef.current = null;
      setGhost(null);
      setHover(null);
    };
    const end = () => finish(true);
    const cancel = () => finish(false);
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', end);
    handle.addEventListener('pointercancel', cancel);
  }

  return { start, ghost, hover, active };
}
