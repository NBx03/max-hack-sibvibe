import type { CheckResult, DocumentCard, FileCorrectionResult } from '../../api/types';

// Логика «Исправить и проверить» без React — чтобы её можно было проверить тестом.

/**
 * Одно и то же ли значение. Пустое и незаполненное — одно и то же (поле открыли и не тронули). Непустые
 * сравниваются дословно: правила проверяют строку целиком, и исправление « ИНН» → «ИНН» — тоже исправление.
 */
export function sameValue(left: string | null | undefined, right: string | null | undefined): boolean {
  const a = left ?? '';
  const b = right ?? '';
  if (a.trim() === '' && b.trim() === '') return true;
  return a === b;
}

/** Поля формы, которые отличаются от reference. */
export function changedFields(fields: Record<string, string>, reference: Record<string, string>): [string, string][] {
  return Object.entries(fields).filter(([name, value]) => !sameValue(reference[name], value));
}

/**
 * Новая версия с теми же файлами — так правятся поля возвращённого документа. Что перенести:
 * у документа с чувствительными данными поля и есть данные версии — передаются все; ручные значения
 * передаются заново всегда — бэкенд переносит поля сам, только если прошлая проверка прошла.
 */
export function keepingFilesInput(card: DocumentCard): {
  containsSensitive: boolean;
  keepFileIds: number[];
  fields?: Record<string, string>;
} {
  const current = card.versions.find((version) => version.versionNo === card.currentVersionNo);
  const containsSensitive = current?.containsSensitive ?? false;
  return {
    containsSensitive,
    keepFileIds: current?.files.map((file) => file.id) ?? [],
    ...(containsSensitive ? { fields: allValues(card) } : {}),
  };
}

function allValues(card: DocumentCard): Record<string, string> {
  return Object.fromEntries(card.check?.fields.map((field) => [field.name, field.value ?? '']) ?? []);
}

/** Значения, введённые вручную, — их нужно сохранить и в новой версии. */
export function manualValues(card: DocumentCard): Record<string, string> {
  return Object.fromEntries(card.check?.fields
    .filter((field) => field.source === 'MANUAL')
    .map((field) => [field.name, field.value ?? '']) ?? []);
}

/** Содержимое документа-формы текущей версии; null — документ загружен файлом. */
export function formContentOf(card: DocumentCard): Record<string, string> | null {
  return card.versions.find((version) => version.versionNo === card.currentVersionNo)?.content ?? null;
}

export interface CorrectionApi {
  correctFile(documentId: number, versionNo: number, fields: Record<string, string>): Promise<FileCorrectionResult>;
  createDocumentVersion(
    documentId: number,
    input: { containsSensitive: boolean; keepFileIds: number[]; fields?: Record<string, string> },
  ): Promise<DocumentCard>;
  createFormVersion(
    documentId: number,
    input: { content: Record<string, string>; containsSensitive: boolean },
  ): Promise<DocumentCard>;
  updateDocumentFields(documentId: number, versionNo: number, fields: Record<string, string>): Promise<CheckResult>;
  getDocument(documentId: number): Promise<DocumentCard>;
}

/**
 * Изменённые поля возвращённого документа — новой версией: возвращённая версия в истории и не меняется.
 * Документ с файлом — версия с теми же файлами, потом поля (ручные значения — заново: бэкенд переносит поля
 * сам, только если прошлая проверка прошла). Документ-форма — сразу исправленная форма
 * целиком: версия «с теми же файлами» потеряла бы её содержимое.
 *
 * Каждая полученная карточка сразу отдаётся в onCard — см. applyCorrection.
 */
export async function saveAsNewVersion(
  api: Pick<CorrectionApi, 'createDocumentVersion' | 'createFormVersion' | 'updateDocumentFields' | 'getDocument'>,
  start: DocumentCard,
  changed: Record<string, string>,
  onCard: (card: DocumentCard) => void,
): Promise<DocumentCard> {
  const content = formContentOf(start);
  if (content) {
    const current = start.versions.find((version) => version.versionNo === start.currentVersionNo);
    const next = await api.createFormVersion(start.id, {
      content: { ...content, ...changed },
      containsSensitive: current?.containsSensitive ?? false,
    });
    onCard(next);
    return next;
  }
  const next = await api.createDocumentVersion(start.id, keepingFilesInput(start));
  onCard(next);
  await api.updateDocumentFields(next.id, next.currentVersionNo, { ...manualValues(start), ...changed });
  const fresh = await api.getDocument(start.id);
  onCard(fresh);
  return fresh;
}

export interface CorrectionOutcome {
  card: DocumentCard;
  /** Номер новой версии, если она создана. */
  versionNo: number | null;
  notApplied: { field: string; message: string }[];
}

/**
 * Что есть в файле — вписывается в файл (новая версия), чего в файле нет — сохраняется в данных документа;
 * у возвращённого документа — сначала новая версия с теми же файлами.
 *
 * Шаги не атомарны: версия может уже появиться, а следующий шаг упасть. Поэтому каждая полученная карточка
 * сразу отдаётся в {@code onCard} — экран переходит на фактическую версию, и повтор не уйдёт со старым номером
 * версии (409). Ошибку вызывающий всё равно получает.
 */
export async function applyCorrection(
  api: CorrectionApi,
  start: DocumentCard,
  writable: Record<string, string>,
  missing: Record<string, string>,
  onCard: (card: DocumentCard) => void,
): Promise<CorrectionOutcome> {
  let card = start;
  let versionNo: number | null = null;
  let notApplied: CorrectionOutcome['notApplied'] = [];
  if (Object.keys(writable).length > 0) {
    const result = await api.correctFile(card.id, card.currentVersionNo, writable);
    card = result.card;
    versionNo = card.currentVersionNo;
    notApplied = result.notApplied;
    onCard(card);
  }
  if (Object.keys(missing).length > 0) {
    if (card.permissions.canEditFields) {
      await api.updateDocumentFields(card.id, card.currentVersionNo, missing);
      card = await api.getDocument(card.id);
      onCard(card);
    } else {
      card = await saveAsNewVersion(api, card, missing, onCard);
      versionNo = card.currentVersionNo;
    }
  }
  return { card, versionNo, notApplied };
}
