import { getAuthHeader } from './auth';
import { ApiError, GENERIC_ERROR_MESSAGE, type ApiErrorBody } from './errors';

const BASE_PATH = '/api/v1';
const DEFAULT_TIMEOUT_MS = 18_000;
// Загрузка файла (до 30 МБ с телефона) и эндпоинты, синхронно вызывающие модель
// (POST /documents, PUT .../fields - docs/API_CONTRACTS.md), дольше обычного запроса.
// На них общий тайм-аут обрывал бы соединение раньше ответа, а документ на сервере
// уже создался бы - повторное нажатие "Загрузить" тогда создаёт дубликат.
const UPLOAD_TIMEOUT_MS = 120_000;

const FILE_TOO_LARGE_MESSAGE = 'Файл слишком большой: до 10 МБ на файл и 30 МБ на все файлы';
const AUTH_FAILED_MESSAGE = 'Не удалось подтвердить вход через MAX';
const AUTH_EXPIRED_MESSAGE = 'Откройте приложение заново';

/**
 * Демо-участник хранится только здесь и передаётся в каждом запросе -
 * на сервере "текущий демо-пользователь" не хранится (docs/API_CONTRACTS.md).
 * SessionContext - источник истины для UI, эта переменная - её зеркало для клиента.
 */
let actingAsId: number | null = null;

export function setActingAsId(id: number | null): void {
  actingAsId = id;
}

export function getActingAsId(): number | null {
  return actingAsId;
}

interface ApiRequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** true - body уже FormData (загрузка файлов), сериализовать в JSON не нужно. */
  isFormData?: boolean;
  /**
   * Переопределяет тайм-аут запроса (мс). По умолчанию - DEFAULT_TIMEOUT_MS, а для
   * isFormData - UPLOAD_TIMEOUT_MS без явного указания; передайте своё значение,
   * если экран точно знает, что вызов дольше (или короче) типичного.
   */
  timeoutMs?: number;
}

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as Record<string, unknown>).code === 'string' &&
    typeof (value as Record<string, unknown>).message === 'string'
  );
}

/** Запрос на относительный /api/v1/..., nginx проксирует на бэкенд - CORS не нужен. */
export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {
    Authorization: getAuthHeader(),
  };
  if (actingAsId !== null) {
    headers['X-Demo-Act-As'] = String(actingAsId);
  }

  let body: BodyInit | undefined;
  if (options.isFormData) {
    body = options.body as FormData;
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }

  // Без тайм-аута зависший запрос (оборвавшаяся сеть, зависший бэкенд) держит экран
  // в состоянии "загрузка" бесконечно - пользователь не видит ни данных, ни ошибки.
  const timeoutMs = options.timeoutMs ?? (options.isFormData ? UPLOAD_TIMEOUT_MS : DEFAULT_TIMEOUT_MS);
  const timeoutController = new AbortController();
  const timeoutId = setTimeout(() => timeoutController.abort(), timeoutMs);

  let response: Response;
  try {
    response = await fetch(`${BASE_PATH}${path}`, {
      method: options.method ?? 'GET',
      headers,
      body,
      signal: timeoutController.signal,
    });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw new ApiError(0, { code: 'TIMEOUT', message: GENERIC_ERROR_MESSAGE });
    }
    throw err;
  } finally {
    clearTimeout(timeoutId);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const rawText = await response.text();
  let parsed: unknown = null;
  if (rawText) {
    try {
      parsed = JSON.parse(rawText);
    } catch {
      // Ответ не в JSON - страница nginx (413, 502, 504) или обрыв сети.
      parsed = null;
    }
  }

  if (!response.ok) {
    if (response.status === 401) {
      // Contract: INIT_DATA_EXPIRED - данные запуска старше 24 часов, нужен
      // именно перезапуск приложения, а не общий текст "не удалось подтвердить".
      const code = isApiErrorBody(parsed) ? parsed.code : 'INIT_DATA_INVALID';
      throw new ApiError(401, {
        code,
        message: code === 'INIT_DATA_EXPIRED' ? AUTH_EXPIRED_MESSAGE : AUTH_FAILED_MESSAGE,
      });
    }
    if (isApiErrorBody(parsed)) {
      throw new ApiError(response.status, parsed);
    }
    throw new ApiError(response.status, {
      code: 'UNKNOWN',
      message: response.status === 413 ? FILE_TOO_LARGE_MESSAGE : GENERIC_ERROR_MESSAGE,
    });
  }

  return parsed as T;
}
