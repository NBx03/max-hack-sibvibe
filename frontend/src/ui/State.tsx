import { useEffect, useRef, type ReactNode } from 'react';
import { Spinner } from '@maxhub/max-ui';
import { Button } from './Button';
import { Icon, type IconName } from './icons';

// Состояния экрана (принцип 7 и критерий кейса «интерфейс сообщает о загрузке, результате и ошибке»):
// пусто — что здесь появится и что сделать; ошибка — текст бэкенда и «Повторить»; нет доступа — без «Повторить».

function StateBlock({
  icon,
  title,
  text,
  action,
}: {
  icon: IconName;
  title?: string;
  text?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="ds-state">
      <span className="ds-state__icon">
        <Icon name={icon} size={26} />
      </span>
      {title && <p className="ds-state__title">{title}</p>}
      {text && <p className="ds-state__text">{text}</p>}
      {action}
    </div>
  );
}

/** Пусто: заголовок «что здесь будет», пояснение и, если есть, следующее действие. */
export function EmptyState({
  icon = 'inbox',
  title,
  text,
  actionLabel,
  onAction,
}: {
  icon?: IconName;
  title: string;
  text?: ReactNode;
  actionLabel?: string;
  onAction?: () => void;
}) {
  return (
    <StateBlock
      icon={icon}
      title={title}
      text={text}
      action={actionLabel && onAction ? <Button onClick={onAction}>{actionLabel}</Button> : undefined}
    />
  );
}

/** Ошибка загрузки: текст из ApiError.message бэкенда и «Повторить». */
export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <StateBlock
      icon="alert"
      title="Не получилось загрузить"
      text={message}
      action={
        <Button icon="return" onClick={onRetry}>
          Повторить
        </Button>
      }
    />
  );
}

/** Нет доступа или ссылка недействительна — повтор не поможет, поэтому вместо него путь дальше. */
export function AccessErrorState({
  text,
  actionLabel,
  onAction,
}: {
  text: string;
  actionLabel?: string;
  onAction?: () => void;
}) {
  return (
    <StateBlock
      icon="lock"
      text={text}
      action={actionLabel && onAction ? <Button onClick={onAction}>{actionLabel}</Button> : undefined}
    />
  );
}

export function Loading({ label = 'Загружаем…' }: { label?: string }) {
  return (
    <div className="ds-loading" role="status" aria-label={label}>
      <Spinner />
    </div>
  );
}

/** Ошибка действия — сразу под кнопкой или полем, в видимой зоне. */
export function InlineError({ children }: { children: ReactNode }) {
  return (
    <p className="ds-inline-error" role="alert">
      <Icon name="alert" size={20} />
      <span>{children}</span>
    </p>
  );
}

/** Спокойная информационная строка или предупреждение (не ошибка). */
export function Notice({
  tone = 'info',
  icon,
  children,
}: {
  tone?: 'info' | 'warning' | 'danger' | 'success';
  icon?: IconName;
  children: ReactNode;
}) {
  const fallback: IconName = tone === 'success' ? 'check' : tone === 'info' ? 'info' : 'alert';
  return (
    <div className={`ds-notice ds-notice--${tone}`} role={tone === 'danger' ? 'alert' : undefined}>
      <Icon name={icon ?? fallback} size={20} />
      <div style={{ flex: 1, minWidth: 0 }}>{children}</div>
    </div>
  );
}

/** Краткое подтверждение после действия; скрывается само через 2,5 с. */
export function Toast({ message, onDismiss }: { message: string; onDismiss: () => void }) {
  // Таймер перезапускается только для нового сообщения, а не на каждый рендер родителя.
  const onDismissRef = useRef(onDismiss);
  onDismissRef.current = onDismiss;
  useEffect(() => {
    const timer = setTimeout(() => onDismissRef.current(), 2500);
    return () => clearTimeout(timer);
  }, [message]);
  return (
    <div className="ds-toast" role="status">
      <Icon name="check" size={20} />
      {message}
    </div>
  );
}
