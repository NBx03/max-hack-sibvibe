import { useEffect, useRef, type CSSProperties, type ReactNode } from 'react';
import { useGoBack } from '../routes/backNavigation';
import { Icon, type IconName } from './icons';

/**
 * Каркас экрана: высота приложения равна видимой области MAX (app/viewport.ts), страница целиком
 * не прокручивается никогда. Прокручивается только область с шапкой и контентом, а действия (footer) закреплены
 * снизу с учётом safe-area и видны всегда. data-scroll-root — для scrollScreenToTop.
 */
export function Page({
  header,
  footer,
  children,
  center,
}: {
  header?: ReactNode;
  /** Закреплённые действия внизу: одна главная кнопка на всю ширину, остальные — вторичные. */
  footer?: ReactNode;
  children?: ReactNode;
  /** Контент по центру по вертикали — загрузка, ошибка, короткие экраны подключения. */
  center?: boolean;
}) {
  // Высота закреплённых действий — для тоста: он встаёт над ними, а не на магическом отступе.
  const footerRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const root = document.documentElement;
    const element = footerRef.current;
    if (!element) {
      root.style.setProperty('--ds-footer-height', '0px');
      return undefined;
    }
    const apply = () => root.style.setProperty('--ds-footer-height', `${Math.round(element.getBoundingClientRect().height)}px`);
    apply();
    if (typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(apply);
    observer.observe(element);
    return () => observer.disconnect();
  }, [footer]);
  return (
    <div className="ds-page">
      <div className="ds-page__scroll" data-scroll-root>
        <div className="ds-page__content">
          {header}
          {center ? <div className="ds-page__center">{children}</div> : children}
        </div>
      </div>
      {footer && <div ref={footerRef} className="ds-page__footer">{footer}</div>}
    </div>
  );
}

/** Прокрутить текущий экран к началу (вместо window.scrollTo — страница сама не прокручивается). */
export function scrollScreenToTop(): void {
  document.querySelector('[data-scroll-root]')?.scrollTo({ top: 0 });
}

/**
 * Шапка экрана: «‹ Куда» (логический родитель, routes/backNavigation.ts — та же цель, что у системной кнопки
 * MAX), заголовок, подзаголовок и то, что справа от заголовка (метка статуса, меню).
 */
/** С какой длины заголовок уменьшается: 40 символов — около трёх строк крупного шрифта на 360 px. */
const LONG_TITLE = 40;

export function PageHeader({
  title,
  subtitle,
  back,
  aside,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  back?: { to: string; label: string };
  aside?: ReactNode;
}) {
  return (
    <header className="ds-header">
      {back && <BackLink to={back.to} label={back.label} />}
      <div className="ds-header__row">
        {/* Длинное название — на ступень меньше, но целиком (название компании занимало полэкрана). */}
        <h1 className={`ds-header__title ${typeof title === 'string' && title.length > LONG_TITLE ? 'ds-header__title--long' : ''}`}>{title}</h1>
        {aside}
      </div>
      {subtitle && <p className="ds-header__subtitle">{subtitle}</p>}
    </header>
  );
}

export function BackLink({ to, label }: { to: string; label: string }) {
  const goBack = useGoBack();
  return (
    <button type="button" className="ds-back" onClick={() => goBack(to)}>
      <Icon name="back" size={20} />
      {label}
    </button>
  );
}

/** Группа на экране: заголовок, необязательная подсказка и содержимое. */
export function Section({ title, hint, children, aside }: { title?: ReactNode; hint?: ReactNode; aside?: ReactNode; children: ReactNode }) {
  return (
    <section className="ds-section">
      {(title || aside) && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {title && <h2 className="ds-section__title" style={{ flex: 1 }}>{title}</h2>}
          {aside}
        </div>
      )}
      {hint && <p className="ds-section__hint">{hint}</p>}
      {children}
    </section>
  );
}

export function Card({ children, style }: { children: ReactNode; style?: CSSProperties }) {
  return (
    <div className="ds-card" style={style}>
      {children}
    </div>
  );
}

/** Список строк на одной подложке. */
export function List({ children, label }: { children: ReactNode; label?: string }) {
  return (
    <div className="ds-list" role="list" aria-label={label}>
      {children}
    </div>
  );
}

/**
 * Строка списка. С onClick — кнопка со стрелкой «›» (принцип 4: кликабельное видно сразу), без — просто текст.
 */
export function Row({
  title,
  subtitle,
  before,
  after,
  onClick,
  chevron = true,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  before?: ReactNode;
  after?: ReactNode;
  onClick?: () => void;
  chevron?: boolean;
}) {
  const body = (
    <>
      {before}
      <span className="ds-row__body">
        <span className="ds-row__title">{title}</span>
        {subtitle && <span className="ds-row__subtitle">{subtitle}</span>}
      </span>
      {after && <span className="ds-row__after">{after}</span>}
      {onClick && chevron && (
        <span className="ds-row__chevron">
          <Icon name="chevron" size={20} />
        </span>
      )}
    </>
  );
  return onClick ? (
    <button type="button" className="ds-row" role="listitem" onClick={onClick}>
      {body}
    </button>
  ) : (
    <div className="ds-row" role="listitem">
      {body}
    </div>
  );
}

/** Сетка плиток 2×2 главного экрана. */
export function Tiles({ children }: { children: ReactNode }) {
  return <div className="ds-tiles">{children}</div>;
}

/**
 * Плитка раздела: иконка, название и пояснение в одну-две строки — «что здесь за документы».
 * attention — есть что сделать (например, документы ждут решения): акцентная рамка и счётчик.
 */
export function Tile({
  icon,
  title,
  hint,
  count,
  countHint,
  attention,
  onClick,
}: {
  icon: IconName;
  title: string;
  hint: string;
  count?: number;
  /** Что считает счётчик — подписью вместо hint, когда он есть: «3» без слов непонятно. */
  countHint?: string;
  attention?: boolean;
  onClick: () => void;
}) {
  const hasCount = count !== undefined && count > 0;
  return (
    <button
      type="button"
      className={`ds-tile ${attention && hasCount ? 'ds-tile--attention' : ''}`}
      onClick={onClick}
      aria-label={hasCount ? `${title}: ${countHint ?? count}` : title}
    >
      <span className="ds-tile__icon">
        <Icon name={icon} size={28} />
      </span>
      <span className="ds-tile__title">{title}</span>
      <span className="ds-tile__hint">{hasCount && countHint ? countHint : hint}</span>
      {hasCount && (
        <span className="ds-tile__count">
          <Count value={count} accent={attention} />
        </span>
      )}
    </button>
  );
}

export function Count({ value, accent }: { value: number; accent?: boolean }) {
  return <span className={`ds-count ${accent ? 'ds-count--accent' : ''}`}>{value > 99 ? '99+' : value}</span>;
}

/** Некликабельный второстепенный текст (принцип 4: никогда не синий). */
export function Muted({ children }: { children: ReactNode }) {
  return <p className="ds-text-secondary">{children}</p>;
}

/** Инициалы человека в кружке — чтобы список людей читался по лицам, а не сплошным текстом. */
export function Initials({ name }: { name: string }) {
  const letters = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('');
  return (
    <span className="ds-initials" aria-hidden>
      {letters || '?'}
    </span>
  );
}
