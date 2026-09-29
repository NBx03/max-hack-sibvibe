import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Icon, type IconName } from './icons';

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost';

/**
 * Кнопка дизайн-системы (принципы 4–6). Своя, а не Button из max-ui: у той фиксированная высота и подпись
 * обрезается многоточием, а у нас подпись переносится на вторую строку. Высота — от 48 px (compact — 44).
 * primary — одно главное действие экрана, secondary — остальные, danger — «тихая» опасная: красный текст
 * на вторичном фоне, без яркой заливки.
 */
export function Button({
  variant = 'secondary',
  stretched,
  compact,
  loading,
  icon,
  children,
  className,
  disabled,
  type = 'button',
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  stretched?: boolean;
  compact?: boolean;
  loading?: boolean;
  icon?: IconName;
  children: ReactNode;
}) {
  const classes = [
    'ds-button',
    `ds-button--${variant}`,
    stretched ? 'ds-button--stretched' : '',
    compact ? 'ds-button--compact' : '',
    className ?? '',
  ].join(' ');
  return (
    <button type={type} className={classes} disabled={disabled || loading} aria-busy={loading || undefined} {...rest}>
      {loading ? <span className="ds-button__spinner" aria-hidden /> : icon && <Icon name={icon} size={20} />}
      <span>{children}</span>
    </button>
  );
}

/** Две кнопки поровну в строку с зазором 8 px — «Вернуть» и «Отклонить», «Отмена» и «Подтвердить». */
export function ButtonRow({ children }: { children: ReactNode }) {
  return <div className="ds-button-row">{children}</div>;
}

/** Кнопка-иконка: видимая иконка ~20–24 px, зона нажатия 44×44. Подпись обязательна — для экранного диктора. */
export function IconButton({
  icon,
  label,
  onClick,
  size = 22,
  className,
}: {
  icon: IconName;
  label: string;
  onClick: () => void;
  size?: number;
  className?: string;
}) {
  return (
    <button type="button" className={`ds-icon-button ${className ?? ''}`} aria-label={label} title={label} onClick={onClick}>
      <Icon name={icon} size={size} />
    </button>
  );
}
