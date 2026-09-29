/**
 * Минимальный интерфейс window.WebApp, который реально используется в проекте.
 * Библиотека подключается скриптом в index.html и создаёт этот объект сама -
 * никакой отдельной инициализации не требует (сверено с dev.max.ru/docs/webapps/bridge).
 */
/** Системная кнопка "назад" - есть только внутри MAX, вне его недоступна (routes/useMaxBackButton.ts). */
export interface MaxBackButton {
  show(): void;
  hide(): void;
  onClick(handler: () => void): void;
  offClick(handler: () => void): void;
}

export interface MaxWebApp {
  /** Подписанная, но не зашифрованная строка стартовых данных. Фронтенд её не разбирает,
   *  а целиком пересылает на бэкенд в заголовке Authorization. */
  initData: string;
  initDataUnsafe: {
    /** Значение из ссылки ?startapp=... Использовать можно только то, что вернул
     *  бэкенд в GET /me.startParam - оно разобрано из проверенной initData. */
    start_param?: string;
  };
  /** Нативное скачивание: работает только внутри MAX, требует нажатия пользователя. */
  downloadFile(url: string, fileName: string): void;
  /**
   * Открывает экран шеринга внутри MAX - переслать в чат MAX.
   * Сверено с dev.max.ru/docs/webapps/bridge: то, что нужно для «Поделиться» кодом
   * компании или личной ссылкой в рабочий чат.
   */
  shareMaxContent?(payload: { text?: string; link?: string }): void;
  /** Нативный экран шеринга ОС - iOS и Android; в веб-версии MAX не поддерживается. */
  shareContent?(payload: { text?: string; link?: string }): void;
  BackButton: MaxBackButton;
  /** Доступная область мини-приложения, строки в пикселях (dev.max.ru/docs/webapps/bridge). */
  getViewportSize?(): Promise<{ height: string; width: string }> | { height: string; width: string };
}

declare global {
  interface Window {
    WebApp?: MaxWebApp;
  }
}

export function getWebApp(): MaxWebApp | undefined {
  return window.WebApp;
}

/** Мини-приложение открыто внутри MAX и получило initData от моста. */
export function isInsideMax(): boolean {
  return Boolean(window.WebApp?.initData);
}

/**
 * Скачивание файла - нативно внутри MAX, иначе обычная вкладка (docs/API_CONTRACTS.md).
 * Проверяем isInsideMax (есть initData), а не просто наличие window.WebApp - скрипт
 * моста грузится в index.html безусловно, поэтому объект существует и в обычном
 * браузере вне MAX, где downloadFile у него ничего реально не сделает.
 */
export function downloadFile(url: string, fileName: string): void {
  if (isInsideMax()) {
    window.WebApp?.downloadFile(url, fileName);
  } else {
    window.open(url, '_blank');
  }
}
