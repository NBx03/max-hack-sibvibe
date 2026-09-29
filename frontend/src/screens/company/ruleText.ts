import type { CompanyRule, RuleKind, Severity } from '../../api/types';

// Подписи правил проверки — одни и те же на экране правил и на экране проверки документа.

export const SEVERITY_LABELS: Record<Severity, string> = {
  BLOCKER: 'Критично',
  WARNING: 'Предупреждение',
  INFO: 'Совет',
};

/** Что важность значит для автора — подсказка при настройке. */
export const SEVERITY_HINTS: Record<Severity, string> = {
  BLOCKER: 'Документ нельзя отправить, пока не исправят',
  WARNING: 'Замечание видно, отправить можно',
  INFO: 'Мягкая подсказка',
};

/**
 * Вид правила — одними словами на экране правил и на экране проверки документа. Словарь,
 * кроме «Правила продукта»: пользователь не знает, что такое «продукт», а это наше допущение без документа-
 * основания — «Рекомендация сервиса». Рекомендация, которой компания указала свой документ, становится
 * «Правилом компании» — так пользователь и видит главное изменение.
 */
export const KIND_LABELS: Record<RuleKind, string> = {
  LEGAL: 'Закон',
  INTERNAL_POLICY: 'Правило компании',
  PRODUCT_RULE: 'Рекомендация сервиса',
};

/** Что проверяет правило — человеческими словами, из вида проверки и подписи поля. */
export function ruleTitle(rule: Pick<CompanyRule, 'check' | 'fieldLabel' | 'minLength' | 'allowedValues'>): string {
  const field = `«${rule.fieldLabel}»`;
  switch (rule.check) {
    case 'REQUIRED':
      return `${field} заполнено`;
    case 'DATE_FORMAT':
      return `${field} — полная дата`;
    case 'DATE_NOT_FUTURE':
      return `${field} — не позже сегодняшнего дня`;
    case 'MATCHES_PATTERN':
      return `${field} — в принятом формате`;
    case 'MIN_LENGTH':
      return `${field} — не короче ${rule.minLength ?? '?'} символов`;
    case 'ONE_OF':
      return `${field} — одно из: ${(rule.allowedValues ?? []).join(', ')}`;
  }
}

/**
 * Основание правила одной строкой; у рекомендации сервиса источника нет — это наше допущение. short — для
 * карточки: у источника шаблона без пояснения в скобках («утв. приказом…», «шаблон сервиса…»), полностью — в
 * настройке. Название, которое ввела компания, не сокращается никогда: «Положение об отпусках (редакция 2026)»
 * — это название, а не пояснение.
 */
export function ruleBasis(
  rule: Pick<CompanyRule, 'kind' | 'sourceTitle' | 'sourceRef'> & { template?: { sourceTitle: string } },
  short = false,
): string {
  if (rule.kind === 'PRODUCT_RULE') return KIND_LABELS.PRODUCT_RULE;
  const fromTemplate = rule.template !== undefined && rule.template.sourceTitle === rule.sourceTitle;
  const title = short && fromTemplate ? shortTitle(rule.sourceTitle) : rule.sourceTitle;
  const ref = rule.sourceRef ? `, ${rule.sourceRef}` : '';
  return `${KIND_LABELS[rule.kind]}: ${title}${ref}`;
}

function shortTitle(title: string): string {
  const cut = title.indexOf(' (');
  return cut > 0 ? title.slice(0, cut) : title;
}
