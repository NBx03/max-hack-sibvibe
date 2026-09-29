import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { getWebApp, isInsideMax, type MaxBackButton } from '../max/webApp';
import { backTarget, useGoBack } from './backNavigation';
import { ROOT_ROUTE_PATTERNS } from './paths';

// isInsideMax, не просто наличие window.WebApp - тот же признак, что уже использует
// downloadFile (max/webApp.ts): скрипт моста грузится в index.html безусловно, поэтому
// объект существует и в обычном браузере вне MAX, где его методы ничего не делают.
function activeBackButton(): MaxBackButton | null {
  return isInsideMax() ? (getWebApp()?.BackButton ?? null) : null;
}

// Системная BackButton моста (show/hide/onClick/offClick - dev.max.ru/docs/webapps/bridge),
// один хук в каркасе вместо ручной проводки в каждом экране. Куда вести -
// та же логика, что у ссылок «‹ …» в шапках экранов (routes/backNavigation.ts).
export function useMaxBackButton(entryRoute: string): void {
  const location = useLocation();
  const goBack = useGoBack();

  // Последний путь, его state и entryRoute - в ref, чтобы не переподписывать onClick моста
  // на каждый переход (тот же приём, что onDismissRef в ui/State.tsx, Toast).
  const latest = useRef({ pathname: location.pathname, state: location.state as unknown, entryRoute, goBack });
  latest.current = { pathname: location.pathname, state: location.state as unknown, entryRoute, goBack };

  useEffect(() => {
    const backButton = activeBackButton();
    if (!backButton) {
      return;
    }
    if (ROOT_ROUTE_PATTERNS.has(location.pathname)) {
      backButton.hide();
    } else {
      backButton.show();
    }
  }, [location.pathname]);

  useEffect(() => {
    const backButton = activeBackButton();
    if (!backButton) {
      return;
    }
    function handleClick() {
      const { pathname, state, entryRoute, goBack } = latest.current;
      goBack(backTarget(pathname, state, entryRoute));
    }
    backButton.onClick(handleClick);
    return () => backButton.offClick(handleClick);
  }, []);
}
