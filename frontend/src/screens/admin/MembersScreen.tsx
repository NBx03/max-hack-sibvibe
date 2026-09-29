import { Switch, Typography } from '@maxhub/max-ui';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSession } from '../../app/SessionContext';
import { disableMember, getMembers, getRoles, setMemberAdmin, setMemberRoles } from '../../api/admin';
import { errorMessage } from '../../api/errors';
import type { MemberView, RoleRef } from '../../api/types';
import { BottomSheet, Button, ConfirmSheet, Initials, List, Muted, Row, Section, Toast } from '../../ui';
import { RolePicker } from '../../components/base/RolePicker';
import { DocumentScreen as AdminScreen, LoadingState, Notice, stackStyle } from '../documents/DocumentFormParts';
import { ADMIN_BACK, AdminLoadError, roleNames } from './AdminFormParts';
import { removedRoleIds, returnedDocumentsText } from './memberChanges';

function sameRoleIds(a: number[], b: number[]): boolean {
  if (a.length !== b.length) return false;
  const setB = new Set(b);
  return a.every((id) => setB.has(id));
}

export function MembersScreen() {
  const { me, refreshQuietly } = useSession();
  const [members, setMembers] = useState<MemberView[] | null>(null);
  const [roles, setRoles] = useState<RoleRef[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);

  const [editing, setEditing] = useState<MemberView | null>(null);
  const [editRoleIds, setEditRoleIds] = useState<number[]>([]);
  const [editIsAdmin, setEditIsAdmin] = useState(false);
  const [saving, setSaving] = useState(false);
  const [disabling, setDisabling] = useState(false);
  const [confirmDisable, setConfirmDisable] = useState(false);
  const [confirmRevoke, setConfirmRevoke] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [showExcluded, setShowExcluded] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const [memberList, roleList] = await Promise.all([getMembers(), getRoles()]);
      setMembers(memberList);
      setRoles(roleList);
    } catch (error) {
      setLoadError(error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Администратор не может изменить свои собственные роли и права (docs/DESIGN-DECISIONS.md, «Собственные
  // роли») - строку не делаем кликабельной вовсе, вместо показа кнопки, которую нельзя
  // нажать (docs/SCREENS.md, «Общие правила»).
  const selfUserId = me?.actingAs ? me.actingAs.user.id : me?.user.id;

  function openEdit(member: MemberView) {
    setEditing(member);
    setEditRoleIds(member.roles.map((role) => role.id));
    setEditIsAdmin(member.isAdmin);
    setEditError(null);
  }

  function closeEdit() {
    setEditing(null);
    setConfirmDisable(false);
    setEditError(null);
  }

  function applyUpdate(updated: MemberView) {
    setMembers((current) => (current ?? []).map((member) => (member.memberId === updated.memberId ? updated : member)));
  }

  /**
   * Только изменившиеся поля, каждое отдельным запросом: роли и права
   * администратора - независимые эндпоинты, и при ошибке после частичного успеха (роли
   * сохранились, права упали на LAST_ADMIN) список должен показывать правду с сервера,
   * а не то, что мы пытались отправить. Перечитываем список тихо, без общего LoadingState -
   * иначе исчез бы сам открытый лист вместе с текстом ошибки.
   */
  async function save(revokeConfirmed = false) {
    if (!editing) return;
    const rolesChanged = !sameRoleIds(editRoleIds, editing.roles.map((role) => role.id));
    const adminChanged = editIsAdmin !== editing.isAdmin;
    if (!rolesChanged && !adminChanged) {
      closeEdit();
      return;
    }
    // Администраторы равноправны: снять права — действие над равным, поэтому сначала подтверждение.
    if (editing.isAdmin && !editIsAdmin && !revokeConfirmed) {
      setConfirmRevoke(true);
      return;
    }
    setConfirmRevoke(false);
    setSaving(true);
    setEditError(null);
    try {
      let returned = 0;
      if (rolesChanged) {
        const changed = await setMemberRoles(editing.memberId, editRoleIds);
        applyUpdate(changed);
        returned = changed.returnedDocuments;
      }
      if (adminChanged) {
        applyUpdate(await setMemberAdmin(editing.memberId, editIsAdmin));
      }
      // Свои роли видны и на главной, и в «Компании» — сессию обновляем сразу.
      if (editing.user.id === selfUserId) await refreshQuietly();
      closeEdit();
      setToast(['Изменения сохранены.', returnedDocumentsText(returned)].filter(Boolean).join(' '));
    } catch (error) {
      try {
        const fresh = await getMembers();
        setMembers(fresh);
        const stillThere = fresh.find((member) => member.memberId === editing.memberId);
        if (stillThere) {
          // Поля листа — тоже со свежих данных: иначе после ошибки лист показывал бы то, что
          // пытались отправить, поверх того, что реально сохранено.
          setEditing(stillThere);
          setEditRoleIds(stillThere.roles.map((role) => role.id));
          setEditIsAdmin(stillThere.isAdmin);
        }
      } catch {
        // Не удалось обновить список - хотя бы покажем ошибку сохранения ниже.
      }
      setEditError(errorMessage(error));
    } finally {
      setSaving(false);
    }
  }

  async function disable() {
    if (!editing || disabling) return;
    setDisabling(true);
    setEditError(null);
    try {
      const updated = await disableMember(editing.memberId);
      applyUpdate(updated);
      setConfirmDisable(false);
      closeEdit();
      setToast(['Сотрудник исключён из компании.', returnedDocumentsText(updated.returnedDocuments)].filter(Boolean).join(' '));
    } catch (error) {
      setConfirmDisable(false);
      setEditError(errorMessage(error));
    } finally {
      setDisabling(false);
    }
  }

  const sortedMembers = useMemo(
    () => members?.slice().sort((left, right) => left.user.fullName.localeCompare(right.user.fullName, 'ru')) ?? [],
    [members],
  );
  // Исключённые — отдельно и внизу: запись остаётся ради истории решений и чтобы
  // вернувшийся по заявке человек занял прежнее место, но в общем списке она путала.
  const activeMembers = sortedMembers.filter((member) => member.status !== 'DISABLED');
  const excludedMembers = sortedMembers.filter((member) => member.status === 'DISABLED');

  if (loading) return <LoadingState />;
  if (loadError !== null || !members) {
    return (
      <AdminScreen title="Участники" back={ADMIN_BACK}>
        <AdminLoadError error={loadError} onRetry={() => void load()} />
      </AdminScreen>
    );
  }

  const editingSelf = editing !== null && editing.user.id === selfUserId;

  return (
    <AdminScreen title="Участники" back={ADMIN_BACK}>
      <Muted>Нажмите на сотрудника, чтобы поменять роли или права администратора.</Muted>
      <List label="Участники">
        {activeMembers.map((member) => {
          const isSelf = member.user.id === selfUserId;
          return (
            <Row
              key={member.memberId}
              before={<Initials name={member.user.fullName} />}
              title={isSelf ? `${member.user.fullName} (вы)` : member.user.fullName}
              // Права администратора — в строке ролей, как в «Компании»: метка справа сжимала имя в две строки.
              subtitle={member.isAdmin ? [roleNames(member.roles), 'администратор'].filter(Boolean).join(', ') : roleNames(member.roles)}
              // Свои роли администратор тоже меняет; свои права и исключение — нет (лист ниже).
              onClick={() => openEdit(member)}
            />
          );
        })}
      </List>

      {excludedMembers.length > 0 && (
        <Section title={`Исключённые (${excludedMembers.length})`} hint="Вернуться можно по коду компании — в заявке прежние роли будут уже отмечены — или по личной ссылке.">
          {showExcluded ? (
            <List label="Исключённые">
              {excludedMembers.map((member) => (
                <Row key={member.memberId} before={<Initials name={member.user.fullName} />} title={member.user.fullName} subtitle={roleNames(member.roles)} />
              ))}
            </List>
          ) : (
            <Button compact onClick={() => setShowExcluded(true)}>
              Показать
            </Button>
          )}
        </Section>
      )}

      <BottomSheet
        open={editing !== null}
        onClose={closeEdit}
        title={editing?.user.fullName ?? ''}
        dismissible={!saving && !disabling}
      >
        <div style={stackStyle}>
          <RolePicker roles={roles} selectedRoleIds={editRoleIds} onChange={setEditRoleIds} disabled={saving || disabling} />
          {editing && removedRoleIds(editing.roles.map((role) => role.id), editRoleIds).length > 0 && (
            <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
              Документы, которые ждут решения сотрудника в снятой роли, вернутся авторам — их можно отправить заново.
            </span>
          )}
          {/* У себя — только роли: снять с себя права или исключить себя нельзя (выхода из компании нет, решение 27.09). */}
          {!editingSelf && (
            <label style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <Switch checked={editIsAdmin} disabled={saving || disabling} onChange={(event) => setEditIsAdmin(event.target.checked)} />
              <Typography.Text>Права администратора</Typography.Text>
            </label>
          )}
          {editError && <Notice tone="danger">{editError}</Notice>}
          <Button stretched variant="primary" loading={saving} disabled={saving || disabling} onClick={() => void save()}>
            Сохранить
          </Button>
          {!editingSelf && (
            <Button stretched variant="danger" disabled={saving || disabling} onClick={() => setConfirmDisable(true)}>
              Исключить из компании
            </Button>
          )}
        </div>
      </BottomSheet>

      <ConfirmSheet
        open={confirmDisable}
        onClose={() => setConfirmDisable(false)}
        title={`Исключить «${editing?.user.fullName ?? ''}»?`}
        description={
          editing?.isAdmin
            ? 'Это администратор: вместе с доступом к документам пропадут и права администратора. Остальные администраторы и сам сотрудник получат сообщение в MAX. Документы, которые ждут решения сотрудника, вернутся авторам.'
            : 'Доступ к документам компании закроется сразу. Документы, которые ждут решения сотрудника, вернутся авторам — их можно отправить заново.'
        }
        confirmLabel="Исключить"
        destructive
        busy={disabling}
        onConfirm={() => void disable()}
      />

      <ConfirmSheet
        open={confirmRevoke}
        onClose={() => setConfirmRevoke(false)}
        title={`Снять права администратора с «${editing?.user.fullName ?? ''}»?`}
        description="Управлять компанией этот сотрудник больше не сможет. Остальные администраторы и сам сотрудник получат сообщение в MAX."
        confirmLabel="Снять права"
        destructive
        busy={saving}
        onConfirm={() => void save(true)}
      />

      {toast && <Toast message={toast} onDismiss={() => setToast(null)} />}
    </AdminScreen>
  );
}
