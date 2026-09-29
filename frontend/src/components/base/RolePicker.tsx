import type { RoleRef } from '../../api/types';
import { Icon } from '../../ui';

// Выбор ролей — один компонент для заявок, участников и личных ссылок. Ролей в компании немного (пять стандартных),
// поэтому все видны сразу чипами-переключателями: нажал — выбрана, нажал ещё раз — снята. Раньше роли прятались
// в выпадающем списке за «+ Добавить роль» — лишний шаг и выпадашка, которой в дизайн-системе нет.
// «Права администратора» сюда не входят: это не роль, а отдельный переключатель на экране.

export function RolePicker({
  roles,
  selectedRoleIds,
  onChange,
  label = 'Роли',
  disabled,
}: {
  /** Все роли компании, из которых можно выбирать. */
  roles: RoleRef[];
  selectedRoleIds: number[];
  onChange: (roleIds: number[]) => void;
  label?: string;
  disabled?: boolean;
}) {
  function toggle(roleId: number) {
    onChange(selectedRoleIds.includes(roleId) ? selectedRoleIds.filter((id) => id !== roleId) : [...selectedRoleIds, roleId]);
  }

  return (
    <div className="ds-field" role="group" aria-label={label}>
      <span className="ds-field__label">{label}</span>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--ds-space-xs)' }}>
        {roles.map((role) => {
          const selected = selectedRoleIds.includes(role.id);
          return (
            <button
              key={role.id}
              type="button"
              className="ds-chip"
              aria-pressed={selected}
              disabled={disabled}
              onClick={() => toggle(role.id)}
            >
              {selected && <Icon name="check" size={16} />}
              {role.name}
            </button>
          );
        })}
      </div>
    </div>
  );
}
