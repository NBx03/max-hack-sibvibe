import { apiRequest } from './client';
import type {
  CheckResult,
  DocumentCard,
  DocumentListItem,
  DisplayStatus,
  DocumentType,
  FileCorrectionResult,
  Page,
  RoutePreview,
  RoutePerson,
  Visibility,
} from './types';

export interface CreateDocumentInput {
  /** null — «Определить автоматически»: тип выберет ИИ по тексту. */
  documentTypeId: number | null;
  /** Пусто — название по содержанию документа или имени файла. */
  title: string;
  visibility: Visibility;
  containsSensitive: boolean;
  fields?: Record<string, string>;
  main: File;
  attachments?: File[];
}

export interface CreateVersionInput {
  fields?: Record<string, string>;
  containsSensitive: boolean;
  keepFileIds: number[];
  main?: File;
  attachments?: File[];
}

/** Документ-форма: содержимое заполняется в приложении, файла нет. */
export interface CreateFormDocumentInput {
  documentTypeId: number;
  /** Пусто — по заголовку записки. */
  title: string;
  visibility: Visibility;
  containsSensitive: boolean;
  content: Record<string, string>;
}

function jsonPart(value: unknown): Blob {
  return new Blob([JSON.stringify(value)], { type: 'application/json' });
}

function appendFiles(form: FormData, field: string, files: File[] | undefined): void {
  files?.forEach((file) => form.append(field, file));
}

export type DocumentTab = 'WAITING_ME' | 'MINE' | 'AVAILABLE';

export interface DocumentListFilters {
  /** Показываемый статус: в том числе «На утверждении» и «Утверждён». */
  status?: DisplayStatus;
  /** Поиск: название, автор, номер документа. */
  q?: string;
  typeId?: number;
  authorId?: number;
  /** Даты создания, ISO-8601. */
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

function buildQuery(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') {
      search.set(key, String(value));
    }
  }
  const query = search.toString();
  return query ? `?${query}` : '';
}

export function getDocumentTypes(): Promise<DocumentType[]> {
  return apiRequest<DocumentType[]>('/document-types');
}

export function getDocuments(tab: DocumentTab, filters: DocumentListFilters = {}): Promise<Page<DocumentListItem>> {
  return apiRequest<Page<DocumentListItem>>(`/documents${buildQuery({ tab, ...filters })}`);
}

export function getDocument(documentId: number): Promise<DocumentCard> {
  return apiRequest<DocumentCard>(`/documents/${documentId}`);
}

export function createDocument(input: CreateDocumentInput): Promise<DocumentCard> {
  const form = new FormData();
  form.append(
    'meta',
    jsonPart({
      ...(input.documentTypeId !== null ? { documentTypeId: input.documentTypeId } : {}),
      ...(input.title.trim() ? { title: input.title.trim() } : {}),
      visibility: input.visibility,
      containsSensitive: input.containsSensitive,
      ...(input.fields ? { fields: input.fields } : {}),
    }),
  );
  form.append('main', input.main);
  appendFiles(form, 'attachments', input.attachments);
  return apiRequest<DocumentCard>('/documents', { method: 'POST', body: form, isFormData: true });
}

/** Тот же POST /documents, но JSON-телом с content вместо файлов; модель не вызывается. */
export function createFormDocument(input: CreateFormDocumentInput): Promise<DocumentCard> {
  return apiRequest<DocumentCard>('/documents', {
    method: 'POST',
    body: {
      documentTypeId: input.documentTypeId,
      ...(input.title.trim() ? { title: input.title.trim() } : {}),
      visibility: input.visibility,
      containsSensitive: input.containsSensitive,
      content: input.content,
    },
  });
}

/** Новая версия документа-формы: содержимое целиком. Так правится форма после возврата. */
export function createFormVersion(
  documentId: number,
  input: { content: Record<string, string>; containsSensitive: boolean },
): Promise<DocumentCard> {
  return apiRequest<DocumentCard>(`/documents/${documentId}/versions`, { method: 'POST', body: input });
}

export function createDocumentVersion(documentId: number, input: CreateVersionInput): Promise<DocumentCard> {
  const form = new FormData();
  form.append(
    'meta',
    jsonPart({
      containsSensitive: input.containsSensitive,
      keepFileIds: input.keepFileIds,
      ...(input.fields ? { fields: input.fields } : {}),
    }),
  );
  if (input.main) {
    form.append('main', input.main);
  }
  appendFiles(form, 'attachments', input.attachments);
  return apiRequest<DocumentCard>(`/documents/${documentId}/versions`, {
    method: 'POST',
    body: form,
    isFormData: true,
  });
}

export function updateDocumentFields(
  documentId: number,
  versionNo: number,
  fields: Record<string, string>,
): Promise<CheckResult> {
  return apiRequest<CheckResult>(`/documents/${documentId}/versions/${versionNo}/fields`, {
    method: 'PUT',
    body: { fields },
    timeoutMs: 120_000,
  });
}

/**
 * «Исправить в файле»: исправленные значения вписываются в DOCX, документ получает новую
 * версию и проверяется заново — модель снова читает файл, поэтому до 2 минут.
 */
export function correctFile(
  documentId: number,
  versionNo: number,
  fields: Record<string, string>,
): Promise<FileCorrectionResult> {
  return apiRequest<FileCorrectionResult>(`/documents/${documentId}/versions/${versionNo}/corrections`, {
    method: 'POST',
    body: { fields },
    timeoutMs: 120_000,
  });
}

/** «Проверить заново»: черновик — по текущим правилам компании, по сохранённым полям, без модели. */
export function recheckDocument(documentId: number, versionNo: number): Promise<DocumentCard> {
  return apiRequest<DocumentCard>(`/documents/${documentId}/versions/${versionNo}/recheck`, {
    method: 'POST',
    timeoutMs: 60_000,
  });
}

/** Сменить тип черновика, если ИИ определил вид неверно; версия перепроверяется. */
export function changeDocumentType(documentId: number, versionNo: number, documentTypeId: number): Promise<DocumentCard> {
  return apiRequest<DocumentCard>(`/documents/${documentId}/versions/${versionNo}/type`, {
    method: 'PUT',
    body: { documentTypeId },
    timeoutMs: 120_000,
  });
}

/** Удалить черновик, который ещё не отправлялся. */
export function deleteDocument(documentId: number): Promise<void> {
  return apiRequest<void>(`/documents/${documentId}`, { method: 'DELETE' });
}

/** Автор отзывает документ с согласования: документ возвращается к нему, маршрут начнётся заново. */
export function withdrawDocument(documentId: number): Promise<DocumentCard> {
  return apiRequest<DocumentCard>(`/documents/${documentId}/withdraw`, { method: 'POST' });
}

export function getRoutePreview(documentId: number): Promise<RoutePreview> {
  return apiRequest<RoutePreview>(`/documents/${documentId}/route-preview`);
}

/**
 * Маршрут из конструктора: этапы по порядку, в этапе — одновременно; утверждающий — последний шаг.
 * Сервер проверяет обязательные роли, повторы и пустые этапы.
 */
export interface SubmitDocumentInput {
  stages: { participants: RoutePerson[] }[];
  endorser: RoutePerson | null;
}

export function submitDocument(documentId: number, input: SubmitDocumentInput): Promise<DocumentCard> {
  return apiRequest<DocumentCard>(`/documents/${documentId}/submit`, { method: 'POST', body: input });
}

export type StepDecisionAction = 'APPROVE' | 'RETURN' | 'REJECT';

/** Решение адресуется шагу, а не документу - docs/API_CONTRACTS.md. */
export function decideStep(stepId: number, decision: StepDecisionAction, comment?: string): Promise<DocumentCard> {
  return apiRequest<DocumentCard>(`/approval-steps/${stepId}/decision`, {
    method: 'POST',
    body: { decision, comment },
  });
}
