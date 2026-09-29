import { BrowserRouter } from 'react-router-dom';
import { useSession } from './app/SessionContext';
import { actorKey } from './app/demo';
import { GENERIC_ERROR_MESSAGE } from './api/errors';
import { DemoSwitcher } from './components/base/DemoSwitcher';
import { ErrorState, Loading, Page } from './ui';
import { AppRoutes } from './routes/AppRoutes';

/**
 * Каркас: подключение к MAX и бэкенду и общие компоненты сделаны,
 * сами экраны - отдельные задачи. Ниже - переход к нужному экрану
 * по результату GET /me и демо-переключатель.
 */
export function App() {
  const { status, me, error, refresh } = useSession();

  if (status === 'loading') {
    return (
      <Page center>
        <Loading />
      </Page>
    );
  }

  if (status === 'error' || !me) {
    return (
      <Page center>
        <ErrorState
          message={error?.message ?? GENERIC_ERROR_MESSAGE}
          onRetry={() => void refresh()}
        />
      </Page>
    );
  }

  // Ключ - от чьего имени идёт работа (actorKey): при смене участника демо (тихое обновление /me, без
  // общего спиннера) экраны пересоздаются и заново загружают свои данные уже для него.
  return (
    <BrowserRouter>
      <DemoSwitcher />
      {/* Экран занимает остаток высоты под плашкой демо и прокручивается сам (Layout.ScreenShell). */}
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        <AppRoutes key={actorKey(me)} me={me} />
      </div>
    </BrowserRouter>
  );
}
