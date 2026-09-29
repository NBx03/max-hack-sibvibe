import { Switch, Typography } from '@maxhub/max-ui';
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { createDocumentVersion, getDocument, getDocumentTypes } from '../../api/documents';
import { errorMessage } from '../../api/errors';
import type { DocumentCard, DocumentType } from '../../api/types';
import { FormatsHelp } from './FormatsHelp';
import {
  ATTACHMENT_ACCEPT,
  ATTACHMENT_CAPTION,
  MAIN_FILE_ACCEPT,
  MAIN_FILE_CAPTION,
  MAX_ATTACHMENTS,
  attachmentErrorOf,
  fileErrorOf,
  fileProblem,
  pickAttachments,
  versionSizeProblem,
} from './uploadFormats';
import { AttachmentsField, ErrorState, Button, FileDrop, InlineError, formatFileSize, type AttachmentItem } from '../../ui';
import { documentCardPath, documentCheckPath } from '../../routes/paths';
import {
  DocumentScreen,
  FieldInputs,
  LoadingState,
  Notice,
  SubmitButton,
  sectionStyle,
  stackStyle,
} from './DocumentFormParts';

const FORM_ID = 'document-version-form';

export function NewDocumentVersionScreen() {
  const navigate = useNavigate();
  const parsedId = Number(useParams().id);
  const documentId = Number.isSafeInteger(parsedId) && parsedId > 0 ? parsedId : null;
  const [card, setCard] = useState<DocumentCard | null>(null);
  const [type, setType] = useState<DocumentType>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [newMain, setNewMain] = useState<File | null>(null);
  const [newMainError, setNewMainError] = useState<string | null>(null);
  const [keptAttachments, setKeptAttachments] = useState<Set<number>>(new Set());
  const [newAttachments, setNewAttachments] = useState<{ key: string; file: File }[]>([]);
  const [attachmentsError, setAttachmentsError] = useState<string | null>(null);
  const nextAttachmentKey = useRef(0);
  const [containsSensitive, setContainsSensitive] = useState(false);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (documentId === null) {
      setError('Документ не найден');
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const [nextCard, types] = await Promise.all([getDocument(documentId), getDocumentTypes()]);
      const current = nextCard.versions.find((version) => version.versionNo === nextCard.currentVersionNo);
      setCard(nextCard);
      setType(types.find((item) => item.id === nextCard.type.id));
      setContainsSensitive(current?.containsSensitive ?? false);
      setKeptAttachments(new Set(current?.files.filter((file) => file.kind === 'ATTACHMENT').map((file) => file.id) ?? []));
      setFields(Object.fromEntries(nextCard.check?.fields.map((field) => [field.name, field.value ?? '']) ?? []));
    } catch (loadError) {
      setError(errorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }, [documentId]);

  useEffect(() => {
    void load();
  }, [load]);

  const currentVersion = useMemo(
    () => card?.versions.find((version) => version.versionNo === card.currentVersionNo),
    [card],
  );
  const currentMain = currentVersion?.files.find((file) => file.kind === 'MAIN');
  const attachments = currentVersion?.files.filter((file) => file.kind === 'ATTACHMENT') ?? [];
  const attachmentsChanged = newAttachments.length > 0 || attachments.some((file) => !keptAttachments.has(file.id));
  const attachmentCount = keptAttachments.size + newAttachments.length;
  const keptKey = (fileId: number) => `kept-${fileId}`;
  // Приложения прежней версии — ключом «kept-<id>», новые — «new-<n>»: одна кнопка на строку, «Убрать» или «Оставить».
  const attachmentItems: AttachmentItem[] = [
    ...attachments.map((file) => ({
      key: keptKey(file.id),
      name: file.fileName,
      size: file.size,
      removed: !keptAttachments.has(file.id),
    })),
    ...newAttachments.map(({ key, file }) => ({ key, name: file.name, size: file.size, note: 'новое' })),
  ];
  // Размер новой версии: основной файл (новый или прежний), оставленные и новые приложения.
  const sizeProblem = versionSizeProblem(
    (newMain?.size ?? currentMain?.size ?? 0)
      + attachments.filter((file) => keptAttachments.has(file.id)).reduce((total, file) => total + file.size, 0)
      + newAttachments.reduce((total, item) => total + item.file.size, 0),
  );
  const isReturned = card?.status === 'RETURNED';
  // Без нового файла версия переносит поля прежней (бэкенд их не перечитывает): так можно
  // убрать приложение, не меняя основной файл, а у возвращённого документа — перейти к правке полей.
  // С «чувствительными данными» файл в модель не уходит вовсе: поля этой формы и есть данные версии.
  // У документов демо-песочницы файла нет: тогда без нового файла отправлять нечего.
  const canSubmit = !sizeProblem
    && (Boolean(newMain) || (Boolean(currentMain) && (containsSensitive || attachmentsChanged || isReturned)));

  function addAttachments(selected: File[]) {
    const { accepted, problems } = pickAttachments(selected, attachmentCount, newAttachments.map((item) => item.file));
    setAttachmentsError(problems.length > 0 ? problems.join(' ') : null);
    setSubmitError(null);
    setNewAttachments((current) => [
      ...current,
      ...accepted.map((file) => ({ key: `new-${nextAttachmentKey.current++}`, file })),
    ]);
  }

  /** key — приложение прежней версии: оставить его в новой или убрать. Иначе — ничего. */
  function setKept(key: string, kept: boolean): boolean {
    const file = attachments.find((item) => keptKey(item.id) === key);
    if (!file) return false;
    setSubmitError(null);
    setKeptAttachments((current) => {
      const next = new Set(current);
      if (kept) next.add(file.id); else next.delete(file.id);
      return next;
    });
    return true;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!card) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const updated = await createDocumentVersion(card.id, {
        containsSensitive,
        keepFileIds: [
          ...(newMain || !currentMain ? [] : [currentMain.id]),
          ...Array.from(keptAttachments),
        ],
        main: newMain ?? undefined,
        attachments: newAttachments.map((item) => item.file),
        fields: containsSensitive ? fields : undefined,
      });
      navigate(documentCheckPath(updated.id), { state: { card: updated } });
    } catch (versionError) {
      // Как на первой загрузке: сервер отверг новый файл — ошибка у зоны файла, файл убран.
      // Не подошло новое приложение — у списка приложений, убираем именно его.
      const fileError = newMain ? fileErrorOf(versionError) : null;
      const attachmentError = attachmentErrorOf(versionError);
      if (fileError) {
        setNewMain(null);
        setNewMainError(fileError);
      } else if (attachmentError) {
        setNewAttachments((current) => current.filter((item) => item.file.name !== attachmentError.fileName));
        setAttachmentsError(attachmentError.message);
      } else {
        setSubmitError(errorMessage(versionError));
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) return <LoadingState />;
  if (error || !card || !currentVersion) {
    return (
      <DocumentScreen back={documentId === null ? undefined : { to: documentCardPath(documentId), label: 'К документу' }} title="Замена файла">
        <ErrorState message={error ?? 'Документ не найден'} onRetry={() => void load()} />
      </DocumentScreen>
    );
  }

  return (
    <DocumentScreen back={documentId === null ? undefined : { to: documentCardPath(documentId), label: 'К документу' }}
      title="Замена файла"
      footer={
        <div style={{ ...stackStyle, gap: 8 }}>
          {/* 30 МБ на все файлы версии — проверка до отправки; ошибки сервера (лимиты, тип файла) — здесь же, у кнопки. */}
          {sizeProblem && <InlineError>{sizeProblem}</InlineError>}
          {submitError && <InlineError>{submitError}</InlineError>}
          <SubmitButton form={FORM_ID} busy={submitting} disabled={!canSubmit}>
            Загрузить и проверить
          </SubmitButton>
          <Button stretched type="button" variant="secondary" disabled={submitting} onClick={() => navigate(documentCheckPath(card.id))}>
            Отмена
          </Button>
        </div>
      }
    >
      <Typography.Text>{card.title}</Typography.Text>
      <form id={FORM_ID} style={stackStyle} onSubmit={(event) => void submit(event)}>
        {currentMain && (
          <div style={sectionStyle}>
            <strong>Текущий основной файл</strong>
            <span>{currentMain.fileName}, {formatFileSize(currentMain.size)}</span>
            <span style={{ fontSize: 14, color: 'var(--text-secondary)' }}>
              {newMain ? 'Будет заменён новым файлом' : 'Останется в новой версии'}
            </span>
          </div>
        )}
        <FileDrop
          file={newMain}
          accept={MAIN_FILE_ACCEPT}
          title="Выберите исправленный файл"
          caption={MAIN_FILE_CAPTION}
          error={newMainError}
          help={<FormatsHelp />}
          disabled={submitting}
          onSelect={(file) => {
            const problem = fileProblem(file);
            setNewMainError(problem);
            if (!problem) setNewMain(file);
          }}
          onRemove={() => {
            setNewMain(null);
            setNewMainError(null);
          }}
        />

        {/* У документа без файла (демо-песочница) приложения появятся вместе с основным файлом. */}
        {(currentMain || newMain) && (
          <AttachmentsField
            items={attachmentItems}
            accept={ATTACHMENT_ACCEPT}
            caption={ATTACHMENT_CAPTION}
            error={attachmentsError}
            canAdd={attachmentCount < MAX_ATTACHMENTS}
            disabled={submitting}
            onAdd={addAttachments}
            onRemove={(key) => {
              setAttachmentsError(null);
              setSubmitError(null);
              if (!setKept(key, false)) setNewAttachments((current) => current.filter((item) => item.key !== key));
            }}
            onKeep={(key) => {
              if (attachmentCount >= MAX_ATTACHMENTS) {
                setAttachmentsError(`Не больше ${MAX_ATTACHMENTS} приложений — сначала уберите одно из новых.`);
                return;
              }
              setKept(key, true);
            }}
          />
        )}

        <label style={{ ...sectionStyle, flexDirection: 'row', alignItems: 'center' }}>
          <Switch checked={containsSensitive} onChange={(event) => setContainsSensitive(event.target.checked)} />
          <span style={{ ...stackStyle, gap: 4 }}>
            <strong>Содержит чувствительные данные</strong>
            <span style={{ fontSize: 14, color: 'var(--text-secondary)' }}>Файл не распознаётся автоматически — поля заполните сами.</span>
          </span>
        </label>

        {containsSensitive && type && (
          <section style={sectionStyle}>
            <Typography.Headline variant="medium">Данные документа</Typography.Headline>
            <FieldInputs typeCode={type.code} definitions={type.fields} values={fields} onChange={(name, value) => setFields((current) => ({ ...current, [name]: value }))} />
          </section>
        )}

        {submitting && (
          <Notice>
            {newMain && !containsSensitive
              ? 'Распознаём и проверяем новый файл — до минуты.'
              : 'Проверяем документ…'}
          </Notice>
        )}
      </form>
    </DocumentScreen>
  );
}
