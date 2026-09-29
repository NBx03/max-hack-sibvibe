import { useState } from 'react';
import type { Severity, ValidationIssue } from '../../api/types';
import { Icon, StatusBadge, type StatusMeta } from '../../ui';
import { KIND_LABELS, SEVERITY_LABELS } from '../company/ruleText';
import { linkStyle, stackStyle } from './DocumentFormParts';

/** Важность замечания — тон палитры, значок и слово: не только цвет. */
export function severityMeta(severity: Severity): StatusMeta {
  if (severity === 'BLOCKER') return { text: SEVERITY_LABELS[severity], tone: 'danger', icon: 'alert' };
  if (severity === 'WARNING') return { text: SEVERITY_LABELS[severity], tone: 'warning', icon: 'alert' };
  return { text: SEVERITY_LABELS[severity], tone: 'neutral', icon: 'info' };
}

const SEVERITY_ORDER: Record<Severity, number> = { BLOCKER: 0, WARNING: 1, INFO: 2 };

/** Сначала то, что мешает отправке, потом предупреждения и советы. */
export function sortIssues(issues: ValidationIssue[]): ValidationIssue[] {
  return [...issues].sort((left, right) => SEVERITY_ORDER[left.severity] - SEVERITY_ORDER[right.severity]);
}

/** Сколько замечаний видно сразу; критичные — всегда все, их нельзя пропустить. */
const PREVIEW = 3;

/**
 * Список замечаний («замечания могут быть длинными»). В каждом сразу видно главное — важность, поле и что
 * исправить, и цитата из документа; основание (длинное название ГОСТа) — по нажатию. Длинный список свёрнут до трёх,
 * но критичные показываются всегда.
 */
export function IssueList({
  issues,
  labelOf,
  onConfigure,
}: {
  issues: ValidationIssue[];
  labelOf: (fieldName: string) => string;
  /** Администратору — ссылка «Настроить правило» в раскрытом замечании. */
  onConfigure?: (ruleId: number) => void;
}) {
  const [showAll, setShowAll] = useState(false);
  const sorted = sortIssues(issues);
  const blockers = sorted.filter((issue) => issue.severity === 'BLOCKER').length;
  const collapsedCount = Math.max(PREVIEW, blockers);
  // Прятать одно замечание за «Показать ещё 1» бессмысленно — кнопка занимает столько же места.
  const canCollapse = sorted.length > collapsedCount + 1;
  const visibleCount = showAll || !canCollapse ? sorted.length : collapsedCount;
  return (
    <div style={{ ...stackStyle, gap: 12 }}>
      {sorted.slice(0, visibleCount).map((issue) => (
        <IssueCard
          key={`${issue.ruleId}:${issue.fieldName}`}
          issue={issue}
          label={labelOf(issue.fieldName)}
          onConfigure={onConfigure}
        />
      ))}
      {canCollapse && (
        <button type="button" className="ds-link-toggle" aria-expanded={showAll} onClick={() => setShowAll((open) => !open)}>
          {showAll ? 'Свернуть замечания' : `Показать ещё ${sorted.length - collapsedCount}`}
          <span style={{ display: 'inline-flex', transform: showAll ? 'rotate(180deg)' : undefined }}>
            <Icon name="chevronDown" size={16} />
          </span>
        </button>
      )}
    </div>
  );
}

function IssueCard({
  issue,
  label,
  onConfigure,
}: {
  issue: ValidationIssue;
  label: string;
  onConfigure?: (ruleId: number) => void;
}) {
  const [open, setOpen] = useState(false);
  // «Рекомендация сервиса» у всех рекомендаций одинакова — основание показываем у закона и правила компании.
  const hasBasis = issue.kind !== 'PRODUCT_RULE';
  const hasDetails = hasBasis || Boolean(onConfigure);
  return (
    // Без цветных полос слева: важность — меткой со значком и словом.
    <article className="ds-issue">
      {/* Метка и поле — всегда в две строки: в одну строку они то помещались, то нет, и карточки выглядели вразнобой. */}
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 6 }}>
        <StatusBadge meta={severityMeta(issue.severity)} />
        <strong>{label}</strong>
      </div>
      <p style={{ margin: 0 }}>{issue.message}</p>
      {issue.quote && (
        <blockquote className="ds-issue__quote">
          «{issue.quote}»{issue.page !== null ? `, стр. ${issue.page}` : ''}
        </blockquote>
      )}
      {hasDetails && (
        <button type="button" className="ds-link-toggle ds-link-toggle--small" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
          {open ? 'Скрыть основание' : hasBasis ? `Основание: ${KIND_LABELS[issue.kind].toLowerCase()}` : 'Настройка правила'}
          <span style={{ display: 'inline-flex', transform: open ? 'rotate(180deg)' : undefined }}>
            <Icon name="chevronDown" size={14} />
          </span>
        </button>
      )}
      {open && (
        <div style={{ ...stackStyle, gap: 8 }}>
          {hasBasis && (
            <div style={{ color: 'var(--text-secondary)', fontSize: 'var(--ds-text-xs)', lineHeight: '18px' }}>
              <span>{KIND_LABELS[issue.kind]}: </span>
              {issue.sourceUrl ? (
                <a href={issue.sourceUrl} target="_blank" rel="noreferrer" style={linkStyle}>{issue.sourceTitle}</a>
              ) : (
                <span>{issue.sourceTitle}</span>
              )}
              {issue.sourceRef && <span>, {issue.sourceRef}</span>}
            </div>
          )}
          {onConfigure && (
            <button type="button" className="ds-link-toggle ds-link-toggle--small" onClick={() => onConfigure(issue.ruleId)}>
              Настроить правило
            </button>
          )}
        </div>
      )}
    </article>
  );
}
