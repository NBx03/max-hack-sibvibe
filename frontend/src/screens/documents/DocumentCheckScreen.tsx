import { Typography } from '@maxhub/max-ui';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  changeDocumentType,
  correctFile,
  recheckDocument,
  createDocumentVersion,
  createFormVersion,
  getDocument,
  getDocumentTypes,
  updateDocumentFields,
} from '../../api/documents';
import { GENERIC_ERROR_MESSAGE, errorMessage } from '../../api/errors';
import type {
  CheckField,
  CheckResult,
  DocumentCard,
  DocumentType,
  DocumentTypeField,
} from '../../api/types';
import {
  AiLegend,
  AiMark,
  Icon,
  BottomSheet,
  Button,
  scrollScreenToTop,
  ErrorState,
  SelectField,
  Stepper,
} from '../../ui';
import { companyRulesPath, documentCardPath, documentNewVersionPath, routePreviewPath } from '../../routes/paths';
import { applyCorrection, changedFields, formContentOf, sameValue, saveAsNewVersion, type CorrectionApi } from './fieldCorrection';
import { useSession } from '../../app/SessionContext';
import { IssueList } from './IssueList';
import { pendingReturn } from './versionOutcome';
import { CREATE_STEPS } from './uploadFormats';
import { isOutsideTemplates, recognizedKindOf } from './documentKind';
import {
  DocumentScreen,
  FieldInputs,
  fieldIssues,
  ISSUE_TONE,
  LoadingState,
  Notice,
  sectionStyle,
  stackStyle,
} from './DocumentFormParts';

const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const OLD_WORD = 'application/msword';

const correctionApi: CorrectionApi = {
  correctFile,
  createDocumentVersion,
  createFormVersion,
  updateDocumentFields,
  getDocument,
};

function documentIdFrom(value: string | undefined): number | null {
  const result = Number(value);
  return Number.isSafeInteger(result) && result > 0 ? result : null;
}

function fieldsToRecord(check: CheckResult | null): Record<string, string> {
  return Object.fromEntries(check?.fields.map((field) => [field.name, field.value ?? '']) ?? []);
}

/** Что сейчас написано в файле: после «Сохранить, не меняя файл» это fileValue, а не value. */
function fileValuesRecord(check: CheckResult | null): Record<string, string> {
  return Object.fromEntries(check?.fields.map((field) => [field.name, field.fileValue ?? field.value ?? '']) ?? []);
}

/** Как на экране загрузки: по алфавиту, «Другой документ» последним. */
function sortTypes(types: DocumentType[]): DocumentType[] {
  return [...types].sort((left, right) =>
    Number(left.code === 'GENERIC') - Number(right.code === 'GENERIC') || left.name.localeCompare(right.name, 'ru'));
}

function fieldDefinitions(type: DocumentType | undefined, check: CheckResult | null): DocumentTypeField[] {
  if (type) return type.fields;
  return check?.fields.map((field) => ({ name: field.name, type: 'STRING', label: field.name, hint: '' })) ?? [];
}

/** Откуда значение поля: из текста документа (с цитатой) или указано вручную, и что тогда в файле. */
function fieldOrigin(field: CheckField, modelAvailable: boolean): string | null {
  if (field.source === 'MANUAL' && field.fileValue === '') {
    return 'Указано вручную — в файле этого нет';
  }
  if (field.source === 'MANUAL' && field.fileValue) {
    return `Изменено вручную, файл не менялся. В файле: «${field.fileValue}»`;
  }
  if (field.source === 'MANUAL') {
    return modelAvailable ? 'Указано вручную' : null;
  }
  if (!field.value) {
    return 'В документе не найдено';
  }
  if (field.quote && field.quote !== field.value) {
    return `Из текста документа: «${field.quote}»${field.page ? `, стр. ${field.page}` : ''}`;
  }
  // «Из текста документа» под каждым полем повторяло ✦ рядом со значением: подпись — только когда она что-то добавляет.
  return field.page ? `Из текста документа, стр. ${field.page}` : null;
}

type CorrectionNotice = {
  /** Номер новой версии, если исправления вписаны в файл. */
  versionNo: number | null;
  checked: boolean;
  /** Поля, которых в файле нет: сохранены в данных документа, файл не менялся. */
  savedWithoutFile: string[];
  notApplied: { field: string; label: string; message: string }[];
};


/** 10. Результат проверки (docs/SCREENS.md). */
export function DocumentCheckScreen() {
  const navigate = useNavigate();
  const documentId = documentIdFrom(useParams().id);
  const [card, setCard] = useState<DocumentCard | null>(null);
  const [types, setTypes] = useState<DocumentType[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<'file' | 'fields' | 'type' | 'recheck' | null>(null);
  const { me } = useSession();
  const isAdmin = Boolean(me?.actingAs ? me.actingAs.isAdmin : me?.membership?.isAdmin);
  const [actionError, setActionError] = useState<string | null>(null);
  const [correction, setCorrection] = useState<CorrectionNotice | null>(null);
  const [typeSheetOpen, setTypeSheetOpen] = useState(false);
  const [nextTypeId, setNextTypeId] = useState<number | null>(null);
  const fieldsRef = useRef<HTMLElement>(null);
  const [scrollToFields, setScrollToFields] = useState(false);

  const load = useCallback(async () => {
    if (documentId === null) {
      setError('Документ не найден');
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const [nextCard, nextTypes] = await Promise.all([getDocument(documentId), getDocumentTypes()]);
      setCard(nextCard);
      setTypes(nextTypes);
      setFields(fieldsToRecord(nextCard.check));
    } catch (loadError) {
      setError(errorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }, [documentId]);

  useEffect(() => {
    void load();
  }, [load]);

  // Прокрутка к форме — после того, как она отрисована: до этого высота раздела ещё прежняя.
  useEffect(() => {
    if (scrollToFields && editing) {
      fieldsRef.current?.scrollIntoView({ block: 'start' });
      setScrollToFields(false);
    }
  }, [scrollToFields, editing]);

  const type = useMemo(() => types.find((item) => item.id === card?.type.id), [types, card?.type.id]);
  const definitions = useMemo(() => fieldDefinitions(type, card?.check ?? null), [card?.check, type]);
  const labels = useMemo(() => new Map(definitions.map((field) => [field.name, field.label])), [definitions]);

  function startEditing() {
    setActionError(null);
    setEditing(true);
    setScrollToFields(true);
  }

  function cancelEditing() {
    setFields(fieldsToRecord(card?.check ?? null));
    setActionError(null);
    setEditing(false);
  }

  function labelOf(name: string): string {
    return labels.get(name) ?? name;
  }

  /** Изменённые относительно показанных значений; пустое и незаполненное — одно и то же. */
  function changedFromShown(): Record<string, string> {
    return Object.fromEntries(changedFields(fields, fieldsToRecord(card?.check ?? null)));
  }

  /**
   * После ошибки перечитываем документ: часть шагов могла уже выполниться, и экран должен показывать
   * фактическую версию, иначе повтор ушёл бы со старым номером версии.
   */
  async function resyncAfterError(documentId: number) {
    try {
      const fresh = await getDocument(documentId);
      setCard(fresh);
      return fresh;
    } catch {
      return null;
    }
  }

  /**
   * «Исправить и проверить» — одно действие без тупиков: что есть в файле,
   * вписывается в файл (новая версия), а поле, которого в файле нет вовсе (адресат), сохраняется
   * в данных документа с пометкой «в файле этого нет» — согласующий её увидит.
   */
  async function writeIntoFile() {
    if (!card) return;
    // Сравниваем с тем, что в файле, а не с показанным значением: после «Сохранить, не меняя файл»
    // поле уже содержит новое значение, и его всё равно можно вписать в файл.
    const inFile = fileValuesRecord(card.check);
    const changed = changedFields(fields, inFile);
    if (changed.length === 0) {
      setActionError('В файле уже такие значения — исправлять нечего');
      return;
    }
    const emptied = changed.filter(([, value]) => !value.trim()).map(([name]) => `«${labelOf(name)}»`);
    if (emptied.length > 0) {
      setActionError(`Пустое значение в файл не вписываем: ${emptied.join(', ')}. Удалить текст можно только в самом документе.`);
      return;
    }
    // Поле, которого в файле нет, — «изменено», только если отличается от уже сохранённого: иначе «Исправить и
    // проверить» без правок у возвращённого документа создавало бы пустую новую версию.
    const shown = fieldsToRecord(card.check);
    const missing = changed.filter(([name, value]) => !(inFile[name] ?? '').trim() && !sameValue(shown[name], value));
    const writable = changed.filter(([name]) => (inFile[name] ?? '').trim());
    if (missing.length === 0 && writable.length === 0) {
      setActionError('Вы ничего не изменили — исправлять нечего');
      return;
    }
    setBusy('file');
    setActionError(null);
    try {
      const outcome = await applyCorrection(
        correctionApi,
        card,
        Object.fromEntries(writable),
        Object.fromEntries(missing),
        setCard,
      );
      const target = outcome.card;
      const versionNo = outcome.versionNo;
      const notApplied = outcome.notApplied.map((item) => ({ field: item.field, label: labelOf(item.field), message: item.message }));
      setCard(target);
      // Невписанные значения не стираем: их можно сохранить без правки файла, не набирая заново.
      const kept = Object.fromEntries(notApplied.map((item) => [item.field, fields[item.field] ?? '']));
      setFields({ ...fieldsToRecord(target.check), ...kept });
      setCorrection({
        versionNo,
        checked: target.check?.status === 'CHECKED',
        savedWithoutFile: missing.map(([name]) => labelOf(name)),
        notApplied,
      });
      setEditing(notApplied.length > 0 && target.permissions.canEditFields);
      scrollScreenToTop();
    } catch (correctionError) {
      setActionError(errorMessage(correctionError));
      // Форму не сбрасываем: введённое остаётся, а номер версии берётся уже из свежей карточки.
      await resyncAfterError(card.id);
    } finally {
      setBusy(null);
    }
  }

  async function saveFieldsOnly() {
    if (!card) return;
    // Только изменённые поля: нетронутые остаются «из текста документа», а не «указано вручную».
    const changed = changedFromShown();
    if (Object.keys(changed).length === 0) {
      setEditing(false);
      return;
    }
    setBusy('fields');
    setActionError(null);
    try {
      // Возвращённая версия не меняется — она в истории: правка идёт новой версией (у формы — новой формой).
      const refreshed = card.permissions.canEditFields
        ? await updateDocumentFields(card.id, card.currentVersionNo, changed).then(() => getDocument(card.id))
        : await saveAsNewVersion(correctionApi, card, changed, setCard);
      setCard(refreshed);
      setFields(fieldsToRecord(refreshed.check));
      setCorrection(null);
      setEditing(false);
    } catch (saveError) {
      setActionError(errorMessage(saveError));
      await resyncAfterError(card.id);
    } finally {
      setBusy(null);
    }
  }

  async function applyType(typeId: number | null = nextTypeId) {
    if (!card || typeId === null || typeId === card.type.id) {
      setTypeSheetOpen(false);
      return;
    }
    setBusy('type');
    setActionError(null);
    try {
      const updated = await changeDocumentType(card.id, card.currentVersionNo, typeId);
      setCard(updated);
      setFields(fieldsToRecord(updated.check));
      setCorrection(null);
      setEditing(false);
      setTypeSheetOpen(false);
    } catch (typeError) {
      setActionError(errorMessage(typeError));
      setTypeSheetOpen(false);
    } finally {
      setBusy(null);
    }
  }

  /**
   * «Проверить заново»: по текущим правилам компании, по тем же полям, без повторного чтения файла.
   * Нужна, когда администратор поменял правила после проверки: замечания — снимок на момент проверки.
   * У возвращённого документа бэкенд создаёт новую версию-черновик с теми же файлами и полями.
   */
  async function recheck() {
    if (!card) return;
    setBusy('recheck');
    setActionError(null);
    try {
      const refreshed = await recheckDocument(card.id, card.currentVersionNo);
      setCard(refreshed);
      setFields(fieldsToRecord(refreshed.check));
      setCorrection(null);
    } catch (recheckError) {
      setActionError(errorMessage(recheckError));
    } finally {
      setBusy(null);
    }
  }

  /**
   * Возвращённый документ без замечаний — дальше, к маршруту: новая версия-черновик с теми же файлами и полями, как
   * у «Проверить заново».
   */
  async function continueFromReturned() {
    if (!card) return;
    setBusy('recheck');
    setActionError(null);
    try {
      const refreshed = await recheckDocument(card.id, card.currentVersionNo);
      if (refreshed.permissions.canSubmit) {
        navigate(routePreviewPath(refreshed.id), { state: { from: 'check' } });
        return;
      }
      setCard(refreshed);
      setFields(fieldsToRecord(refreshed.check));
      setCorrection(null);
    } catch (continueError) {
      setActionError(errorMessage(continueError));
    } finally {
      setBusy(null);
    }
  }

  function saveDraft() {
    if (!card) return;
    navigate(documentCardPath(card.id), { state: { toast: 'Черновик сохранён — он в разделе «Мои»' } });
  }

  const back = documentId === null ? undefined : { to: documentCardPath(documentId), label: 'К документу' };
  if (loading) return <LoadingState />;
  if (error || !card) {
    return (
      <DocumentScreen back={back} title="Результат проверки">
        <ErrorState message={error ?? GENERIC_ERROR_MESSAGE} onRetry={() => void load()} />
      </DocumentScreen>
    );
  }

  const check = card.check;
  const hasBlocker = check?.issues.some((issue) => issue.severity === 'BLOCKER') ?? false;
  const mustFix = check?.status === 'FAILED' || hasBlocker;
  const isDraft = card.status === 'DRAFT';
  const isReturned = card.status === 'RETURNED';
  // Что просил согласующий — там же, где автор исправляет.
  const returned = pendingReturn(card);
  const issuesByField = fieldIssues(check?.issues ?? []);
  const currentVersion = card.versions.find((version) => version.versionNo === card.currentVersionNo);
  const mainFile = currentVersion?.files.find((file) => file.kind === 'MAIN');
  const sensitive = currentVersion?.containsSensitive ?? false;
  // Документ-форма: файла нет — ни «Заменить файл», ни смены типа; «Изменить» правит саму форму.
  const isForm = formContentOf(card) !== null;
  // Возвращённый документ тоже правится здесь же, кнопкой «Изменить»: сохранение создаёт новую версию
  const canSaveFields = card.permissions.canEditFields || (isReturned && card.permissions.canUploadVersion);
  const canEdit = canSaveFields || card.permissions.canCorrectFile;
  const isPdf = Boolean(mainFile && mainFile.mimeType !== DOCX && mainFile.mimeType !== OLD_WORD);
  // DOC (старый Word) новым файлом не принимается, но черновики, загруженные раньше, остались:
  // у них текст не читается не потому, что это скан, — совет другой.
  const isOldWord = Boolean(mainFile && (mainFile.mimeType === OLD_WORD || /\.doc$/i.test(mainFile.fileName)));
  const modelAvailable = check?.modelAvailable ?? false;
  const manualCount = check?.fields.filter((field) => field.source === 'MANUAL' && field.fileValue != null).length ?? 0;
  // Сменить тип можно у своего черновика: тем же правом, что и правка полей.
  // Поля, которых в файле нет, но в форме уже есть значение: вписать некуда, сохраним в данных документа.
  const inFileNow = fileValuesRecord(check ?? null);
  const missingInFile = editing
    ? Object.entries(fields)
        .filter(([name, value]) => value.trim() && !(inFileNow[name] ?? '').trim())
        .map(([name]) => labelOf(name))
    : [];
  // Пока идёт правка полей, смена типа стёрла бы введённое — кнопку прячем.
  const canChangeType = isDraft && card.permissions.canEditFields && !editing && !isForm;

  // Каждое действие — в одном месте («Исправить замечания» внизу и «Изменить» справа
  // делали одно и то же). Поля — «Изменить» у «Данных документа»; файл целиком — «Заменить файл» вверху, рядом
  // с типом, и у чистого черновика тоже; внизу — только куда дальше: к маршруту или сохранить черновик.
  // «Заменить файл», а не «Другой файл»: это новый файл этого же документа, а не другой документ.
  const canReplaceFile = card.permissions.canUploadVersion && !editing && !isForm;
  // Шаги создания — весь путь автора до первой отправки, и когда критичные замечания ещё мешают: исправлять их
  // и есть шаг «Проверка».
  const inCreateFlow = isDraft && card.route === null && (card.permissions.canSubmit || card.permissions.canUploadVersion);
  const recognized = recognizedKindOf(card.type.code, check ?? null);
  // ИИ определил другой вид, чем выбран — подсказка со сменой вида одним нажатием. Она заменяет
  // плашку «Другого документа»: иначе рядом стояли бы «такого вида нет в шаблонах» и «ИИ определил: Служебная записка».
  const aiTypeHint = isDraft && card.type.aiTypeId != null && card.type.aiTypeName ? card.type : null;
  const showKindNotice = isOutsideTemplates(card.type.code) && isDraft && !aiTypeHint;
  const hasAiFields = check?.fields.some((field) => field.source === 'MODEL' && field.value) ?? false;
  const openTypeSheet = () => {
    setNextTypeId(card.type.id);
    setTypeSheetOpen(true);
  };
  // Когда внизу главная «Исправить», кнопки «Изменить» у «Данных документа» нет: они делали одно и то же
  //. Без критичных замечаний внизу «Далее», и править поля можно только через «Изменить».
  const footerEdits = !editing && !(isDraft && card.permissions.canSubmit)
    && (isDraft || isReturned) && canEdit && (mustFix || isReturned);
  const footer = editing ? null : isDraft && card.permissions.canSubmit ? (
    <div style={{ ...stackStyle, gap: 8 }}>
      <Button stretched variant="primary" onClick={() => navigate(routePreviewPath(card.id), { state: { from: 'check' } })}>Далее: выбрать согласующих</Button>
      <Button stretched variant="secondary" onClick={saveDraft}>Сохранить черновик</Button>
    </div>
  ) : isReturned && !mustFix && card.permissions.canUploadVersion ? (
    <div style={{ ...stackStyle, gap: 8 }}>
      <Button stretched variant="primary" loading={busy === 'recheck'} disabled={busy !== null} onClick={() => void continueFromReturned()}>
        Далее: выбрать согласующих
      </Button>
      {canEdit && (
        <Button stretched variant="secondary" disabled={busy !== null} onClick={startEditing}>
          {isForm ? 'Исправить записку' : 'Исправить'}
        </Button>
      )}
    </div>
  ) : (isDraft || isReturned) && canEdit && (mustFix || isReturned) ? (
    // Отправить нельзя — главное действие здесь исправить: раньше главной внизу была
    // «Сохранить черновик», а нужная «Изменить» пряталась в тексте ниже.
    <div style={{ ...stackStyle, gap: 8 }}>
      <Button stretched variant="primary" onClick={startEditing}>{isForm ? 'Исправить записку' : 'Исправить'}</Button>
      {isDraft && <Button stretched variant="secondary" onClick={saveDraft}>Сохранить черновик</Button>}
    </div>
  ) : isDraft && canEdit ? (
    <Button stretched variant="secondary" onClick={saveDraft}>Сохранить черновик</Button>
  ) : null;

  return (
    <DocumentScreen back={back} title={card.title} footer={footer}>
      {inCreateFlow && <Stepper steps={CREATE_STEPS} current={1} />}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginTop: inCreateFlow ? 0 : -8 }}>
        <span style={{ color: 'var(--text-secondary)', display: 'inline-flex', alignItems: 'center', gap: 4, flexWrap: 'wrap' }}>
          {/* У «Другого документа» вид — в плашке ниже, здесь не повторяем. */}
          {showKindNotice ? (
            `Версия ${card.currentVersionNo}`
          ) : (
            <>
              {recognized ? recognized.value : card.type.name}
              {(recognized ? recognized.fromAi : card.type.autoDetected) && <AiMark />}
              {', '}версия {card.currentVersionNo}
            </>
          )}
        </span>
        {canChangeType && !isOutsideTemplates(card.type.code) && (
          <Button compact variant="secondary" onClick={openTypeSheet}>
            Сменить вид
          </Button>
        )}
        {canReplaceFile && (
          <Button compact variant="secondary" onClick={() => navigate(documentNewVersionPath(card.id))}>
            Заменить файл
          </Button>
        )}
      </div>

      {returned && (
        <Notice tone="warning" icon="return">
          <strong>Вернули на доработку</strong> — {returned.approver.fullName}
          {returned.comment && <div style={{ marginTop: 4, color: 'var(--text-primary)' }}>«{returned.comment}»</div>}
        </Notice>
      )}

      {correction && (
        <Notice tone={correction.checked ? 'success' : 'danger'}>
          {correction.versionNo !== null
            ? correction.checked
              ? `Исправлено в файле — версия ${correction.versionNo}, проверена заново.`
              : `Исправлено в файле — версия ${correction.versionNo}, но проверка не удалась. Проверьте поля или замените файл.`
            : 'Сохранено и проверено заново.'}
          {correction.savedWithoutFile.length > 0 && (
            <div style={{ marginTop: 8 }}>
              {correction.savedWithoutFile.map((label) => `«${label}»`).join(', ')}: в файле этого нет — сохранено в данных
              документа, согласующие это увидят.
            </div>
          )}
          {correction.notApplied.length > 0 && (
            <ul style={{ margin: '8px 0 0', paddingLeft: 18 }}>
              {correction.notApplied.map((item) => (
                <li key={item.field}>
                  «{item.label}»: {item.message}
                </li>
              ))}
            </ul>
          )}
        </Notice>
      )}

      {aiTypeHint && (
        <Notice tone="warning">
          <div style={{ ...stackStyle, gap: 8 }}>
            <span>
              Выбран вид «{card.type.name}», а ИИ определил «{aiTypeHint.aiTypeName}». Согласующие увидят это расхождение.
            </span>
            {canChangeType && (
              <div>
                <Button compact variant="secondary" loading={busy === 'type'} onClick={() => void applyType(aiTypeHint.aiTypeId ?? null)}>
                  Сменить на «{aiTypeHint.aiTypeName}»
                </Button>
              </div>
            )}
          </div>
        </Notice>
      )}
      {showKindNotice && (
        <GenericKindNotice kind={recognized} textMissing={Boolean(check?.textMissing)} canChangeType={canChangeType} onChangeType={openTypeSheet} />
      )}

      <HowChecked
        modelAvailable={modelAvailable}
        sensitive={sensitive}
        textMissing={Boolean(check?.textMissing)}
        oldWord={isOldWord}
        canEdit={canEdit}
        isForm={isForm}
      />

      {check?.status === 'FAILED' && (
        <Notice tone="danger">
          {isForm ? 'Проверка не выполнена. Сохраните записку ещё раз.' : 'Проверка не выполнена. Исправьте поля или загрузите файл ещё раз.'}
        </Notice>
      )}
      {mustFix && check?.status !== 'FAILED' && (
        <Notice tone="danger">
          {/* Как исправить — показывает кнопка «Исправить» внизу, текстом не повторяем. */}
          Есть критичные замечания — отправить пока нельзя.
        </Notice>
      )}
      {manualCount > 0 && !editing && (
        <Notice>
          Указано вручную, без правки файла: {manualCount}. Согласующие это увидят.
        </Notice>
      )}
      {actionError && !editing && <Notice tone="danger">{actionError}</Notice>}

      <section style={sectionStyle}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
          <Typography.Headline variant="medium">Замечания</Typography.Headline>
          {/* У возвращённого — тоже: проверяется новая версия-черновик, возвращённая остаётся в истории. */}
          {(isDraft || isReturned) && card.permissions.canUploadVersion && !editing && (
            <div style={{ flex: 'none', whiteSpace: 'nowrap' }}>
              <Button compact variant="secondary" loading={busy === 'recheck'} disabled={busy !== null} onClick={() => void recheck()}>
                Проверить заново
              </Button>
            </div>
          )}
        </div>
        {!check || check.issues.length === 0 ? (
          <Notice tone="success">Замечаний нет</Notice>
        ) : (
          <IssueList
            issues={check.issues}
            labelOf={(name) => labels.get(name) ?? name}
            onConfigure={isAdmin ? (ruleId) => navigate(companyRulesPath(ruleId)) : undefined}
          />
        )}
      </section>

      <section ref={fieldsRef} style={{ ...sectionStyle, scrollMarginTop: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
          <Typography.Headline variant="medium">Данные документа</Typography.Headline>
          {canEdit && !editing && !footerEdits && (
            <div style={{ flex: 'none', whiteSpace: 'nowrap' }}>
              <Button compact variant="secondary" onClick={startEditing}>Изменить</Button>
            </div>
          )}
        </div>

        {editing ? (
          <div style={stackStyle}>
            <Typography.Text style={{ color: 'var(--text-secondary)' }}>
              {card.permissions.canCorrectFile
                ? 'Новые значения впишем в файл Word на место прежних — получится новая версия.'
                : isForm
                  ? isReturned ? 'Исправленная записка станет новой версией.' : 'Исправьте записку — правила проверят её заново.'
                  : 'Исправьте значения, распознанные неверно.'}
            </Typography.Text>
            <FieldInputs
              typeCode={type?.code}
              definitions={definitions}
              issues={issuesByField}
              values={fields}
              onChange={(name, value) => setFields((current) => ({ ...current, [name]: value }))}
            />
            {card.permissions.canCorrectFile && missingInFile.length > 0 && (
              <Notice>
                В файле нет {missingInFile.map((label) => `«${label}»`).join(', ')} — значение сохранится в данных документа.
              </Notice>
            )}
            {!card.permissions.canCorrectFile && isPdf && (
              <Notice>PDF не исправляется автоматически: ошибку в самом документе исправьте в исходнике и замените файл.</Notice>
            )}
            {actionError && <Notice tone="danger">{actionError}</Notice>}
            <div style={{ ...stackStyle, gap: 8 }}>
              {card.permissions.canCorrectFile && (
                <Button stretched variant="primary" loading={busy === 'file'} disabled={busy !== null} onClick={() => void writeIntoFile()}>
                  Исправить и проверить
                </Button>
              )}
              {canSaveFields && (
                <>
                  <Button
                    stretched
                    variant={card.permissions.canCorrectFile ? 'secondary' : 'primary'}
                    loading={busy === 'fields'}
                    disabled={busy !== null}
                    onClick={() => void saveFieldsOnly()}
                  >
                    {card.permissions.canCorrectFile ? 'Сохранить, не меняя файл' : 'Сохранить и проверить'}
                  </Button>
                  {card.permissions.canCorrectFile && (
                    <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                      Если поле распознано неверно, а в файле всё верно.
                    </span>
                  )}
                </>
              )}
              {!card.permissions.canCorrectFile && card.permissions.canUploadVersion && !isForm && (
                <Button stretched variant="secondary" disabled={busy !== null} onClick={() => navigate(documentNewVersionPath(card.id))}>
                  Заменить файл
                </Button>
              )}
              <Button stretched variant="secondary" disabled={busy !== null} onClick={cancelEditing}>
                Отмена
              </Button>
            </div>
            {busy === 'file' && <Notice>Вписываем исправления и проверяем — до минуты.</Notice>}
          </div>
        ) : check?.fields.length ? (
          <>
          {hasAiFields && <AiLegend />}
          <dl style={{ margin: 0 }}>
            {check.fields.map((field, index) => {
              const origin = fieldOrigin(field, modelAvailable);
              return (
                <div key={field.name} style={{ padding: '10px 0', borderTop: index > 0 ? '1px solid var(--divider-primary)' : undefined }}>
                  <dt style={{ color: 'var(--text-tertiary)', fontSize: 13, display: 'flex', alignItems: 'center', gap: 4 }}>
                    {labels.get(field.name) ?? field.name}
                    {/* Поле с замечанием — значок цвета важности: связь с замечанием выше видна без правки. */}
                    {issuesByField[field.name] && (
                      <span className={`ds-field-issue ds-field-issue--${ISSUE_TONE[issuesByField[field.name].severity]}`}>
                        <Icon name="alert" size={14} label="есть замечание" />
                      </span>
                    )}
                  </dt>
                  <dd style={{ margin: '3px 0 0', overflowWrap: 'anywhere', color: field.value ? undefined : 'var(--text-tertiary)' }}>
                    {field.value || 'Не заполнено'}
                    {field.source === 'MODEL' && field.value && <> <AiMark /></>}
                  </dd>
                  {origin && (
                    <dd style={{ margin: '3px 0 0', fontSize: 13, overflowWrap: 'anywhere', color: field.fileValue ? 'var(--status-warning-fg)' : 'var(--text-secondary)' }}>
                      {origin}
                    </dd>
                  )}
                </div>
              );
            })}
          </dl>
          </>
        ) : (
          <Typography.Text>Данных для отображения нет.</Typography.Text>
        )}
      </section>

      <BottomSheet
        open={typeSheetOpen}
        onClose={() => setTypeSheetOpen(false)}
        title="Вид документа"
        dismissible={busy !== 'type'}
      >
        <Typography.Text style={{ color: 'var(--text-secondary)' }}>
          От вида зависят поля, правила и маршрут. Документ проверится заново.
        </Typography.Text>
        <SelectField
          placeholder="Выберите вид"
          sheetTitle="Вид документа"
          options={sortTypes(types).map((item) => ({ value: item.id, label: item.name }))}
          value={nextTypeId ?? null}
          onChange={setNextTypeId}
        />
        <Button stretched variant="primary" loading={busy === 'type'} disabled={busy !== null} onClick={() => void applyType()}>
          Проверить по этому виду
        </Button>
      </BottomSheet>
    </DocumentScreen>
  );
}

/** Коротко: что сделал ИИ, что правила и кто решает. Без названия модели: провайдер меняется настройкой. */
function HowChecked({
  modelAvailable,
  sensitive,
  textMissing,
  oldWord,
  canEdit,
  isForm,
}: {
  modelAvailable: boolean;
  sensitive: boolean;
  textMissing: boolean;
  /** Основной файл — DOC: текст не читается не потому, что это скан. */
  oldWord: boolean;
  canEdit: boolean;
  /** Записка заполнена в приложении: распознавать нечего — это не «распознавание недоступно». */
  isForm: boolean;
}) {
  const fill = canEdit ? 'Заполните поля вручную' : 'Поля заполняет автор';
  const text = isForm
    ? 'Записка заполнена в приложении — правила проверили её поля.'
    : textMissing && oldWord
    ? `Старый формат Word (DOC): ИИ его не читает. ${fill} — правила проверят их так же — или сохраните файл как DOCX и замените.`
    : textMissing
    ? `Похоже, это скан: в файле нет текста. ${fill} или загрузите файл с текстом.`
    : modelAvailable
    ? 'Реквизиты распознаны по тексту — у каждого поля видно, откуда оно. Замечания находят правила, решение принимают люди. '
      + '«Кратко от ИИ» на карточке появляется, только если все числа и даты в нём подтверждаются текстом документа.'
    : sensitive
      ? 'Чувствительные данные: текст не распознавался автоматически, поля заполнены вручную.'
      : `Распознавание сейчас недоступно. ${fill} — правила проверят так же.`;
  return (
    <details className="ds-disclosure" style={{ ...sectionStyle, gap: 8, padding: '12px 16px' }} open={!modelAvailable && !isForm}>
      <summary>
        Как проверялся документ
        <span className="ds-disclosure__chevron"><Icon name="chevronDown" size={20} /></span>
      </summary>
      <Typography.Text style={{ color: 'var(--text-secondary)' }}>{text}</Typography.Text>
    </details>
  );
}

/**
 * «Другой документ» на шаге проверки: что распознал ИИ, что это значит для проверки и маршрута, и выход —
 * выбрать вид из списка. Честно: у такого документа правила проверяют только общие реквизиты.
 */
function GenericKindNotice({
  kind,
  textMissing,
  canChangeType,
  onChangeType,
}: {
  kind: { value: string; fromAi: boolean } | null;
  /** Текста в файле нет — ИИ вид и не мог распознать: так и говорим, а не «не распознан». */
  textMissing: boolean;
  canChangeType: boolean;
  onChangeType: () => void;
}) {
  return (
    <section style={{ ...sectionStyle, gap: 8 }}>
      <strong style={{ display: 'inline-flex', alignItems: 'center', gap: 4, flexWrap: 'wrap' }}>
        {kind ? (
          <>
            {kind.fromAi ? 'ИИ распознал' : 'Вид'}: {kind.value}
            {kind.fromAi && <AiMark />}
          </>
        ) : textMissing ? (
          'Вид не определён: текст файла не прочитан'
        ) : (
          'Вид документа не распознан'
        )}
      </strong>
      <Typography.Text style={{ color: 'var(--text-secondary)' }}>
        {kind
          ? 'Такого вида нет в шаблонах компании — проверим общие реквизиты: дату, номер, заголовок и подпись. Согласующих выберете сами.'
          : 'Если это документ одного из видов компании — выберите вид из списка, проверка будет подробнее. Иначе проверим общие реквизиты: дату, номер, заголовок и подпись.'}
      </Typography.Text>
      {canChangeType && (
        <div>
          <Button compact variant="secondary" onClick={onChangeType}>Выбрать вид из списка</Button>
        </div>
      )}
    </section>
  );
}
