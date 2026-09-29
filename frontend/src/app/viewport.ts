import { getWebApp } from '../max/webApp';

/**
 * Высота приложения = видимая область мини-приложения. В веб-версии MAX окно мини-приложения
 * бывает выше видимой части, и при высоте 100vh закреплённые кнопки уходили за край, а прокручивалось
 * окно самого MAX. MAX Bridge сообщает доступную область через getViewportSize
 * (dev.max.ru/docs/webapps/bridge) — берём меньшее из неё и window.innerHeight.
 */
export function watchViewportHeight(): void {
  let bridgeHeight: number | null = null;

  const apply = () => {
    const candidates = [window.innerHeight, bridgeHeight].filter((value): value is number => Boolean(value && value > 0));
    const height = Math.min(...candidates);
    if (Number.isFinite(height)) {
      document.documentElement.style.setProperty('--app-height', `${Math.round(height)}px`);
    }
  };

  const askBridge = () => {
    const webApp = getWebApp();
    if (!webApp?.initData || typeof webApp.getViewportSize !== 'function') return;
    Promise.resolve(webApp.getViewportSize())
      .then((size) => {
        const parsed = Number.parseFloat(String(size?.height ?? ''));
        bridgeHeight = Number.isFinite(parsed) && parsed > 0 ? parsed : null;
        apply();
      })
      .catch(() => undefined);
  };

  apply();
  askBridge();
  window.addEventListener('resize', () => {
    apply();
    askBridge();
  });
}
