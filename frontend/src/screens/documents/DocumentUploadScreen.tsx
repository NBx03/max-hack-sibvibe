import { Input, Radio, Switch } from '@maxhub/max-ui';
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { createDocument, getDocumentTypes } from '../../api/documents';
import { errorMessage } from '../../api/errors';
import type { DocumentType, Visibility } from '../../api/types';
import { useSession } from '../../app/SessionContext';
import { ROUTE_PATTERNS, documentCheckPath } from '../../routes/paths';
import {
  AttachmentsField,
  Button,
  ErrorState,
  FileDrop,
  InlineError,
  Loading,
  Notice,
  Page,
  PageHeader,
  Section,
  SelectField,
  Stepper,
  type SelectOption,
} from '../../ui';
import { FieldInputs, fieldStyle, labelStyle, sectionStyle, stackStyle } from './DocumentFormParts';
import { FormatsHelp } from './FormatsHelp';
import {
  ATTACHMENT_ACCEPT,
  ATTACHMENT_CAPTION,
  CREATE_STEPS,
  MAIN_FILE_ACCEPT,
  MAIN_FILE_CAPTION,
  MAX_ATTACHMENTS,
  attachmentErrorOf,
  fileErrorOf,
  fileProblem,
  pickAttachments,
  versionSizeProblem,
} from './uploadFormats';

const FORM_ID = 'document-upload-form';
const UPLOAD_BACK = { to: ROUTE_PATTERNS.home, label: 'Главная' };
const AUTO = 0;

/**
 * Что будет проверено — подпись под выбором вида. Человек, загрузивший свой файл, не обязан знать наши виды: по
 * умолчанию вид определяет ИИ, а любой документ проверяется хотя бы по общим реквизитам.
 */
const TYPE_HINTS: Record<string, string> = {
  auto: 'ИИ определит вид по тексту документа.',
  OFFICIAL_MEMO: 'Проверим по ГОСТ Р 7.0.97-2016: адресат, дата, номер, подпись.',
  VACATION_REQUEST: 'Проверим сотрудника, вид отпуска, даты и число дней.',
  SUPPORT_MEASURE_REQUEST: 'Проверим заявителя, ИНН, категорию МСП и вид поддержки.',
  BUSINESS_TRIP_REQUEST: 'Проверим сотрудника, место, цель, дату начала и срок.',
  GENERIC: 'Проверим дату, номер, заголовок и подпись.',
};

/** Сначала виды с подробной проверкой, «Другой документ» — последним. */
function sortTypes(types: DocumentType[]): DocumentType[] {
  return [...types].sort((left, right) => Number(left.code === 'GENERIC') - Number(right.code === 'GENERIC'));
}

/**
 * Примеры для демонстрации: у проверяющего в MAX на телефоне нет наших тестовых файлов. Лежат в public/samples
 * (копии testdata/documents), подставляются в ту же зону загрузки — дальше обычный путь, сервер проверяет файл как любой.
 */
const DEMO_SAMPLES = [
  { key: 'memo', label: 'Служебная записка с ошибками', url: '/samples/memo-errors.docx', name: 'Служебная записка с ошибками.docx',
    type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' },
  { key: 'act', label: 'Акт — документ вне шаблонов', url: '/samples/act-errors.pdf', name: 'Акт приёма-передачи.pdf', type: 'application/pdf' },
] as const;

/** 9. Новый документ — шаг 1 «Файл». */
export function DocumentUploadScreen() {
  const navigate = useNavigate();
  const { me } = useSession();
  const inDemo = Boolean(me?.actingAs);
  const [sampleLoading, setSampleLoading] = useState<string | null>(null);
  const [types, setTypes] = useState<DocumentType[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [typeId, setTypeId] = useState<number>(AUTO);
  const [title, setTitle] = useState('');
  const [mainFile, setMainFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [attachments, setAttachments] = useState<{ key: string; file: File }[]>([]);
  const [attachmentsError, setAttachmentsError] = useState<string | null>(null);
  const nextAttachmentKey = useRef(0);
  const [visibility, setVisibility] = useState<Visibility>('PRIVATE');
  const [containsSensitive, setContainsSensitive] = useState(false);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  async function loadTypes() {
    setLoadError(null);
    try {
      setTypes(sortTypes(await getDocumentTypes()));
    } catch (error) {
      setLoadError(errorMessage(error));
    }
  }

  useEffect(() => {
    void loadTypes();
  }, []);

  const selectedType = useMemo(() => (typeId === AUTO ? null : types?.find((type) => type.id === typeId) ?? null), [typeId, types]);
  // С чувствительными данными текст в ИИ не уходит — вид определить нельзя, его выбирает человек.
  const needsExplicitType = containsSensitive && typeId === AUTO;
  const hint = TYPE_HINTS[selectedType?.code ?? 'auto'] ?? 'Проверим по правилам компании.';
  const typeOptions: SelectOption<number>[] = [
    { value: AUTO, label: 'Определить автоматически', description: 'ИИ выберет вид по тексту', disabledReason: containsSensitive ? 'недоступно с чувствительными данными' : undefined },
    ...(types ?? []).map((type) => ({ value: type.id, label: type.name })),
  ];

  function selectFile(file: File) {
    const problem = fileProblem(file);
    setFileError(problem);
    if (!problem) setMainFile(file);
  }

  async function takeSample(sample: (typeof DEMO_SAMPLES)[number]) {
    setSampleLoading(sample.key);
    setFileError(null);
    try {
      const response = await fetch(sample.url);
      if (!response.ok) throw new Error(String(response.status));
      selectFile(new File([await response.blob()], sample.name, { type: sample.type }));
    } catch {
      setFileError('Не удалось загрузить пример, попробуйте ещё раз');
    } finally {
      setSampleLoading(null);
    }
  }

  function addAttachments(selected: File[]) {
    const { accepted, problems } = pickAttachments(selected, attachments.length, attachments.map((item) => item.file));
    setAttachmentsError(problems.length > 0 ? problems.join(' ') : null);
    setSubmitError(null);
    setAttachments((current) => [
      ...current,
      ...accepted.map((file) => ({ key: String(nextAttachmentKey.current++), file })),
    ]);
  }

  const sizeProblem = versionSizeProblem(
    (mainFile?.size ?? 0) + attachments.reduce((total, item) => total + item.file.size, 0),
  );

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!mainFile) {
      setFileError('Сначала выберите файл документа.');
      return;
    }
    if (needsExplicitType || sizeProblem) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const card = await createDocument({
        documentTypeId: selectedType?.id ?? null,
        title,
        visibility,
        containsSensitive,
        fields: containsSensitive ? fields : undefined,
        main: mainFile,
        attachments: attachments.map((item) => item.file),
      });
      navigate(documentCheckPath(card.id), { state: { card } });
    } catch (error) {
      // Файл не подошёл по содержимому (переименован, повреждён, слишком большой) — ошибка у зоны файла, где его и
      // меняют, а не внизу у кнопки; сам файл убираем: он и есть причина.
      // Не подошло приложение — так же, но у списка приложений: убираем именно его.
      const fileError = fileErrorOf(error);
      const attachmentError = attachmentErrorOf(error);
      if (fileError) {
        setMainFile(null);
        setFileError(fileError);
      } else if (attachmentError) {
        setAttachments((current) => current.filter((item) => item.file.name !== attachmentError.fileName));
        setAttachmentsError(attachmentError.message);
      } else {
        setSubmitError(errorMessage(error));
      }
    } finally {
      setSubmitting(false);
    }
  }

  const header = <PageHeader title="Новый документ" back={UPLOAD_BACK} />;
  if (!types && !loadError) {
    return (
      <Page header={header}>
        <Loading />
      </Page>
    );
  }
  if (loadError) {
    return (
      <Page header={header}>
        <ErrorState message={loadError} onRetry={() => void loadTypes()} />
      </Page>
    );
  }
  if (!types || types.length === 0) {
    return (
      <Page header={header}>
        <Notice>Виды документов пока не настроены. Обратитесь к администратору.</Notice>
      </Page>
    );
  }

  return (
    <Page
      header={
        <>
          {header}
          <Stepper steps={CREATE_STEPS} current={0} />
        </>
      }
      footer={
        <>
          {sizeProblem && <InlineError>{sizeProblem}</InlineError>}
          {submitError && <InlineError>{submitError}</InlineError>}
          {submitting && <p className="ds-section__hint" style={{ margin: 0 }}>{containsSensitive ? 'Проверяем документ…' : 'Распознаём и проверяем документ — до минуты.'}</p>}
          <Button type="submit" form={FORM_ID} variant="primary" stretched loading={submitting} disabled={needsExplicitType || Boolean(sizeProblem)}>
            Далее: проверить документ
          </Button>
        </>
      }
    >
      <form id={FORM_ID} style={stackStyle} onSubmit={(event) => void submit(event)}>
        <FileDrop
          file={mainFile}
          accept={MAIN_FILE_ACCEPT}
          title="Выберите файл документа"
          caption={MAIN_FILE_CAPTION}
          error={fileError}
          help={<FormatsHelp />}
          disabled={submitting}
          onSelect={selectFile}
          onRemove={() => {
            setMainFile(null);
            setFileError(null);
          }}
        />

        <AttachmentsField
          items={attachments.map(({ key, file }) => ({ key, name: file.name, size: file.size }))}
          accept={ATTACHMENT_ACCEPT}
          caption={ATTACHMENT_CAPTION}
          error={attachmentsError}
          canAdd={attachments.length < MAX_ATTACHMENTS}
          disabled={submitting}
          onAdd={addAttachments}
          onRemove={(key) => {
            setAttachments((current) => current.filter((item) => item.key !== key));
            setAttachmentsError(null);
            setSubmitError(null);
          }}
        />

        {inDemo && !mainFile && (
          <div style={{ ...sectionStyle, gap: 12 }}>
            <span style={{ ...stackStyle, gap: 4 }}>
              <strong>Нет файла под рукой?</strong>
              <span style={{ fontSize: 14, color: 'var(--text-secondary)' }}>Возьмите пример — он проверится как обычный файл.</span>
            </span>
            {DEMO_SAMPLES.map((sample) => (
              <Button key={sample.key} type="button" stretched loading={sampleLoading === sample.key}
                disabled={submitting || sampleLoading !== null} onClick={() => void takeSample(sample)}>
                {sample.label}
              </Button>
            ))}
          </div>
        )}

        {/* Записка-форма — без файлов, поэтому и без приложений: выбранные приложения пропали бы молча. */}
        {types.some((type) => type.code === 'OFFICIAL_MEMO') && !mainFile && attachments.length === 0 && (
          <div style={{ ...sectionStyle, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
            <span style={{ ...stackStyle, gap: 4 }}>
              <strong>Нет файла?</strong>
              <span style={{ fontSize: 14, color: 'var(--text-secondary)' }}>Служебную записку можно заполнить прямо здесь.</span>
            </span>
            <div style={{ flex: 'none' }}>
              <Button compact type="button" onClick={() => navigate(ROUTE_PATTERNS.memoForm)}>
                Заполнить
              </Button>
            </div>
          </div>
        )}

        <div className="ds-field">
          <SelectField
            label="Вид документа"
            placeholder="Определить автоматически"
            options={typeOptions}
            value={typeId}
            onChange={(next) => {
              setTypeId(next);
              setFields({});
            }}
          />
          <p className="ds-section__hint" style={{ margin: 0 }}>{hint}</p>
        </div>

        <label style={fieldStyle}>
          <span style={labelStyle}>Название</span>
          <Input
            size="large"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            maxLength={255}
            placeholder="Необязательно — возьмём из документа"
          />
        </label>

        {/* Не fieldset: его рамка и legend у браузера рисуются поверх нашей карточки. */}
        <div role="radiogroup" aria-label="Кто видит документ" style={{ ...sectionStyle, gap: 12 }}>
          <strong>Кто видит документ</strong>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Radio name="visibility" checked={visibility === 'PRIVATE'} onChange={() => setVisibility('PRIVATE')} />
            Только участники согласования
          </label>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Radio name="visibility" checked={visibility === 'ORG'} onChange={() => setVisibility('ORG')} />
            Вся компания — после отправки
          </label>
        </div>

        <label style={{ ...sectionStyle, flexDirection: 'row', alignItems: 'center' }}>
          <Switch checked={containsSensitive} onChange={(event) => setContainsSensitive(event.target.checked)} />
          <span style={{ ...stackStyle, gap: 4 }}>
            <strong>Содержит чувствительные данные</strong>
            <span style={{ fontSize: 14, color: 'var(--text-secondary)' }}>Текст не уйдёт ИИ — поля заполните сами.</span>
          </span>
        </label>

        {needsExplicitType && <InlineError>С чувствительными данными вид документа нужно выбрать самому.</InlineError>}

        {containsSensitive && selectedType && (
          <Section title="Данные документа">
            <FieldInputs
              typeCode={selectedType.code}
              definitions={selectedType.fields}
              values={fields}
              onChange={(name, value) => setFields((current) => ({ ...current, [name]: value }))}
            />
          </Section>
        )}
      </form>
    </Page>
  );
}
