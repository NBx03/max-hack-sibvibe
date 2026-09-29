import { Input, Textarea, Typography } from '@maxhub/max-ui';
import type { CSSProperties, ReactNode } from 'react';
import type { DocumentTypeField, Severity } from '../../api/types';
import { fieldHint } from './fieldHints';
import { Button, Icon, Loading, Page, PageHeader } from '../../ui';

export { Notice } from '../../ui';

export const stackStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 16,
};

/** Карточка-группа — то же, что ds-card дизайн-системы: подложка без рамки и цветных полос. */
export const sectionStyle: CSSProperties = {
  ...stackStyle,
  padding: 'var(--ds-space-m)',
  borderRadius: 'var(--ds-radius-m)',
  background: 'var(--ds-surface)',
};

export const fieldStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

/** Подпись поля формы — одна на все экраны документов. */
export const labelStyle: CSSProperties = { fontSize: 14, fontWeight: 500, color: 'var(--text-secondary)' };

/** Ссылка на источник правила: цвет из темы, иначе браузерный синий нечитаем в тёмной теме. */
export const linkStyle: CSSProperties = { color: 'var(--text-themed)' };

/**
 * Экран на каркасе дизайн-системы (ui/Page): шапка с «‹ Куда» и заголовком, контент прокручивается, главное
 * действие — в закреплённой нижней панели. Нижнего меню разделов больше нет: путь домой — «‹ Главная».
 */
export function DocumentScreen({
  title,
  subtitle,
  children,
  footer,
  back,
}: {
  title: string;
  subtitle?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  back?: { to: string; label: string };
}) {
  return (
    <Page header={<PageHeader title={title} subtitle={subtitle} back={back} />} footer={footer}>
      {children}
    </Page>
  );
}

export function LoadingState({ label = 'Загружаем…' }: { label?: string }) {
  return (
    <Page center>
      <Loading label={label} />
    </Page>
  );
}

/** Самое важное замечание поля — для подсветки при правке: у поля видно, что с ним не так. */
export type FieldIssue = { severity: Severity; message: string };

export function FieldInputs({
  definitions,
  values,
  onChange,
  typeCode,
  issues = {},
}: {
  definitions: DocumentTypeField[];
  values: Record<string, string>;
  onChange: (name: string, value: string) => void;
  /** Код типа — для подсказок-примеров (fieldHints.ts); подсказки справочника — для модели, не для людей. */
  typeCode?: string;
  /** Замечания по полям: рамка цвета важности и текст замечания под полем вместо примера. */
  issues?: Record<string, FieldIssue>;
}) {
  if (definitions.length === 0) {
    return <Typography.Text>Для этого типа нет полей для ручного ввода.</Typography.Text>;
  }
  return (
    <div style={stackStyle}>
      {definitions.map((field) => {
        const issue = issues[field.name];
        const tone = issue ? ISSUE_TONE[issue.severity] : null;
        const hint = issue
          ? <span className={`ds-field-issue ds-field-issue--${tone}`}><Icon name={tone === 'neutral' ? 'info' : 'alert'} size={14} />{issue.message}</span>
          : fieldHint(typeCode, field.name);
        return (
          <label style={fieldStyle} key={field.name}>
            <span style={{ fontSize: 14, fontWeight: 500, color: 'var(--text-secondary)' }}>{field.label}</span>
            {MULTILINE_FIELDS.has(field.name) ? (
              <>
                <Textarea
                  rows={6}
                  className={tone ? `ds-textarea-issue ds-textarea-issue--${tone}` : undefined}
                  value={values[field.name] ?? ''}
                  onChange={(event) => onChange(field.name, event.target.value)}
                  maxLength={10_000}
                />
                {hint && (issue ? hint : <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{hint}</span>)}
              </>
            ) : (
              <Input
                size="large"
                innerClassNames={tone ? { container: `ds-input-issue ds-input-issue--${tone}` } : undefined}
                inputMode={field.type === 'NUMBER' ? 'numeric' : 'text'}
                value={values[field.name] ?? ''}
                onChange={(event) => onChange(field.name, event.target.value)}
                maxLength={10_000}
                hint={hint}
              />
            )}
          </label>
        );
      })}
    </div>
  );
}

/** Цвет замечания по важности — у поля при правке и у подписи поля при чтении. */
export const ISSUE_TONE: Record<Severity, 'danger' | 'warning' | 'neutral'> = { BLOCKER: 'danger', WARNING: 'warning', INFO: 'neutral' };

/** Самое важное замечание по каждому полю: критичное важнее предупреждения, предупреждение — совета. */
export function fieldIssues(issues: { fieldName: string; severity: Severity; message: string }[]): Record<string, FieldIssue> {
  const order: Record<Severity, number> = { BLOCKER: 0, WARNING: 1, INFO: 2 };
  const result: Record<string, FieldIssue> = {};
  for (const issue of issues) {
    const current = result[issue.fieldName];
    if (!current || order[issue.severity] < order[current.severity]) {
      result[issue.fieldName] = { severity: issue.severity, message: issue.message };
    }
  }
  return result;
}

/** Поля с текстом в несколько абзацев: текст служебной записки в одну строку не набрать. */
const MULTILINE_FIELDS: ReadonlySet<string> = new Set(['body']);

/** `form` — id формы: кнопка стоит в нижней панели, вне самой формы. */
export function SubmitButton({
  busy,
  disabled,
  form,
  children,
}: {
  busy: boolean;
  disabled?: boolean;
  form?: string;
  children: ReactNode;
}) {
  return (
    <Button stretched type="submit" form={form} variant="primary" loading={busy} disabled={disabled}>
      {children}
    </Button>
  );
}
