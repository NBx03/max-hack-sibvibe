import { useEffect, useState } from 'react';
import { Button } from '../../ui';
import { Typography } from '@maxhub/max-ui';
import { useSession } from '../../app/SessionContext';
import { cancelJoinRequest } from '../../api/onboarding';
import { ApiError, errorMessage } from '../../api/errors';
import type { PendingJoinRequestRef } from '../../api/types';
import { ErrorText, Hint, OnboardingLayout } from './OnboardingLayout';
import { orgInText } from '../../lib/orgName';

function formatDate(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? ''
    : date.toLocaleString('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });
}

/** 5. Ожидание решения (docs/SCREENS.md). Показывается, пока заявка не решена. */
export function PendingRequestScreen({ request }: { request: PendingJoinRequestRef }) {
  const { refresh, refreshQuietly } = useSession();
  // Вернулись в приложение (например, из чата, где бот написал «Заявка одобрена») — проверяем решение сами, без
  // нажатия «Проверить решение»: так одобрение подхватится при любом поведении MAX.
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refreshQuietly();
    };
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('focus', onVisible);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('focus', onVisible);
    };
  }, [refreshQuietly]);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const cancel = async () => {
    setBusy(true);
    setError(null);
    try {
      await cancelJoinRequest(request.id);
      await refresh();
    } catch (err) {
      // Администратор успел рассмотреть заявку: свежий /me откроет компанию или приветствие.
      if (err instanceof ApiError && err.code === 'INVALID_STATE') {
        await refresh();
        return;
      }
      setError(errorMessage(err));
      setBusy(false);
    }
  };

  const sent = formatDate(request.createdAt);

  return (
    <OnboardingLayout
      title="Заявка отправлена"
      subtitle={`Заявка в компанию ${orgInText(request.orgName)} ждёт решения администратора. Мы сообщим в чате с ботом, когда её рассмотрят.`}
      footer={
        confirming ? (
          <>
            <Typography.Body variant="medium-strong">Отменить заявку в компанию {orgInText(request.orgName)}?</Typography.Body>
            <Button variant="danger" stretched loading={busy} onClick={() => void cancel()}>
              Да, отменить
            </Button>
            <Button variant="ghost" stretched disabled={busy} onClick={() => setConfirming(false)}>
              Нет
            </Button>
          </>
        ) : (
          <>
            <Button variant="secondary" stretched onClick={() => void refresh()}>
              Проверить решение
            </Button>
            <Button variant="ghost" stretched onClick={() => setConfirming(true)}>
              Отменить заявку
            </Button>
          </>
        )
      }
    >
      {sent && <Hint>Отправлена {sent}</Hint>}
      {error && <ErrorText>{error}</ErrorText>}
    </OnboardingLayout>
  );
}
