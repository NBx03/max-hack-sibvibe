/** Общий текст для сетевых сбоев и неизвестных ошибок - один на весь фронтенд (client.ts, errors.ts). */
export const GENERIC_ERROR_MESSAGE = 'Сервис временно недоступен, попробуйте ещё раз';

/** Формат ошибки бэкенда - см. docs/API_CONTRACTS.md, раздел "Ошибки". */
export interface ApiErrorBody {
  code: string;
  message: string;
  details?: Record<string, unknown>;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: Record<string, unknown>;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message);
    this.name = 'ApiError';
    this.status = status;
    this.code = body.code;
    this.details = body.details;
  }
}

/** Текст для ErrorState - "Ошибки бэкенда показываем его текстом" (docs/SCREENS.md). */
export function errorMessage(err: unknown): string {
  return err instanceof ApiError ? err.message : GENERIC_ERROR_MESSAGE;
}
