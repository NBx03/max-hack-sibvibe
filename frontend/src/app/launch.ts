// Запуски мини-приложения: какой запуск текущий и отработал ли уже его startParam.
//
// Почему это отдельный модуль. Кнопка «Открыть документ» в уведомлении бота открывает
// мини-приложение с startapp=d_<id>. Если приложение уже было открыто на другом экране,
// MAX не запускает его с нуля: либо перезагружает страницу по текущему пути, либо только
// меняет хэш адреса с новыми данными запуска (WebAppData). Раньше в обоих случаях startParam
// игнорировался: он срабатывал лишь на пути «/», а мост MAX читает данные запуска из хэша
// один раз, при загрузке страницы. Человек нажимал кнопку и оставался на прежнем экране.
//
// Теперь «новый запуск» определяется по самим данным запуска (initData уникальна для каждого
// открытия), а смена хэша перезагружает страницу — мост перечитает свежие данные.

import { getWebApp } from '../max/webApp';

const HANDLED_LAUNCH_KEY = 'approval.handledLaunch';
const WEB_APP_DATA_PARAM = 'WebAppData';

/** Короткий отпечаток строки: в sessionStorage не кладём саму подписанную initData. */
function fingerprint(value: string): string {
  let hash = 5381;
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash << 5) + hash + value.charCodeAt(index)) | 0;
  }
  return `${value.length}:${hash >>> 0}`;
}

function currentLaunchId(): string {
  return fingerprint(getWebApp()?.initData ?? '');
}

function readStorage(): string | null {
  try {
    return sessionStorage.getItem(HANDLED_LAUNCH_KEY);
  } catch {
    return null;
  }
}

/** startParam этого запуска уже отработал — повторно по нему не переходим. */
export function isLaunchHandled(): boolean {
  return readStorage() === currentLaunchId();
}

export function markLaunchHandled(): void {
  try {
    sessionStorage.setItem(HANDLED_LAUNCH_KEY, currentLaunchId());
  } catch {
    // Без sessionStorage (приватный режим) startParam отработает и после перезагрузки —
    // это безопасно: доступ к документу всё равно проверяет бэкенд.
  }
}

/** Данные запуска из хэша адреса — в том виде, в каком их туда кладёт MAX. */
export function launchDataFromHash(hash: string): string | null {
  try {
    return new URLSearchParams(hash.replace(/^#/, '')).get(WEB_APP_DATA_PARAM);
  } catch {
    return null;
  }
}

const RELOADED_FOR_KEY = 'approval.reloadedFor';

/**
 * Данные запуска из хэша, ради которых стоит перезагрузить страницу, или null. Только внутри MAX (есть мост) и
 * не больше одного раза для одной и той же строки: если мост и хэш по-разному декодируют «+» или «%XX» и строки
 * никогда не совпадут, иначе каждая загрузка вызывала бы новую — приложение не открылось бы вовсе.
 */
export function launchDataToReloadFor(hash: string, bridgeInitData: string | undefined, reloadedFor: string | null): string | null {
  if (bridgeInitData === undefined) return null;
  const next = launchDataFromHash(hash);
  if (!next || next === bridgeInitData) return null;
  // Любая новая строка в хэше — новый запуск: MAX кладёт её туда, только когда приложение открывают заново, в том числе
  // по той же ссылке или по кнопке бота без параметра («Заявка одобрена»). Мост при этом новых данных сам не прочитает:
  // initData он берёт из хэша один раз, при загрузке (сверено с st.max.ru/js/max-web-app.js 26.09).
  const mark = fingerprint(next);
  return mark === reloadedFor ? null : mark;
}

function readReloadedFor(): string | null {
  try {
    return sessionStorage.getItem(RELOADED_FOR_KEY);
  } catch {
    // Без sessionStorage не помним, что уже перезагружали, — поэтому и не перезагружаем.
    return 'unknown';
  }
}

/**
 * Следит за повторным открытием уже открытого мини-приложения. Если MAX поменял
 * только хэш (новые данные запуска без перезагрузки), перезагружаем страницу: мост MAX читает initData
 * лишь при загрузке, и без перезагрузки все запросы ушли бы со старыми данными запуска, а переход
 * по ссылке из уведомления не случился бы. Сам мост новых данных не прочитает — initData он берёт из хэша один раз,
 * при загрузке, поэтому признак нового запуска — только хэш. Проверяем не только hashchange: смену адреса фрейма
 * браузер может сообщить и как popstate, а возвращение к открытому приложению — как focus или
 * visibilitychange.
 *
 * Как именно MAX передаёт повторное открытие, в документации моста не описано (сверено с
 * dev.max.ru/docs/webapps/bridge 24.09). Поэтому сообщения моста и смена хэша пишутся в консоль
 * с меткой [approval] (уровень debug): так поведение конкретного клиента MAX можно проверить.
 */
export function watchRelaunch(): void {
  const check = (reason: string) => {
    const webApp = getWebApp();
    const mark = launchDataToReloadFor(window.location.hash, webApp ? webApp.initData ?? '' : undefined, readReloadedFor());
    if (mark === null) return;
    try {
      sessionStorage.setItem(RELOADED_FOR_KEY, mark);
    } catch {
      return;
    }
    console.debug('[approval] новый запуск мини-приложения:', reason, '(хэш)');
    window.location.reload();
  };
  window.addEventListener('hashchange', () => check('hashchange'));
  window.addEventListener('popstate', () => check('popstate'));
  window.addEventListener('pageshow', () => check('pageshow'));
  window.addEventListener('focus', () => check('focus'));
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') check('visibilitychange');
  });
  window.addEventListener('message', (event) => {
    if (typeof event.data === 'string' && event.data.includes('"type":"WebApp')) {
      console.debug('[approval] сообщение моста MAX:', event.data.slice(0, 120));
    }
  });
}

/**
 * Диагностика диплинков: дошёл ли параметр ссылки до моста и до бэкенда. Сам параметр не пишем — токен
 * личной ссылки секретный; только вид (c_, p_, d_). Смотреть в консоли веб-версии MAX, строки [approval].
 */
export function logLaunch(meStartParam: string | null): void {
  const bridgeParam = getWebApp()?.initDataUnsafe?.start_param ?? null;
  // Только наши виды; у чужого параметра не печатаем даже начало.
  const kind = (value: string | null) => {
    if (!value) return 'нет';
    const prefix = value.slice(0, 2);
    return ['c_', 'p_', 'd_', 's_', 'a_'].includes(prefix) ? prefix : 'другой';
  };
  console.debug('[approval] запуск: параметр в мосте —', kind(bridgeParam), '; в /me —', kind(meStartParam),
    '; уже обработан —', isLaunchHandled());
}
