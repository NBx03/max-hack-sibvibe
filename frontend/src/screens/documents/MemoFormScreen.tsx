import { Input, Radio, Typography } from '@maxhub/max-ui';
import { companyZoneOf, todayIn } from '../../app/companyTime';
import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { createFormDocument, getDocumentTypes } from '../../api/documents';
import { errorMessage } from '../../api/errors';
import type { DocumentType, Visibility } from '../../api/types';
import { ErrorState } from '../../ui';
import { useSession } from '../../app/SessionContext';
import { ROUTE_PATTERNS, documentCheckPath } from '../../routes/paths';
import {
  DocumentScreen,
  FieldInputs,
  LoadingState,
  Notice,
  SubmitButton,
  fieldStyle,
  labelStyle,
  sectionStyle,
  stackStyle,
} from './DocumentFormParts';

const FORM_ID = 'memo-form';
const MEMO_BACK = { to: ROUTE_PATTERNS.documentUpload, label: 'Новый документ' };
const MEMO_TYPE_CODE = 'OFFICIAL_MEMO';

/**
 * Сегодня компании в виде ДД.ММ.ГГГГ: дату записки чаще всего ставят сегодняшнюю. По поясу компании, а не телефона —
 * тот же день, что у правила «не позже сегодняшнего дня» на сервере.
 */
function today(zone: string): string {
  const { year, month, day } = todayIn(zone);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(day)}.${pad(month)}.${year}`;
}

/**
 * 9а. Служебная записка в приложении: без файла, поля — сразу данные документа. Распознавать нечего:
 * правила проверяют поля так же, как извлечённые из файла, а исправление — правка этой же формы.
 */
export function MemoFormScreen() {
  const navigate = useNavigate();
  const { me } = useSession();
  // «От кого» — чаще всего сам автор; в демонстрации — участник, от имени которого действуют.
  const authorName = me?.actingAs ? me.actingAs.user.fullName : me?.user.fullName ?? '';
  const [memoType, setMemoType] = useState<DocumentType | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [visibility, setVisibility] = useState<Visibility>('PRIVATE');
  const [content, setContent] = useState<Record<string, string>>(() => ({ author_name: authorName, doc_date: today(companyZoneOf(me)) }));
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  async function loadType() {
    setLoading(true);
    setLoadError(null);
    try {
      const types = await getDocumentTypes();
      setMemoType(types.find((type) => type.code === MEMO_TYPE_CODE) ?? null);
    } catch (error) {
      setLoadError(errorMessage(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadType();
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!memoType) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const card = await createFormDocument({
        documentTypeId: memoType.id,
        title,
        visibility,
        containsSensitive: false,
        content,
      });
      navigate(documentCheckPath(card.id), { state: { card } });
    } catch (error) {
      setSubmitError(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) return <LoadingState />;
  if (loadError) {
    return (
      <DocumentScreen title="Служебная записка" back={MEMO_BACK}>
        <ErrorState message={loadError} onRetry={() => void loadType()} />
      </DocumentScreen>
    );
  }
  if (!memoType) {
    return (
      <DocumentScreen title="Служебная записка" back={MEMO_BACK}>
        <Notice>Служебные записки в компании не настроены. Загрузите документ файлом.</Notice>
      </DocumentScreen>
    );
  }

  return (
    <DocumentScreen
      title="Служебная записка"
      back={MEMO_BACK}
      footer={
        <SubmitButton form={FORM_ID} busy={submitting}>
          Проверить
        </SubmitButton>
      }
    >
      <Typography.Text style={{ color: 'var(--text-secondary)' }}>Файл не нужен: правила проверят записку сразу.</Typography.Text>
      <form id={FORM_ID} style={stackStyle} onSubmit={(event) => void submit(event)}>
        <section style={sectionStyle}>
          <FieldInputs
            typeCode={memoType.code}
            definitions={memoType.fields}
            values={content}
            onChange={(name, value) => setContent((current) => ({ ...current, [name]: value }))}
          />
        </section>

        <label style={fieldStyle}>
          <span style={labelStyle}>Название</span>
          <Input
            size="large"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            maxLength={255}
            placeholder="Необязательно — возьмём заголовок записки"
          />
        </label>

        {/* Не fieldset: его рамка и legend у браузера рисуются поверх нашей карточки. */}
        <div role="radiogroup" aria-label="Видимость" style={{ ...sectionStyle, gap: 12 }}>
          <strong>Кто видит документ</strong>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Radio name="visibility" checked={visibility === 'PRIVATE'} onChange={() => setVisibility('PRIVATE')} />
            Только участники согласования
          </label>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Radio name="visibility" checked={visibility === 'ORG'} onChange={() => setVisibility('ORG')} />
            Вся компания
          </label>
        </div>

        {submitError && <Notice tone="danger">{submitError}</Notice>}
      </form>
    </DocumentScreen>
  );
}
