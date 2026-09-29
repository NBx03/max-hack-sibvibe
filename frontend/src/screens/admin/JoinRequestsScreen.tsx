import { Switch, Typography } from '@maxhub/max-ui';
import { useCallback, useEffect, useState } from 'react';
import { approveJoinRequest, getJoinRequests, getRoles, rejectJoinRequest } from '../../api/admin';
import { ApiError, errorMessage } from '../../api/errors';
import type { JoinRequestView, RoleRef } from '../../api/types';
import { RolePicker } from '../../components/base/RolePicker';
import { Button, ButtonRow, Card, EmptyState, Initials, Toast } from '../../ui';
import { DocumentScreen as AdminScreen, LoadingState, Notice } from '../documents/DocumentFormParts';
import { ADMIN_BACK, AdminLoadError, formatDateTime } from './AdminFormParts';

interface RowChoice {
  roleIds: number[];
  isAdmin: boolean;
}

export function JoinRequestsScreen() {
  const [requests, setRequests] = useState<JoinRequestView[] | null>(null);
  const [roles, setRoles] = useState<RoleRef[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [choices, setChoices] = useState<Record<number, RowChoice>>({});
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rowErrors, setRowErrors] = useState<Record<number, string>>({});
  const [toast, setToast] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const [requestList, roleList] = await Promise.all([getJoinRequests(), getRoles()]);
      setRequests(requestList);
      setRoles(roleList);
      // Вернувшемуся сотруднику прежние роли отмечены заранее: администратору остаётся «Принять».
      const known = new Set(roleList.map((role) => role.id));
      setChoices(Object.fromEntries(requestList.map((request) => [
        request.id,
        { roleIds: (request.previousRoles ?? []).map((role) => role.id).filter((id) => known.has(id)), isAdmin: false },
      ])));
    } catch (error) {
      setLoadError(error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  function setChoice(requestId: number, patch: Partial<RowChoice>) {
    setChoices((current) => ({ ...current, [requestId]: { ...current[requestId], ...patch } }));
  }

  function removeRequest(requestId: number) {
    setRequests((current) => (current ?? []).filter((request) => request.id !== requestId));
  }

  /**
   * Заявку уже решил другой администратор (409 INVALID_STATE) - строка в списке устарела,
   * а не временно недоступна. Убираем её и показываем тостом текст бэкенда, а не оставляем
   * висеть с ошибкой. Остальные ошибки (сеть, 403) - в строке как раньше.
   */
  function handleRowError(request: JoinRequestView, error: unknown) {
    if (error instanceof ApiError && error.status === 409) {
      removeRequest(request.id);
      setToast(error.message);
      return;
    }
    setRowErrors((current) => ({
      ...current,
      [request.id]: errorMessage(error),
    }));
  }

  async function approve(request: JoinRequestView) {
    const choice = choices[request.id] ?? { roleIds: [], isAdmin: false };
    setBusyId(request.id);
    setRowErrors((current) => ({ ...current, [request.id]: '' }));
    try {
      await approveJoinRequest(request.id, choice.roleIds, choice.isAdmin);
      removeRequest(request.id);
      setToast(`Заявка «${request.user.fullName}» принята`);
    } catch (error) {
      handleRowError(request, error);
    } finally {
      setBusyId(null);
    }
  }

  async function reject(request: JoinRequestView) {
    setBusyId(request.id);
    setRowErrors((current) => ({ ...current, [request.id]: '' }));
    try {
      await rejectJoinRequest(request.id);
      removeRequest(request.id);
      setToast(`Заявка «${request.user.fullName}» отклонена`);
    } catch (error) {
      handleRowError(request, error);
    } finally {
      setBusyId(null);
    }
  }

  if (loading) return <LoadingState />;
  if (loadError !== null || !requests) {
    return (
      <AdminScreen title="Заявки" back={ADMIN_BACK}>
        <AdminLoadError error={loadError} onRetry={() => void load()} />
      </AdminScreen>
    );
  }

  return (
    <AdminScreen title="Заявки" back={ADMIN_BACK}>

      {requests.length === 0 ? (
        // Пришло сообщение о заявке, а здесь пусто — значит, её уже решил другой администратор или отозвал сам
        // человек. Говорим это прямо, но коротко (было пять строк объяснения).
        <EmptyState
          title="Новых заявок нет"
          text="Здесь появятся заявки по коду компании. Нет той, о которой сообщил бот, — её уже рассмотрели или отозвали."
        />
      ) : (
        requests.map((request) => {
          const choice = choices[request.id] ?? { roleIds: [], isAdmin: false };
          const busy = busyId === request.id;
          const canApprove = choice.roleIds.length > 0 || choice.isAdmin;
          const previousRoles = request.previousRoles ?? [];
          // Карточка заявки — как лист сотрудника в «Участниках»: человек строкой с инициалами, роли, права, действия
          return (
            <Card key={request.id}>
              <div className="ds-request__who">
                <Initials name={request.user.fullName} />
                <span className="ds-row__body">
                  <span className="ds-row__title">{request.user.fullName}</span>
                  <span className="ds-row__subtitle">
                    {previousRoles.length > 0
                      ? 'Возвращается, прежние роли отмечены'
                      : `Заявка ${formatDateTime(request.createdAt)}`}
                  </span>
                </span>
              </div>

              <RolePicker
                roles={roles}
                selectedRoleIds={choice.roleIds}
                onChange={(roleIds) => setChoice(request.id, { roleIds })}
                disabled={busy}
              />

              <label style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <Switch
                  checked={choice.isAdmin}
                  disabled={busy}
                  onChange={(event) => setChoice(request.id, { isAdmin: event.target.checked })}
                />
                <Typography.Text>Права администратора</Typography.Text>
              </label>

              {rowErrors[request.id] && <Notice tone="danger">{rowErrors[request.id]}</Notice>}

              <ButtonRow>
                <Button
                  variant="primary"
                  loading={busy}
                  disabled={busy || !canApprove}
                  onClick={() => void approve(request)}
                >
                  Принять
                </Button>
                <Button variant="secondary" disabled={busy} onClick={() => void reject(request)}>
                  Отклонить
                </Button>
              </ButtonRow>
              {!canApprove && (
                // Почему «Принять» недоступна — рядом с ней, а не догадкой.
                <p className="ds-section__hint" style={{ margin: 0 }}>Чтобы принять, отметьте роль или права администратора</p>
              )}
            </Card>
          );
        })
      )}

      {toast && <Toast message={toast} onDismiss={() => setToast(null)} />}
    </AdminScreen>
  );
}
