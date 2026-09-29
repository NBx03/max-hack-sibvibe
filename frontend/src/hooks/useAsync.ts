import { useCallback, useEffect, useMemo, useState } from 'react';

// Общий загрузка/данные/ошибка для экранов документов - docs/SCREENS.md,
// "Четыре состояния у каждого экрана с данными". Хранит "сырую" ошибку (не готовый
// текст), чтобы экран сам мог отличить 403/404 документа (ApiError.status) от
// обычного сбоя и показать AccessErrorState вместо общей ErrorState.

export type AsyncState<T> =
  | { status: 'loading' }
  | { status: 'error'; error: unknown }
  | { status: 'ready'; data: T };

export function useAsync<T>(load: () => Promise<T>, deps: unknown[]): AsyncState<T> & { refresh: () => void } {
  const [state, setState] = useState<AsyncState<T>>({ status: 'loading' });
  const [tick, setTick] = useState(0);
  const refresh = useCallback(() => setTick((value) => value + 1), []);

  useEffect(() => {
    let cancelled = false;
    setState({ status: 'loading' });
    load()
      .then((data) => {
        if (!cancelled) {
          setState({ status: 'ready', data });
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setState({ status: 'error', error: err });
        }
      });
    return () => {
      cancelled = true;
    };
    // deps - явный список зависимостей запроса от вызывающего экрана (id документа,
    // фильтры и т.п.), tick - принудительный повтор по "Повторить"/refresh.
  }, [...deps, tick]);

  // {...state, refresh} - новый объект на каждом рендере, если не завернуть в useMemo:
  // экран, использующий результат как зависимость useEffect/useMemo, увидел бы "изменение"
  // при любом чужом ре-рендере компонента. Если такой эффект сам вызывает setState -
  // это бесконечный цикл рендеров, а не просто лишняя работа.
  return useMemo(() => ({ ...state, refresh }), [state, refresh]);
}
