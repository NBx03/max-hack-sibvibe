import { useNavigate } from 'react-router-dom';
import { ApiError, errorMessage } from '../../api/errors';
import { AccessErrorState, ErrorState } from '../../ui';
import { ROUTE_PATTERNS } from '../../routes/paths';

// Общее для экранов 3/6/7 (docs/SCREENS.md, "Подключение компании"): ссылка назад в раздел «Компания»,
// где собрано администрирование. Вкладок между экранами нет — путь один: «Компания» → нужный экран.

export const ADMIN_BACK = { to: ROUTE_PATTERNS.company, label: 'Компания' };

/**
 * Ошибка первой загрузки экрана. 403 (не ADMIN) - не временный сбой, «Повторить» здесь
 * никогда не поможет: показываем AccessErrorState со ссылкой на документы. Экраны администрирования открываются из раздела «Компания» или по прямому адресу — только для ADMIN.
 */
export function AdminLoadError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const navigate = useNavigate();
  if (error instanceof ApiError && error.status === 403) {
    return (
      <AccessErrorState
        text="Раздел доступен администраторам компании"
        actionLabel="На главную"
        onAction={() => navigate(ROUTE_PATTERNS.home)}
      />
    );
  }
  return <ErrorState message={errorMessage(error)} onRetry={onRetry} />;
}

/** «когда подана» / срок действия ссылки - коротко и по-русски, без библиотеки дат. */
export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
    .format(date);
}

/** Личные ссылки живут 72 часа (docs/DESIGN-DECISIONS.md) - «осталось» понятнее, чем абсолютная дата истечения. */
export function formatTimeLeft(iso: string): string {
  const diffMs = new Date(iso).getTime() - Date.now();
  if (Number.isNaN(diffMs)) {
    return iso;
  }
  if (diffMs <= 0) {
    return 'истекает';
  }
  const hours = Math.round(diffMs / (60 * 60 * 1000));
  if (hours < 1) {
    return 'осталось меньше часа';
  }
  if (hours < 24) {
    return `осталось ${hours} ч`;
  }
  return `осталось ${Math.round(hours / 24)} дн.`;
}

export function roleNames(roles: { name: string }[]): string {
  return roles.length > 0 ? roles.map((role) => role.name).join(', ') : 'Без ролей';
}
