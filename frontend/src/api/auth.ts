import { getWebApp } from '../max/webApp';
import { ApiError } from './errors';

/**
 * Заглушка только для разработки вне MAX - обычный браузер без window.WebApp.
 * Бэкенд принимает "Authorization: Dev <maxUserId>" только в профиле Spring `dev`.
 * import.meta.env.DEV - статическая константа Vite: в prod-сборке ветка ниже
 * не попадает в бандл (dead code elimination), заглушки в собранной версии нет.
 */
const DEV_FALLBACK_USER_ID = '1';
const DEV_USER_KEY = 'approval.devUser';

/**
 * Локально второго человека не открыть в MAX: `?devUser=2` в адресе запоминает, за кого
 * войти в этой вкладке (автор и согласующий — в двух вкладках). Только для разработки.
 */
function devUserId(): string {
  try {
    const fromUrl = new URLSearchParams(window.location.search).get('devUser');
    if (fromUrl && /^\d+$/.test(fromUrl)) {
      sessionStorage.setItem(DEV_USER_KEY, fromUrl);
      return fromUrl;
    }
    return sessionStorage.getItem(DEV_USER_KEY) ?? DEV_FALLBACK_USER_ID;
  } catch {
    return DEV_FALLBACK_USER_ID;
  }
}

export function getAuthHeader(): string {
  const initData = getWebApp()?.initData;
  if (initData) {
    return `MaxInitData ${initData}`;
  }
  if (import.meta.env.DEV) {
    return `Dev ${devUserId()}`;
  }
  throw new ApiError(401, {
    code: 'INIT_DATA_INVALID',
    message: 'Не удалось подтвердить вход через MAX',
  });
}
