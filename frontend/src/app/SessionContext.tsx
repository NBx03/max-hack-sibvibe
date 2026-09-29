import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { getActingAsId, setActingAsId } from '../api/client';
import { ApiError, GENERIC_ERROR_MESSAGE } from '../api/errors';
import { createDemoSandbox, getDemoSandbox, getMe } from '../api/session';
import { demoActorToResume } from './demo';
import { deviceZone } from './companyTime';
import type { DemoSandboxUser, MeResponse } from '../api/types';

type SessionStatus = 'loading' | 'ready' | 'error';

interface SessionValue {
  status: SessionStatus;
  me: MeResponse | null;
  error: ApiError | null;
  /** null - демо-режим выключен или песочница ещё не создана. */
  sandboxUsers: DemoSandboxUser[] | null;
  refresh: () => Promise<void>;
  /** Перечитать /me без общего спиннера — экран остаётся на месте, пока ответ не изменит маршрут. */
  refreshQuietly: () => Promise<void>;
  /** id участника своей песочницы, или null, чтобы выйти из демо. */
  actAs: (userId: number | null) => Promise<void>;
  /**
   * Создаёт песочницу или пересоздаёт её ("Начать заново"). Возвращает её участников,
   * чтобы сразу войти от имени одного из них; null - не получилось, ошибка уже в `error`.
   */
  startSandbox: () => Promise<DemoSandboxUser[]>;
}

const SessionContext = createContext<SessionValue | null>(null);

function toApiError(err: unknown): ApiError {
  if (err instanceof ApiError) {
    return err;
  }
  return new ApiError(0, {
    code: 'UNKNOWN',
    message: GENERIC_ERROR_MESSAGE,
  });
}

/**
 * X-Demo-Act-As мог протухнуть без нашего ведома - песочницу пересоздали
 * в другой вкладке или бэкенд перезапустили. Без восстановления пользователь
 * залипает: 403 DEMO_ACT_AS_FORBIDDEN на каждый /me, а "Повторить" шлёт тот
 * же протухший заголовок снова, и выйти из демо нечем - DemoSwitcher не
 * рендерится на экране ошибки. Поэтому один раз сбрасываем заголовок и
 * повторяем запрос от имени реальной компании / экрана приветствия.
 */
async function getMeWithDemoRecovery(): Promise<MeResponse> {
  try {
    return await getMe();
  } catch (err) {
    if (err instanceof ApiError && err.code === 'DEMO_ACT_AS_FORBIDDEN') {
      setActingAsId(null);
      return await getMe();
    }
    throw err;
  }
}

/**
 * Демонстрация запоминается только у того, у кого нет своей компании (жюри): после закрытия
 * мини-приложения он возвращается туда же, а не на экран приветствия. У сотрудника
 * настоящей компании приложение открывается в его компании: уведомления бота ведут к её документам.
 */
const DEMO_ACTOR_KEY = 'approval.demoActor.';

function readDemoActor(userId: number): number | null {
  try {
    const value = Number(window.localStorage.getItem(DEMO_ACTOR_KEY + userId));
    return Number.isSafeInteger(value) && value > 0 ? value : null;
  } catch {
    return null;
  }
}

function writeDemoActor(userId: number, actorId: number | null): void {
  try {
    if (actorId === null) window.localStorage.removeItem(DEMO_ACTOR_KEY + userId);
    else window.localStorage.setItem(DEMO_ACTOR_KEY + userId, String(actorId));
  } catch {
    // Хранилище недоступно (приватный режим) — просто не запоминаем.
  }
}

/**
 * Только dev-сборка: ?demoActor=<id> в адресе открывает демо от имени этого участника — для скриншотов
 * headless-браузером на разных ширинах. В прод-сборке ветка вырезается.
 */
function devDemoActor(): number | null {
  if (!import.meta.env.DEV) return null;
  const value = Number(new URLSearchParams(window.location.search).get('demoActor'));
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SessionStatus>('loading');
  const [me, setMe] = useState<MeResponse | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [sandboxUsers, setSandboxUsers] = useState<DemoSandboxUser[] | null>(null);
  // Автовозврат в демонстрацию — один раз за открытие, иначе «Выйти из демонстрации» не работала бы.
  const resumeTried = useRef(false);

  // silent - обновить /me, не показывая общий спиннер: при смене участника демо экран
  // не должен исчезать вместе с плашкой «Демо». Экраны при этом всё равно
  // перезагружают свои данные: App пересоздаёт роутер по ключу участника.
  //
  // Загрузки идут строго по очереди: иначе вторая (StrictMode в dev, повторное нажатие «Повторить») успевала
  // спросить /me раньше, чем первая восстановила участника демо, получала ответ «без компании», и охранник
  // уводил с открытого по ссылке экрана на главную.
  const queue = useRef<Promise<void>>(Promise.resolve());
  const load = useCallback((silent: boolean) => {
    const run = queue.current.then(() => loadNow(silent));
    queue.current = run;
    return run;
    // loadNow не зависит от состояния — только от стабильных сеттеров.
  }, []);

  const loadNow = async (silent: boolean) => {
    if (!silent) {
      setStatus('loading');
    }
    setError(null);
    try {
      let response = await getMeWithDemoRecovery();
      if (!resumeTried.current) {
        resumeTried.current = true;
        const saved = demoActorToResume(response, devDemoActor() ?? readDemoActor(response.user.id));
        if (saved !== null) {
          setActingAsId(saved);
          // Песочницу пересоздали или участника нет — getMeWithDemoRecovery вернёт обычный вход.
          response = await getMeWithDemoRecovery();
          if (!response.actingAs) {
            // Запомненный участник больше недействителен — не пробуем его при каждом открытии.
            writeDemoActor(response.user.id, null);
          }
        }
      }
      // Участники существующей песочницы загружаются до перехода в ready: иначе экран
      // приветствия успел бы увидеть sandboxUsers = null, решить, что песочницы нет,
      // и кнопка «Открыть демо» пересоздала бы её, стерев прогресс проверяющего.
      // Сбой этой загрузки — общая ошибка с «Повторить», а не «песочницы нет».
      const users = response.sandbox ? (await getDemoSandbox()).users : null;
      setMe(response);
      setSandboxUsers(users);
      setStatus('ready');
    } catch (err) {
      setError(toApiError(err));
      setStatus('error');
    }
  };

  const refresh = useCallback(() => load(false), [load]);
  const refreshQuietly = useCallback(() => load(true), [load]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const actAs = useCallback(
    async (userId: number | null) => {
      setActingAsId(userId);
      await load(true);
      if (me) writeDemoActor(me.user.id, userId);
    },
    [load, me],
  );

  // Ошибку создания бросает вызывающему: экран приветствия показывает её у своей кнопки.
  // Двойное нажатие успевает вызвать создание дважды до перерисовки с заблокированной кнопкой:
  // второй вызов получает тот же запрос, а не вторую песочницу.
  const sandboxInFlight = useRef<Promise<DemoSandboxUser[]> | null>(null);
  const startSandbox = useCallback(() => {
    if (sandboxInFlight.current) {
      return sandboxInFlight.current;
    }
    const run = async () => {
      // Прежние id участников после пересоздания недействительны (403) - сбрасываем сразу.
      const previous = getActingAsId();
      setActingAsId(null);
      try {
        // Демо-компания живёт по часам проверяющего: иначе «сегодня» в ней было бы московским.
        const sandbox = await createDemoSandbox(deviceZone());
        await refresh();
        return sandbox.users;
      } catch (err) {
        // Иначе экран показывал бы участника песочницы, а запросы шли бы от реальной учётной
        // записи. Возвращаем прежнего участника и перечитываем /me: если прежняя
        // песочница всё же пропала, getMeWithDemoRecovery сам выведет из демо.
        setActingAsId(previous);
        await load(true);
        throw err;
      } finally {
        sandboxInFlight.current = null;
      }
    };
    sandboxInFlight.current = run();
    return sandboxInFlight.current;
  }, [refresh, load]);

  const value = useMemo<SessionValue>(
    () => ({ status, me, error, sandboxUsers, refresh, refreshQuietly, actAs, startSandbox }),
    [status, me, error, sandboxUsers, refresh, refreshQuietly, actAs, startSandbox],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const ctx = useContext(SessionContext);
  if (!ctx) {
    throw new Error('useSession должен использоваться внутри SessionProvider');
  }
  return ctx;
}
