import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MaxUI } from '@maxhub/max-ui';
import '@maxhub/max-ui/styles.css';
import './theme/tokens.css';
import { ThemeSync } from './ui';

import { App } from './App';
import { SessionProvider } from './app/SessionContext';
import { watchRelaunch } from './app/launch';
import { watchViewportHeight } from './app/viewport';

// Повторное открытие из бота при уже открытом мини-приложении (app/launch.ts).
watchRelaunch();
// Высота приложения — по видимой области MAX, а не 100vh (app/viewport.ts).
watchViewportHeight();

const container = document.getElementById('root');
if (!container) {
  throw new Error('Не найден корневой элемент #root');
}

createRoot(container).render(
  <StrictMode>
    <MaxUI resetBody className="app-root">
      <ThemeSync />
      {/* Рамка приложения: на широком экране всё — шапка демо, экран и закреплённые кнопки — одной колонкой (theme/tokens.css). */}
      <div className="app-frame">
        <SessionProvider>
          <App />
        </SessionProvider>
      </div>
    </MaxUI>
  </StrictMode>,
);
