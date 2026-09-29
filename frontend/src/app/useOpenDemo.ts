import { useState } from 'react';
import { errorMessage } from '../api/errors';
import { useSession } from './SessionContext';
import { demoEntryUser } from './demo';

/**
 * Вход в демо-компанию — один для экрана приветствия и раздела «Компания». Уже созданную
 * песочницу не пересоздаём: проверяющий вернётся к своим документам. Входим сразу от имени
 * автора — у него основное действие сценария.
 */
export function useOpenDemo() {
  const { me, sandboxUsers, startSandbox, actAs } = useSession();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // SessionContext загружает участников песочницы до перехода в ready, поэтому
  // me.sandbox — надёжный признак: песочница есть, её нельзя пересоздавать.
  const hasSandbox = Boolean(me?.sandbox);

  async function open() {
    setBusy(true);
    setError(null);
    try {
      const users = hasSandbox ? (sandboxUsers ?? []) : await startSandbox();
      const entry = demoEntryUser(users);
      if (entry === null) {
        setError('Не удалось открыть демо-компанию, попробуйте ещё раз');
        return;
      }
      await actAs(entry);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return { open, busy, error, hasSandbox };
}
