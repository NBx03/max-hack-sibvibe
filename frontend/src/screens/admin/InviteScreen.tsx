import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ROUTE_PATTERNS } from '../../routes/paths';
import {
  createPersonalInvite,
  getInviteCode,
  getMembers,
  getPersonalInvites,
  getRoles,
  regenerateInviteCode,
  revokePersonalInvite,
} from '../../api/admin';
import { errorMessage } from '../../api/errors';
import type { InviteCodeInfo, PersonalInviteView, RoleRef } from '../../api/types';
import { BottomSheet, Button, ButtonRow, Card, ConfirmSheet, List, Row, Section, Toast } from '../../ui';
import { RolePicker } from '../../components/base/RolePicker';
import { copyToClipboard, shareOrCopy } from '../../lib/share';
import { DocumentScreen as AdminScreen, LoadingState, Notice, stackStyle } from '../documents/DocumentFormParts';
import { ADMIN_BACK, AdminLoadError, formatTimeLeft, roleNames } from './AdminFormParts';
import { onlyAdminLeft } from './memberChanges';

function upperFirst(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function InviteScreen() {
  const navigate = useNavigate();
  const [code, setCode] = useState<InviteCodeInfo | null>(null);
  const [roles, setRoles] = useState<RoleRef[]>([]);
  const [personalInvites, setPersonalInvites] = useState<PersonalInviteView[] | null>(null);
  // Экран 2 (SCREENS.md): подсказка «Пригласите сотрудников…», пока администратор один -
  //  ведёт создателя компании прямо сюда, а не на отдельный экран.
  const [soloAdmin, setSoloAdmin] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);

  const [regenerating, setRegenerating] = useState(false);
  const [confirmRegenerate, setConfirmRegenerate] = useState(false);

  const [createOpen, setCreateOpen] = useState(false);
  const [newRoleIds, setNewRoleIds] = useState<number[]>([]);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  // Заполнен после успешного создания - лист остаётся открытым с готовой ссылкой и
  // «Поделиться», а не закрывается сразу (SCREENS.md: «→ ссылка и «Поделиться»»).
  const [createdInvite, setCreatedInvite] = useState<PersonalInviteView | null>(null);

  // Ошибка — там, где её причина: у кода на экране, у перевыпуска — в его подтверждении, у действий
  // с личной ссылкой — в её листе. Каждое действие и открытие листа сбрасывает прежнюю ошибку.
  const [screenError, setScreenError] = useState<string | null>(null);
  const [regenerateError, setRegenerateError] = useState<string | null>(null);
  const [sheetError, setSheetError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [openedInvite, setOpenedInvite] = useState<PersonalInviteView | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const [codeInfo, roleList, memberList] = await Promise.all([getInviteCode(), getRoles(), getMembers()]);
      setCode(codeInfo);
      setRoles(roleList);
      setSoloAdmin(onlyAdminLeft(memberList));
      // Личные ссылки: если недоступны (эндпоинта нет в этой сборке),
      // основной блок «Код компании» всё равно должен показаться.
      try {
        setPersonalInvites(await getPersonalInvites());
      } catch {
        setPersonalInvites(null);
      }
    } catch (error) {
      setLoadError(error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /** inSheet — делятся личной ссылкой из её листа: ошибку показать там же, а не под листом. */
  async function share(url: string, label: string, inSheet: boolean) {
    const setError = inSheet ? setSheetError : setScreenError;
    setError(null);
    const result = await shareOrCopy(url, label);
    if (result === 'copied') setToast('Ссылка скопирована');
    if (result === 'failed') setError('Не удалось поделиться ссылкой');
  }

  function openInvite(invite: PersonalInviteView) {
    setSheetError(null);
    setOpenedInvite(invite);
  }

  async function copyCode() {
    if (!code) return;
    setScreenError(null);
    if (await copyToClipboard(code.code)) {
      setToast('Код скопирован');
    } else {
      setScreenError('Не удалось скопировать код');
    }
  }

  async function regenerate() {
    // ConfirmSheet не блокирует свою кнопку по loading - защита от повторного тапа здесь.
    if (regenerating) return;
    setRegenerating(true);
    setRegenerateError(null);
    try {
      setCode(await regenerateInviteCode());
      setConfirmRegenerate(false);
      setToast('Код перевыпущен');
    } catch (error) {
      setRegenerateError(errorMessage(error));
    } finally {
      setRegenerating(false);
    }
  }

  async function createLink() {
    if (newRoleIds.length === 0) return;
    setCreating(true);
    setCreateError(null);
    setSheetError(null);
    try {
      const created = await createPersonalInvite(newRoleIds);
      setPersonalInvites((current) => [created, ...(current ?? [])]);
      setCreatedInvite(created);
      setNewRoleIds([]);
    } catch (error) {
      setCreateError(errorMessage(error));
    } finally {
      setCreating(false);
    }
  }

  function closeCreateSheet() {
    setCreateOpen(false);
    setCreatedInvite(null);
    setCreateError(null);
    setSheetError(null);
  }

  async function revoke(invite: PersonalInviteView) {
    setSheetError(null);
    try {
      await revokePersonalInvite(invite.id);
      setPersonalInvites((current) => (current ?? []).filter((item) => item.id !== invite.id));
      setOpenedInvite(null);
      setToast('Ссылка отозвана');
    } catch (error) {
      setSheetError(errorMessage(error));
    }
  }

  if (loading) return <LoadingState />;
  if (loadError !== null || !code) {
    return (
      <AdminScreen title="Приглашение" back={ADMIN_BACK}>
        <AdminLoadError error={loadError} onRetry={() => void load()} />
      </AdminScreen>
    );
  }

  return (
    <AdminScreen
      title="Приглашение"
      back={ADMIN_BACK}
      // Сразу после создания компании (в ней пока только вы) — понятный выход к работе: раньше экран кончался, а
      // «назад» вело в «Компанию», не к документам.
      footer={soloAdmin ? (
        <Button variant="primary" stretched onClick={() => navigate(ROUTE_PATTERNS.home)}>
          Готово
        </Button>
      ) : undefined}
    >
      {soloAdmin && (
        <Notice>
          Перешлите сотрудникам код или ссылку. Пока их нет, за директора согласуете вы — автоматически.
        </Notice>
      )}

      {/* Типографика и группы — как в «Компании» и «Участниках»: заголовок раздела над плашкой, а не крупный
          заголовок внутри неё. */}
      <Section title="Код компании" hint="Для рабочего чата: по коду подают заявку, вы её принимаете">
        <Card>
          <span className="ds-invite-code">{code.code}</span>
          <span className="ds-invite-link">{code.link}</span>
          <ButtonRow>
            <Button variant="primary" onClick={() => void share(code.link, 'Приглашение в компанию', false)}>Поделиться</Button>
            <Button variant="secondary" onClick={() => void copyCode()}>Скопировать код</Button>
          </ButtonRow>
        </Card>
        <Button variant="ghost" compact onClick={() => setConfirmRegenerate(true)}>Перевыпустить код</Button>
      </Section>

      {screenError && <Notice tone="danger">{screenError}</Notice>}

      {personalInvites !== null && (
        <Section title="Личные ссылки" hint="Одноразовые, на 72 часа, роли выбраны заранее — вступают без заявки">
          <Button variant="secondary" icon="plus" onClick={() => setCreateOpen(true)} disabled={roles.length === 0}>
            Создать личную ссылку
          </Button>
          {personalInvites.length > 0 && (
            // Строка — роли и срок; «Поделиться» и «Отозвать» — в листе по нажатию, как у сотрудника в «Участниках»:
            // две кнопки в каждой строке на 360 px сжимали роли в столбик (про «спам» ссылок).
            <List label="Неиспользованные личные ссылки">
              {personalInvites.map((invite) => (
                <Row
                  key={invite.id}
                  title={roleNames(invite.roles)}
                  subtitle={upperFirst(formatTimeLeft(invite.expiresAt))}
                  onClick={() => openInvite(invite)}
                />
              ))}
            </List>
          )}
        </Section>
      )}

      <ConfirmSheet
        open={confirmRegenerate}
        onClose={() => {
          setConfirmRegenerate(false);
          setRegenerateError(null);
        }}
        title="Перевыпустить код?"
        description="Старый код перестанет работать. Уже поданные заявки останутся."
        confirmLabel="Перевыпустить"
        busy={regenerating}
        error={regenerateError}
        onConfirm={() => void regenerate()}
      />

      <BottomSheet open={createOpen} onClose={closeCreateSheet} title="Личная ссылка" dismissible={!creating}>
        {createdInvite ? (
          <InviteActions
            invite={createdInvite}
            created
            onShare={() => void share(createdInvite.link, 'Личное приглашение в компанию', true)}
            onDone={closeCreateSheet}
            error={sheetError}
          />
        ) : (
          <div style={stackStyle}>
            <RolePicker roles={roles} selectedRoleIds={newRoleIds} onChange={setNewRoleIds} disabled={creating} />
            <Notice>Вступит тот, кто откроет первым. Отправляйте только в личные сообщения.</Notice>
            {createError && <Notice tone="danger">{createError}</Notice>}
            <Button
              stretched
              variant="primary"
              loading={creating}
              disabled={creating || newRoleIds.length === 0}
              onClick={() => void createLink()}
            >
              Создать
            </Button>
          </div>
        )}
      </BottomSheet>

      <BottomSheet open={openedInvite !== null} onClose={() => { setOpenedInvite(null); setSheetError(null); }} title="Личная ссылка">
        {openedInvite && (
          <InviteActions
            invite={openedInvite}
            onShare={() => void share(openedInvite.link, 'Личное приглашение в компанию', true)}
            onRevoke={() => void revoke(openedInvite)}
            error={sheetError}
          />
        )}
      </BottomSheet>

      {toast && <Toast message={toast} onDismiss={() => setToast(null)} />}
    </AdminScreen>
  );
}

/** Лист одной личной ссылки: кому она (роли) и сколько живёт, «Поделиться»; только что созданной — «Готово». */
function InviteActions({
  invite,
  created,
  onShare,
  onRevoke,
  onDone,
  error,
}: {
  invite: PersonalInviteView;
  error?: string | null;
  created?: boolean;
  onShare: () => void;
  onRevoke?: () => void;
  onDone?: () => void;
}) {
  return (
    <div style={stackStyle}>
      {created && <Notice tone="success">Ссылка создана</Notice>}
      <List>
        <Row title={roleNames(invite.roles)} subtitle={upperFirst(formatTimeLeft(invite.expiresAt))} />
      </List>
      <span className="ds-invite-link">{invite.link}</span>
      {error && <Notice tone="danger">{error}</Notice>}
      <Button variant="primary" stretched onClick={onShare}>Поделиться</Button>
      {onRevoke && <Button variant="danger" stretched onClick={onRevoke}>Отозвать ссылку</Button>}
      {onDone && <Button variant="secondary" stretched onClick={onDone}>Готово</Button>}
    </div>
  );
}
