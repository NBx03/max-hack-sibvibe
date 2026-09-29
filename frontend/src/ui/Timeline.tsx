import { useState, type ReactNode } from 'react';
import { Icon, type IconName } from './icons';
import type { StatusTone } from './status';

// Таймлайн отслеживания — как трекинг заказа: пройденные шаги — заполненная точка и сплошная линия акцентного
// цвета, текущий — крупнее, с подписью «Сейчас здесь», будущие — серая точка, пунктир, приглушённый текст. Итоговый шаг
// берёт цвет своего статуса (утверждён — зелёный, отклонён — красный). Пройденные этапы сворачиваются в одну строку.

export type TimelineState = 'done' | 'current' | 'future';

export interface TimelineItem {
  key: string;
  state: TimelineState;
  title: ReactNode;
  /** Дата, автор, «одновременно» — вторая строка. */
  meta?: ReactNode;
  /** Люди этапа и их решения. */
  children?: ReactNode;
  /** Цвет и значок итогового шага (статус документа); без него — акцентный. */
  tone?: StatusTone;
  icon?: IconName;
  /** Можно свернуть вместе с соседними пройденными («Этапы 1–2 пройдены»). */
  collapsible?: boolean;
}

function Dot({ item }: { item: TimelineItem }) {
  const tone = item.tone ? `var(--status-${item.tone}-fg)` : 'var(--ds-accent)';
  if (item.state === 'future') {
    return <span className="ds-tl__dot ds-tl__dot--future" />;
  }
  if (item.state === 'current') {
    return (
      <span className="ds-tl__dot ds-tl__dot--current" style={{ borderColor: tone, color: tone }}>
        <Icon name={item.icon ?? 'clock'} size={14} />
      </span>
    );
  }
  return (
    <span className="ds-tl__dot ds-tl__dot--done" style={{ background: tone }}>
      <Icon name={item.icon ?? 'check'} size={13} />
    </span>
  );
}

/**
 * Что сворачивается: только пройденные этапы до первого непройденного. Этап после текущего тоже бывает пройден — например,
 * сразу согласованный автором, у которого одного есть обязательная роль, — и раньше он попадал в свёрнутые:
 * «Этапы 1–2 пройдены», хотя этап 2 ещё идёт.
 */
export function collapsibleItems(items: TimelineItem[]): TimelineItem[] {
  const firstOpen = items.findIndex((item) => item.state !== 'done');
  const passed = firstOpen === -1 ? items : items.slice(0, firstOpen);
  return passed.filter((item) => item.collapsible && item.state === 'done');
}

export function Timeline({ items, collapseLabel }: { items: TimelineItem[]; collapseLabel?: (count: number) => string }) {
  const [expanded, setExpanded] = useState(false);
  const collapsible = collapsibleItems(items);
  const collapse = !expanded && collapsible.length >= 2;
  const firstCollapsed = collapse ? items.indexOf(collapsible[0]) : -1;

  const canCollapse = collapsible.length >= 2;
  const firstCollapsible = canCollapse ? items.indexOf(collapsible[0]) : -1;
  const visible: (TimelineItem | 'toggle' | 'collapse')[] = [];
  items.forEach((item, index) => {
    if (collapse && collapsible.includes(item)) {
      if (index === firstCollapsed) visible.push('toggle');
      return;
    }
    // «Свернуть» — там, где были свёрнутые этапы, а не в конце списка после итога.
    if (expanded && index === firstCollapsible) visible.push('collapse');
    visible.push(item);
  });

  return (
    <ol className="ds-tl" aria-label="Ход согласования">
      {visible.map((entry, index) => {
        const next = visible[index + 1];
        const nextState = next === 'toggle' || next === 'collapse' ? 'done' : next?.state;
        if (entry === 'collapse') {
          return (
            <li key="collapse" className="ds-tl__item">
              <span className="ds-tl__rail">
                <span className="ds-tl__line ds-tl__line--done" />
              </span>
              <button type="button" className="ds-tl__toggle" aria-expanded onClick={() => setExpanded(false)}>
                Свернуть пройденные этапы
                <span style={{ display: 'inline-flex', transform: 'rotate(180deg)' }}>
                  <Icon name="chevronDown" size={16} />
                </span>
              </button>
            </li>
          );
        }
        if (entry === 'toggle') {
          return (
            <li key="toggle" className="ds-tl__item">
              <span className="ds-tl__rail">
                <span className="ds-tl__dot ds-tl__dot--done" style={{ background: 'var(--ds-accent)' }}>
                  <Icon name="check" size={13} />
                </span>
                {next && <span className="ds-tl__line ds-tl__line--done" />}
              </span>
              <button type="button" className="ds-tl__toggle" aria-expanded={false} onClick={() => setExpanded(true)}>
                <span className="ds-tl__toggle-count">
                  {collapseLabel ? collapseLabel(collapsible.length) : `Пройдено этапов: ${collapsible.length}`}.
                </span>
                Показать
                <Icon name="chevronDown" size={16} />
              </button>
            </li>
          );
        }
        const lineDone = entry.state === 'done' && nextState !== 'future';
        return (
          <li key={entry.key} className={`ds-tl__item ds-tl__item--${entry.state}`} aria-current={entry.state === 'current' ? 'step' : undefined}>
            <span className="ds-tl__rail">
              <Dot item={entry} />
              {next && <span className={`ds-tl__line ${lineDone ? 'ds-tl__line--done' : 'ds-tl__line--future'}`} />}
            </span>
            <div className="ds-tl__body">
              <div className="ds-tl__head">
                <span className="ds-tl__title">{entry.title}</span>
                {entry.state === 'current' && <span className="ds-tl__here">Сейчас здесь</span>}
              </div>
              {entry.meta && <div className="ds-tl__meta">{entry.meta}</div>}
              {entry.children}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

/** Человек этапа на таймлайне: имя, роль, решение словом и значком, время, комментарий. */
export function TimelinePerson({
  name,
  role,
  status,
  time,
  comment,
}: {
  name: ReactNode;
  role: ReactNode;
  /** Нет у будущих этапов: их очередь ещё не пришла, «ждёт» там вводило бы в заблуждение. */
  status?: ReactNode;
  time?: string | null;
  comment?: string | null;
}) {
  return (
    // Решение — отдельной строкой под именем: справа длинная метка («Возвращено на доработку») сжимала имя на 360 px.
    <div className="ds-tl__person">
      <div className="ds-row__title" style={{ fontSize: 'var(--ds-text-s)' }}>{name}</div>
      <div className="ds-row__subtitle">{[role, time].filter(Boolean).join(', ')}</div>
      {status && <div style={{ marginTop: 6 }}>{status}</div>}
      {comment && <p className="ds-tl__comment">«{comment}»</p>}
    </div>
  );
}
