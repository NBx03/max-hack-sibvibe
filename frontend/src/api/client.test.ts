import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiRequest, setActingAsId } from './client';
import { ApiError, GENERIC_ERROR_MESSAGE } from './errors';

// Разбор ответов бэкенда: ошибку пользователь видит текстом бэкенда, а ответы
// nginx не в JSON (413, 502) и истёкший вход дают понятный текст, а не «[object Object]».

function respond(status: number, body: string | null, contentType = 'application/json') {
  return vi.fn().mockResolvedValue(
    new Response(body, { status, headers: body === null ? {} : { 'Content-Type': contentType } }),
  );
}

beforeEach(() => {
  vi.stubGlobal('window', { WebApp: { initData: 'query_id=1&hash=abc' } });
});

afterEach(() => {
  vi.unstubAllGlobals();
  setActingAsId(null);
});

describe('apiRequest', () => {
  it('ошибка бэкенда - его код и текст', async () => {
    vi.stubGlobal('fetch', respond(409, JSON.stringify({ code: 'STEP_NOT_ACTIVE', message: 'Документ уже возвращён' })));
    const error = await apiRequest('/x').catch((err: unknown) => err);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, code: 'STEP_NOT_ACTIVE', message: 'Документ уже возвращён' });
  });

  it('истёкшие данные запуска - просьба открыть приложение заново', async () => {
    vi.stubGlobal('fetch', respond(401, JSON.stringify({ code: 'INIT_DATA_EXPIRED', message: 'expired' })));
    await expect(apiRequest('/x')).rejects.toMatchObject({ status: 401, message: 'Откройте приложение заново' });
  });

  it('ответ nginx не в JSON: 413 - про размер файла, 502 - общий текст', async () => {
    vi.stubGlobal('fetch', respond(413, '<html>413</html>', 'text/html'));
    await expect(apiRequest('/x')).rejects.toMatchObject({ status: 413, message: expect.stringContaining('10 МБ') });

    vi.stubGlobal('fetch', respond(502, '<html>Bad Gateway</html>', 'text/html'));
    await expect(apiRequest('/x')).rejects.toMatchObject({ status: 502, message: GENERIC_ERROR_MESSAGE });
  });

  it('204 - пустой результат, данные запуска и демо-участник уходят заголовками', async () => {
    const fetchMock = respond(204, null);
    vi.stubGlobal('fetch', fetchMock);
    setActingAsId(42);
    await expect(apiRequest('/x', { method: 'DELETE' })).resolves.toBeUndefined();
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.headers).toMatchObject({ Authorization: 'MaxInitData query_id=1&hash=abc', 'X-Demo-Act-As': '42' });
  });
});
