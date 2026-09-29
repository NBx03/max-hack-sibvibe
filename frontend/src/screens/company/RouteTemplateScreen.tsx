import { Navigate, useParams } from 'react-router-dom';
import { useSession } from '../../app/SessionContext';
import { useState } from 'react';
import { getDocumentTypes } from '../../api/documents';
import { errorMessage } from '../../api/errors';
import { getRoles } from '../../api/admin';
import { getColleagues, getRoutes, replaceRouteTemplate } from '../../api/organization';
import { useAsync } from '../../hooks/useAsync';
import type { DocumentType, RoleRef, RouteOverview } from '../../api/types';
import {
  BottomSheet,
  Button,
  ConfirmSheet,
  ErrorState,
  Icon,
  IconButton,
  InlineError,
  List,
  Loading,
  Muted,
  Page,
  PageHeader,
  Row,
  SelectSheet,
  Toast,
} from '../../ui';
import type { SelectOption } from '../../ui';
import { ROUTE_PATTERNS } from '../../routes/paths';
import { useGoBack } from '../../routes/backNavigation';
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
  type Target,
  type TemplateSlot,
  type TemplateState,
} from './routeTemplateBuilder';

/**
 * Редактор состава шаблона маршрута — этапы, роли на этапах, их порядок, и здесь же обязательность. Отдельный
 * экран, а не панель поверх «Компании»: содержимого достаточно, чтобы над панелью открывались ещё
 * панели, и «Сохранить» уезжало вниз со списком — здесь оно закреплено, как у конструктора маршрута документа.
 *
 * В отличие от конструктора маршрута документа (routeBuilder.ts) и от точечного, администратор убирает и
 * переставляет любую роль, включая обязательные: запрет «обязательную не убрать» — ограничение автора при
 * отправке, а не того, кто меняет сам шаблон.
 */
export function RouteTemplateScreen() {
  const { typeId } = useParams<{ typeId: string }>();
  const documentTypeId = Number(typeId);
  const { me } = useSession();
  const isAdmin = Boolean(me?.actingAs ? me.actingAs.isAdmin : me?.membership?.isAdmin);
  const query = useAsync(() => Promise.all([getRoutes(), getDocumentTypes(), getRoles()]), [documentTypeId]);

  // Редактор — только администратору: по прямому адресу сотрудник видел бы редактор, а сохранить не смог бы (403).
  if (!isAdmin) return <Navigate to={ROUTE_PATTERNS.company} replace />;

  if (query.status === 'loading') {
    return (
      <Page header={<PageHeader back={{ to: ROUTE_PATTERNS.company, label: 'Компания' }} title="Маршрут" />}>
        <Loading />
      </Page>
    );
  }
  if (query.status === 'error') {
    return (
      <Page header={<PageHeader back={{ to: ROUTE_PATTERNS.company, label: 'Компания' }} title="Маршрут" />}>
        <ErrorState message={errorMessage(query.error)} onRetry={query.refresh} />
      </Page>
    );
  }
  const [routes, types, roles] = query.data;
  const type = types.find((item) => item.id === documentTypeId);
  const route = routes.find((item) => item.documentTypeId === documentTypeId);
  // Нет вида или шаблона — назад в «Компанию». <Navigate>, а не переход прямо во время отрисовки. «Другой документ»
  // редактируется наравне с остальными.
  if (!type || !route) {
    return <Navigate to={ROUTE_PATTERNS.company} replace />;
  }
  return <RouteTemplateEditor type={type} route={route} allRoles={roles} />;
}

function RouteTemplateEditor({
  type,
  route,
  allRoles,
}: {
  type: DocumentType;
  route: RouteOverview;
  allRoles: RoleRef[];
}) {
  const goBack = useGoBack();
  const [state, setState] = useState<TemplateState>(() => initialState(route));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [menuSlotId, setMenuSlotId] = useState<string | null>(null);
  const [stageMenu, setStageMenu] = useState<number | null>(null);
  const [adding, setAdding] = useState<Target | null>(null);
  const [confirmRole, setConfirmRole] = useState<{ slotId: string; name: string } | null>(null);
  const [confirmLeave, setConfirmLeave] = useState(false);
  const [saved, setSaved] = useState(route);
  const [toast, setToast] = useState<string | null>(null);

  const dirty = isDirty(state, saved);
  const colleagues = useAsync(() => getColleagues(), []);
  const hasCarrier = (roleId: number) =>
    colleagues.status !== 'ready' || colleagues.data.some((member) => member.roles.some((role) => role.id === roleId));

  const usedIds = usedRoleIds(state);
  const availableRoles = allRoles.filter((role) => !usedIds.has(role.id));
  const menuSlot = menuSlotId
    ? state.stages.flatMap((stage) => stage.slots).find((slot) => slot.id === menuSlotId) ?? null
    : null;

  function leave() {
    if (dirty) {
      setConfirmLeave(true);
    } else {
      goBack(ROUTE_PATTERNS.company);
    }
  }

  function toggleMandatory(slot: TemplateSlot) {
    if (!slot.mandatory && !hasCarrier(slot.roleId)) {
      setConfirmRole({ slotId: slot.id, name: slot.roleName });
      return;
    }
    setState((current) => setMandatory(current, slot.id, !slot.mandatory));
  }

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const routes = await replaceRouteTemplate(type.id, toRequest(state));
      const next = routes.find((item) => item.documentTypeId === type.id);
      if (next) {
        setSaved(next);
        setState(initialState(next));
      }
      // Кнопка просто стала неактивной — без сообщения непонятно, сохранилось ли.
      setToast('Маршрут сохранён');
    } catch (saveError) {
      setError(errorMessage(saveError));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Page
      header={
        <header className="ds-header">
          {/* Не PageHeader.back: тот уходит сразу через useGoBack, а здесь несохранённые правки нужно подтвердить
 — тот же приём, что и системная кнопка MAX не может дать без отдельного механизма. */}
          <button type="button" className="ds-back" onClick={leave}>
            <Icon name="back" size={20} />
            Компания
          </button>
          <div className="ds-header__row">
            <h1 className="ds-header__title">{`Маршрут — ${type.name}`}</h1>
          </div>
          <p className="ds-header__subtitle">Действует на новые отправки</p>
        </header>
      }
      footer={
        <>
          {error && <InlineError>{error}</InlineError>}
          <Button
            variant="primary"
            stretched
            loading={saving}
            disabled={!dirty || state.stages.length === 0}
            onClick={() => void save()}
          >
            Сохранить
          </Button>
        </>
      }
    >
      {state.stages.length === 0 && <Muted>Нужна хотя бы одна роль — иначе шаблон не сохранится.</Muted>}
      {state.stages.map((stage, index) => {
        const isEndorsement = index === state.stages.length - 1 && stage.slots.length === 1;
        return (
          <section key={stage.id} className="ds-card">
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, minHeight: 32 }}>
              <h2 className="ds-section__title" style={{ flex: 1 }}>
                Этап {index + 1}
                {stage.slots.length > 1 && <span style={{ fontWeight: 400, color: 'var(--text-secondary)' }}> — одновременно</span>}
                {isEndorsement && <span style={{ fontWeight: 400, color: 'var(--text-secondary)' }}> — утверждение</span>}
              </h2>
              {state.stages.length > 1 && (
                <IconButton icon="more" label={`Порядок этапа ${index + 1}`} onClick={() => setStageMenu(index)} />
              )}
            </div>
            <List label={`Этап ${index + 1}`}>
              {stage.slots.map((slot) => (
                <RoleRow
                  key={slot.id}
                  slot={slot}
                  hasCarrier={hasCarrier(slot.roleId)}
                  onOpen={() => setMenuSlotId(slot.id)}
                  onRemove={() => setState((current) => removeRole(current, slot.id))}
                />
              ))}
            </List>
            <Button compact icon="plus" disabled={availableRoles.length === 0} onClick={() => setAdding({ kind: 'stage', index })}>
              Роль
            </Button>
          </section>
        );
      })}
      <button
        type="button"
        className="ds-dropzone"
        disabled={availableRoles.length === 0}
        onClick={() => setAdding({ kind: 'newStage', index: state.stages.length })}
      >
        <Icon name="plus" size={20} />
        Новый этап
      </button>

      <AddRoleSheet
        target={adding}
        roles={availableRoles}
        onClose={() => setAdding(null)}
        onPick={(role) => {
          if (adding) setState((current) => addRole(current, role, adding));
          setAdding(null);
        }}
      />

      <RoleMenu
        slot={menuSlot}
        state={state}
        onClose={() => setMenuSlotId(null)}
        onToggleMandatory={() => {
          if (menuSlot) toggleMandatory(menuSlot);
          setMenuSlotId(null);
        }}
        onMove={(target) => {
          if (menuSlotId) setState((current) => moveRole(current, menuSlotId, target));
          setMenuSlotId(null);
        }}
        onRemove={() => {
          if (menuSlotId) setState((current) => removeRole(current, menuSlotId));
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

      <ConfirmSheet
        open={confirmRole !== null}
        onClose={() => setConfirmRole(null)}
        title={`Сделать «${confirmRole?.name ?? ''}» обязательной?`}
        description={`Сейчас ни у кого в компании нет роли «${confirmRole?.name ?? ''}». Пока её никому не назначат, документы вида «${type.name}» нельзя будет отправить на согласование.`}
        confirmLabel="Всё равно сделать"
        onConfirm={() => {
          if (confirmRole) setState((current) => setMandatory(current, confirmRole.slotId, true));
          setConfirmRole(null);
        }}
      />

      <ConfirmSheet
        open={confirmLeave}
        onClose={() => setConfirmLeave(false)}
        title="Выйти без сохранения?"
        description="Изменения в составе маршрута потеряются."
        confirmLabel="Выйти"
        destructive
        onConfirm={() => {
          setConfirmLeave(false);
          goBack(ROUTE_PATTERNS.company);
        }}
      />
      {toast && <Toast message={toast} onDismiss={() => setToast(null)} />}
    </Page>
  );
}

function RoleRow({
  slot,
  hasCarrier,
  onOpen,
  onRemove,
}: {
  slot: TemplateSlot;
  hasCarrier: boolean;
  onOpen: () => void;
  onRemove: () => void;
}) {
  return (
    <div className="ds-row" role="listitem">
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
        <span className="ds-row__title" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          {slot.roleName}
          {slot.mandatory && <Icon name="lock" size={14} label="Обязательна" />}
        </span>
        <span className="ds-row__subtitle">
          {slot.mandatory ? 'Обязательна — автор не убирает' : 'Не обязательна'}
          {!hasCarrier && ', сейчас ни у кого нет этой роли'}
        </span>
      </button>
      <IconButton icon="close" label={`Убрать роль: ${slot.roleName}`} onClick={onRemove} />
    </div>
  );
}

/** Куда добавить роль: новый этап или существующий — список ролей, которых ещё нет в шаблоне. */
function AddRoleSheet({
  target,
  roles,
  onClose,
  onPick,
}: {
  target: Target | null;
  roles: RoleRef[];
  onClose: () => void;
  onPick: (role: RoleRef) => void;
}) {
  const options: SelectOption<number>[] = roles.map((role) => ({ value: role.id, label: role.name }));
  const title = target?.kind === 'stage' ? `Роль в этап ${target.index + 1}` : 'Роль в новый этап';
  return (
    <SelectSheet
      open={target !== null}
      onClose={onClose}
      title={title}
      options={options}
      searchable={options.length > 6}
      emptyText="Все роли компании уже в маршруте"
      onSelect={(roleId) => {
        const role = roles.find((item) => item.id === roleId);
        if (role) onPick(role);
      }}
    />
  );
}

/** Меню роли: обязательность, перенос в любой этап, удаление — как в конструкторе маршрута документа. */
function RoleMenu({
  slot,
  state,
  onClose,
  onToggleMandatory,
  onMove,
  onRemove,
}: {
  slot: TemplateSlot | null;
  state: TemplateState;
  onClose: () => void;
  onToggleMandatory: () => void;
  onMove: (target: Target) => void;
  onRemove: () => void;
}) {
  const where = slot ? state.stages.findIndex((stage) => stage.slots.some((item) => item.id === slot.id)) : -1;
  return (
    <BottomSheet open={slot !== null} onClose={onClose} title={slot?.roleName ?? ''}>
      <List label="Обязательность">
        <Row
          title={slot?.mandatory ? 'Сделать необязательной' : 'Сделать обязательной'}
          subtitle={slot?.mandatory ? 'Автор сможет убрать эту роль из своего маршрута' : 'Автор не сможет убрать эту роль из маршрута'}
          onClick={onToggleMandatory}
        />
      </List>
      <p className="ds-section__hint" style={{ margin: 0 }}>Перенести</p>
      <List label="Перенести">
        {state.stages.map((stage, index) =>
          index === where ? null : (
            <Row
              key={stage.id}
              title={`В этап ${index + 1}`}
              subtitle={`вместе с: ${stage.slots.map((item) => item.roleName).join(', ')}`}
              onClick={() => onMove({ kind: 'stage', index })}
            />
          ))}
        <Row title="Отдельным этапом в начале" onClick={() => onMove({ kind: 'newStage', index: 0 })} />
        <Row title="Отдельным этапом в конце" onClick={() => onMove({ kind: 'newStage', index: state.stages.length })} />
      </List>
      <Button variant="danger" stretched icon="close" onClick={onRemove}>
        Убрать роль из маршрута
      </Button>
    </BottomSheet>
  );
}
