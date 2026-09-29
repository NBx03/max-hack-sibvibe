// «Поделиться» и «Скопировать» — экран 3 (SCREENS.md): «Поделиться» (системный шаринг MAX),
// «Скопировать код». Порядок: внутри MAX - shareMaxContent моста (экран
// шеринга внутри MAX, например переслать в рабочий чат); иначе - стандартный Web Share API
// браузера (iOS/Android WebView вне MAX); иначе - копирование в буфер обмена.

import { getWebApp, isInsideMax } from '../max/webApp';

export async function copyToClipboard(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // Продолжаем к запасному варианту ниже - например, вкладка не в фокусе.
    }
  }
  // Запасной вариант для контекстов без Clipboard API (старые WebView, не https).
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.select();
  let copied = false;
  try {
    copied = document.execCommand('copy');
  } catch {
    copied = false;
  } finally {
    document.body.removeChild(textarea);
  }
  return copied;
}

/**
 * @returns 'shared' - открылось системное меню «Поделиться» (или экран шеринга MAX);
 *   'copied' - меню недоступно, ссылка скопирована в буфер; 'failed' - не удалось ни то,
 *   ни другое (текст покажет вызывающий код).
 */
export async function shareOrCopy(url: string, title: string): Promise<'shared' | 'copied' | 'failed'> {
  const webApp = getWebApp();
  if (isInsideMax() && typeof webApp?.shareMaxContent === 'function') {
    try {
      webApp.shareMaxContent({ text: title, link: url });
      return 'shared';
    } catch {
      // Мост есть, но вызов не удался - пробуем следующий вариант, а не показываем ошибку.
    }
  }

  if (navigator.share) {
    try {
      await navigator.share({ title, url });
      return 'shared';
    } catch (error) {
      // Пользователь закрыл системное меню - это не ошибка, просто не показываем тост.
      if (error instanceof DOMException && error.name === 'AbortError') {
        return 'failed';
      }
      // Другая причина (например, share недоступен для этого типа данных) - пробуем копию.
    }
  }
  return (await copyToClipboard(url)) ? 'copied' : 'failed';
}
