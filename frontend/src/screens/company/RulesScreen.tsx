import { Input, Switch, Textarea, Typography } from '@maxhub/max-ui';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { errorMessage } from '../../api/errors';
import { getCompanyRules, resetCompanyRule, updateCompanyRule } from '../../api/organization';
import type { CompanyRule, CompanyRules, RuleSettingInput, Severity } from '../../api/types';
import { BottomSheet, Button, Chip, Chips, ErrorState, Icon, List, Row, Section, Toast, SelectField } from '../../ui';
import { ROUTE_PATTERNS } from '../../routes/paths';
import { useSession } from '../../app/SessionContext';
import { zoneLabel } from '../../app/companyTime';
import { DocumentScreen, LoadingState, Notice, fieldStyle, labelStyle, stackStyle } from '../documents/DocumentFormParts';
import { KIND_LABELS, SEVERITY_HINTS, SEVERITY_LABELS, ruleBasis, ruleTitle } from './ruleText';

const SEVERITIES: Severity[] = ['BLOCKER', 'WARNING', 'INFO'];
const BACK = { to: ROUTE_PATTERNS.company, label: 'Компания' };

const chip = (background: string, color: string) => ({
  fontSize: 12,
  padding: '2px 8px',
  borderRadius: 999,
  background,
  color,
  whiteSpace: 'nowrap' as const,
});

function severityChip(severity: Severity) {
  if (severity === 'BLOCKER') return chip('var(--status-danger-bg)', 'var(--status-danger-fg)');
  if (severity === 'WARNING') return chip('var(--status-warning-bg)', 'var(--status-warning-fg)');
  return chip('var(--status-neutral-bg)', 'var(--status-neutral-fg)');
}

/**
 * «Компания» → «Правила проверки». Кто сюда приходит и зачем:
 * - автор или согласующий — понять, откуда замечание и на чём оно основано: всё видно без нажатий;
 * - администратор — подстроить типовые правила под документы своей компании: выключить лишнее, сменить
 *   важность, текст и основание, а у «Не короче» и «Одно из значений» — само значение;
 * - кто угодно — попробовать ослабить закон: у закона замок и объяснение, а не форма.
 * Новые правила и поля здесь не создаются — это данные шаблона сервиса. Изменения действуют для новых проверок:
 * документы на согласовании не пересчитываются, черновик автор перепроверяет кнопкой «Проверить заново».
 */
export function RulesScreen() {
  const [params, setParams] = useSearchParams();
  const [data, setData] = useState<CompanyRules | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [typeId, setTypeId] = useState<number | null>(null);
  const [editing, setEditing] = useState<CompanyRule | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      setData(await getCompanyRules());
    } catch (error) {
      setLoadError(errorMessage(error));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Ссылка «Настроить правило» с экрана проверки: сразу нужный вид документа и, у администратора, само правило.
  const linkedRuleId = Number(params.get('rule')) || null;
  useEffect(() => {
    if (!data) return;
    const linkedType = linkedRuleId === null
      ? null
      : data.types.find((type) => type.rules.some((rule) => rule.id === linkedRuleId));
    setTypeId((current) => linkedType?.documentTypeId ?? current ?? data.types[0]?.documentTypeId ?? null);
    if (linkedType) {
      setEditing(linkedType.rules.find((rule) => rule.id === linkedRuleId) ?? null);
    }
    // Только при загрузке и по ссылке: дальше вид выбирает человек.
  }, [data === null, linkedRuleId]);

  const selected = useMemo(() => data?.types.find((type) => type.documentTypeId === typeId) ?? null, [data, typeId]);

  function closeEditor() {
    setEditing(null);
    if (params.has('rule')) {
      params.delete('rule');
      setParams(params, { replace: true });
    }
  }

  if (loading && !data) return <LoadingState />;
  if (loadError || !data) {
    return (
      <DocumentScreen title="Правила проверки" back={BACK}>
        <ErrorState message={loadError ?? 'Не удалось загрузить правила'} onRetry={() => void load()} />
      </DocumentScreen>
    );
  }

  // По выбранному виду: общее число по всем видам не совпадало с тем, что видно на экране.
  const changedCount = selected?.rules.filter((rule) => rule.customized).length ?? 0;

  return (
    <DocumentScreen title="Правила проверки" back={BACK}>
      <Typography.Text style={{ color: 'var(--text-secondary)', marginTop: -8 }}>
        {data.canEdit
          ? 'Типовые правила сервиса. Нажмите на правило, чтобы подстроить его под документы компании.'
          : 'По этим правилам проверяются документы компании. Менять их может администратор.'}
      </Typography.Text>
      {changedCount > 0 && (
        <span style={{ fontSize: 14, color: 'var(--text-secondary)' }}>Изменено компанией в этом виде: {changedCount}</span>
      )}

      <SelectField
        label="Вид документа"
        placeholder="Выберите вид"
        options={data.types.map((type) => ({ value: type.documentTypeId, label: type.name }))}
        value={typeId}
        onChange={setTypeId}
      />

      {selected && <RuleGroups rules={selected.rules} onOpen={setEditing} />}

      <span style={{ fontSize: 13, color: 'var(--text-tertiary)' }}>
        Новые правила и поля добавляются в шаблон сервиса, а не здесь.
      </span>

      {!data.canEdit && <RuleDetails rule={editing} onClose={closeEditor} />}
      <RuleEditor
        rule={data.canEdit ? editing : null}
        onClose={closeEditor}
        onSaved={(next, message) => {
          setData(next);
          closeEditor();
          setToast(message);
        }}
      />
      {toast && <Toast message={toast} onDismiss={() => setToast(null)} />}
    </DocumentScreen>
  );
}

/** Группы по важности — в порядке, в каком они мешают автору; выключенные — отдельно, в конце. */
const GROUPS: { key: Severity | 'OFF'; title: string; hint: string }[] = [
  { key: 'BLOCKER', title: 'Критичные', hint: SEVERITY_HINTS.BLOCKER },
  { key: 'WARNING', title: 'Предупреждения', hint: SEVERITY_HINTS.WARNING },
  { key: 'INFO', title: 'Советы', hint: SEVERITY_HINTS.INFO },
  { key: 'OFF', title: 'Не проверяются', hint: 'Выключены в компании' },
];

/**
 * Правила — короткими строками по группам важности (карточки с полным текстом и основанием растягивали экран,
 * и до нижних правил приходилось долго листать). Важность видна по группе, текст и основание — по нажатию.
 */
function RuleGroups({ rules, onOpen }: { rules: CompanyRule[]; onOpen: (rule: CompanyRule) => void }) {
  return (
    <>
      {GROUPS.map((group) => {
        const inGroup = rules.filter((rule) => (group.key === 'OFF' ? !rule.enabled : rule.enabled && rule.severity === group.key));
        if (inGroup.length === 0) return null;
        return (
          <Section key={group.key} title={`${group.title} (${inGroup.length})`} hint={group.hint}>
            <List label={group.title}>
              {inGroup.map((rule) => (
                <Row
                  key={rule.id}
                  title={ruleTitle(rule)}
                  subtitle={ruleMarks(rule)}
                  after={rule.locked ? <Icon name="lock" size={16} label="закон" /> : undefined}
                  onClick={() => onOpen(rule)}
                />
              ))}
            </List>
          </Section>
        );
      })}
    </>
  );
}

/** Вид правила и отметка «изменено» — одной короткой строкой под названием. */
function ruleMarks(rule: CompanyRule): string {
  return [KIND_LABELS[rule.kind], rule.customized ? 'изменено компанией' : null].filter(Boolean).join(', ');
}

/**
 * У правила «не позже сегодняшнего дня» — чьё это «сегодня»: по городу компании. Администратор видит, где его
 * поменять: «Компания» → «Часовой пояс».
 */
function TodayZone({ rule }: { rule: CompanyRule }) {
  const { me } = useSession();
  const company = me?.actingAs ? me.sandbox : me?.membership;
  if (rule.check !== 'DATE_NOT_FUTURE' || !company) return null;
  return (
    <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
      «Сегодня» — по времени компании: {zoneLabel(company.timeZone)}
    </span>
  );
}

/** Для сотрудника без прав администратора: что проверяется и на каком основании — без формы. */
function RuleDetails({ rule, onClose }: { rule: CompanyRule | null; onClose: () => void }) {
  return (
    <BottomSheet open={rule !== null} onClose={onClose} title={rule ? ruleTitle(rule) : ''}>
      {rule && (
        <div style={{ ...stackStyle, gap: 10 }}>
          <span style={{ ...severityChip(rule.enabled ? rule.severity : 'INFO'), alignSelf: 'flex-start', whiteSpace: 'normal' }}>
            {rule.enabled ? `${SEVERITY_LABELS[rule.severity]} — ${SEVERITY_HINTS[rule.severity].toLowerCase()}` : 'Не проверяется'}
          </span>
          <span style={{ fontSize: 15 }}>{rule.description}</span>
          <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{ruleBasis(rule)}</span>
          <TodayZone rule={rule} />
          {rule.locked && <Notice>🔒 Требование закона одинаково для всех компаний.</Notice>}
          <Button stretched variant="secondary" onClick={onClose}>Понятно</Button>
        </div>
      )}
    </BottomSheet>
  );
}

type Draft = {
  enabled: boolean;
  severity: Severity;
  description: string;
  sourceTitle: string;
  sourceRef: string;
  minLength: string;
  allowedValues: string;
};

function draftOf(rule: CompanyRule): Draft {
  // У рекомендации сервиса своего документа-основания нет — поле пустое, пока компания не укажет свой.
  const ownOrTemplateSource = rule.kind === 'PRODUCT_RULE' ? '' : rule.sourceTitle;
  return {
    enabled: rule.enabled,
    severity: rule.severity,
    description: rule.description,
    sourceTitle: ownOrTemplateSource,
    sourceRef: rule.kind === 'PRODUCT_RULE' ? '' : rule.sourceRef ?? '',
    minLength: rule.minLength === null ? '' : String(rule.minLength),
    allowedValues: (rule.allowedValues ?? []).join('\n'),
  };
}

function RuleEditor({
  rule,
  onClose,
  onSaved,
}: {
  rule: CompanyRule | null;
  onClose: () => void;
  onSaved: (rules: CompanyRules, message: string) => void;
}) {
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState<'save' | 'reset' | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setDraft(rule ? draftOf(rule) : null);
    setError(null);
  }, [rule]);

  if (!rule || !draft) {
    return <BottomSheet open={false} onClose={onClose} title="">{null}</BottomSheet>;
  }

  const update = (patch: Partial<Draft>) => setDraft((current) => (current ? { ...current, ...patch } : current));

  async function save() {
    if (!rule || !draft) return;
    const input: RuleSettingInput = {
      enabled: draft.enabled,
      severity: draft.severity,
      description: draft.description,
      sourceTitle: draft.sourceTitle.trim() || null,
      sourceRef: draft.sourceRef.trim() || null,
    };
    if (rule.check === 'MIN_LENGTH') {
      const value = Number(draft.minLength);
      if (!Number.isInteger(value) || value < 1) {
        setError('Минимальная длина — целое число больше нуля');
        return;
      }
      input.minLength = value;
    }
    if (rule.check === 'ONE_OF') {
      input.allowedValues = draft.allowedValues.split('\n').map((value) => value.trim()).filter(Boolean);
    }
    setBusy('save');
    setError(null);
    try {
      onSaved(await updateCompanyRule(rule.id, input), 'Правило сохранено. Действует для новых проверок');
    } catch (saveError) {
      setError(errorMessage(saveError));
    } finally {
      setBusy(null);
    }
  }

  async function reset() {
    if (!rule) return;
    setBusy('reset');
    setError(null);
    try {
      onSaved(await resetCompanyRule(rule.id), 'Правило снова как в типовом шаблоне');
    } catch (resetError) {
      setError(errorMessage(resetError));
    } finally {
      setBusy(null);
    }
  }

  // Подписи и сноски — как в форме полей документа (labelStyle, fieldStyle, ds-section__hint), без своих размеров
  // числами; важность — чипами с одной подсказкой к выбранной, а не тремя радиокнопками с тире.
  return (
    <BottomSheet open onClose={onClose} title={ruleTitle(rule)} dismissible={busy === null}>
      {rule.locked ? (
        <div style={stackStyle}>
          <Notice icon="lock">Требование закона одинаково для всех компаний — выключить или ослабить его нельзя.</Notice>
          <Typography.Text>{rule.description}</Typography.Text>
          <p className="ds-section__hint" style={{ margin: 0 }}>{ruleBasis(rule)}</p>
          <Button stretched variant="secondary" onClick={onClose}>Понятно</Button>
        </div>
      ) : (
        <div style={stackStyle}>
          <TodayZone rule={rule} />
          <label style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <Switch checked={draft.enabled} disabled={busy !== null} onChange={(event) => update({ enabled: event.target.checked })} />
            <Typography.Text>Проверять это правило</Typography.Text>
          </label>

          {draft.enabled && (
            <>
              <div style={fieldStyle}>
                <span style={labelStyle}>Важность</span>
                <Chips label="Важность" wrap>
                  {SEVERITIES.map((severity) => (
                    <Chip key={severity} selected={draft.severity === severity} onClick={() => update({ severity })}>
                      {SEVERITY_LABELS[severity]}
                    </Chip>
                  ))}
                </Chips>
                <p className="ds-section__hint" style={{ margin: 0 }}>{SEVERITY_HINTS[draft.severity]}</p>
              </div>

              {rule.check === 'MIN_LENGTH' && (
                <label style={fieldStyle}>
                  <span style={labelStyle}>Не короче, символов</span>
                  <Input size="large" inputMode="numeric" value={draft.minLength} onChange={(event) => update({ minLength: event.target.value })} />
                </label>
              )}
              {rule.check === 'ONE_OF' && (
                <label style={fieldStyle}>
                  <span style={labelStyle}>Допустимые значения — по одному в строке</span>
                  <Textarea value={draft.allowedValues} onChange={(event) => update({ allowedValues: event.target.value })} />
                </label>
              )}

              <label style={fieldStyle}>
                <span style={labelStyle}>Текст замечания для автора</span>
                <Textarea value={draft.description} onChange={(event) => update({ description: event.target.value })} />
              </label>

              <label style={fieldStyle}>
                <span style={labelStyle}>Основание — документ компании</span>
                <Input
                  size="large"
                  value={draft.sourceTitle}
                  placeholder="Например, Инструкция по делопроизводству"
                  onChange={(event) => update({ sourceTitle: event.target.value })}
                />
                <Input
                  size="large"
                  value={draft.sourceRef}
                  placeholder="Пункт, например п. 2.5"
                  onChange={(event) => update({ sourceRef: event.target.value })}
                />
                {rule.kind === 'PRODUCT_RULE' && (
                  <p className="ds-section__hint" style={{ margin: 0 }}>
                    Сейчас это рекомендация сервиса. Укажете свой документ — станет правилом компании.
                  </p>
                )}
              </label>
            </>
          )}

          <p className="ds-section__hint" style={{ margin: 0 }}>
            Действует для новых проверок. Черновики — после «Проверить заново», документы на согласовании не меняются.
          </p>
          {error && <Notice tone="danger">{error}</Notice>}
          <Button stretched variant="primary" loading={busy === 'save'} disabled={busy !== null} onClick={() => void save()}>
            Сохранить
          </Button>
          {rule.customized && (
            <Button stretched variant="secondary" loading={busy === 'reset'} disabled={busy !== null} onClick={() => void reset()}>
              Сбросить к типовому
            </Button>
          )}
          {rule.customized && (
            <p className="ds-section__hint" style={{ margin: 0 }}>
              В шаблоне: {SEVERITY_LABELS[rule.template.severity]}, {KIND_LABELS[rule.template.kind]}
            </p>
          )}
        </div>
      )}
    </BottomSheet>
  );
}
