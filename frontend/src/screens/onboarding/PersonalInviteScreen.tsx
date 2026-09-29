import { useCallback, useEffect, useState } from 'react';
import { Button } from '../../ui';
import { Spinner, Typography } from '@maxhub/max-ui';
import { useSession } from '../../app/SessionContext';
import { acceptPersonalInvite, previewPersonalInvite } from '../../api/onboarding';
import { ApiError, errorMessage } from '../../api/errors';
import type { PersonalInvitePreview } from '../../api/types';
import { isInviteGone } from './inviteErrors';
import { ErrorText, Hint, OnboardingLayout } from './OnboardingLayout';

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; invite: PersonalInvitePreview }
  | { kind: 'invalid'; message: string }
  // Временный сбой (сеть, 5xx, тайм-аут): ссылка может быть в порядке — нужен «Повторить».
  | { kind: 'error'; message: string };

/**
 * 4б. Вступление по личной ссылке (docs/SCREENS.md). При открытии ничего не происходит:
 * человек видит компанию и роли и вступает только по кнопке (docs/DESIGN-DECISIONS.md, онбординг).
 */
export function PersonalInviteScreen({
  token,
  onJoinByCode,
  onDecline,
}: {
  token: string;
  onJoinByCode: () => void;
  /** «Не сейчас» — по ссылке основной путь, и без выхода экран был бы тупиком. */
  onDecline: () => void;
}) {
  const { refresh } = useSession();
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setState({ kind: 'loading' });
    try {
      setState({ kind: 'ready', invite: await previewPersonalInvite(token) });
    } catch (err) {
      // Использована, просрочена, отозвана или не найдена — текст бэкенда объясняет, что именно.
      setState({ kind: isInviteGone(err) ? 'invalid' : 'error', message: errorMessage(err) });
    }
  }, [token]);

  useEffect(() => {
    void load();
  }, [load]);

  const join = async () => {
    setBusy(true);
    setError(null);
    try {
      await acceptPersonalInvite(token);
      // Ответ accept — уже MeResponse, но refresh всё равно нужен: он же подгружает
      // песочницу и остаётся единственным местом, где сессия меняет состояние.
      await refresh();
    } catch (err) {
      if (isInviteGone(err)) {
        // Ссылку успели использовать, отозвать или она истекла, пока экран был открыт.
        setState({ kind: 'invalid', message: errorMessage(err) });
      } else if (err instanceof ApiError && err.code === 'ALREADY_MEMBER') {
        // Уже состоит в компании — актуальный /me знает, какой экран показать.
        await refresh();
        return;
      } else {
        setError(errorMessage(err));
      }
      setBusy(false);
    }
  };

  if (state.kind === 'loading') {
    return (
      <OnboardingLayout title="Приглашение">
        <Spinner />
      </OnboardingLayout>
    );
  }

  if (state.kind === 'error') {
    return (
      <OnboardingLayout
        title="Приглашение"
        footer={
          <Button variant="primary" stretched onClick={() => void load()}>
            Повторить
          </Button>
        }
      >
        <ErrorText>{state.message}</ErrorText>
      </OnboardingLayout>
    );
  }

  if (state.kind === 'invalid') {
    return (
      <OnboardingLayout
        title="Ссылка недействительна"
        footer={
          <>
            <Button variant="primary" stretched onClick={onJoinByCode}>
              Присоединиться по коду
            </Button>
            {/* Без выхода экран был тупиком: ни приветствия, ни демонстрации. */}
            <Button variant="ghost" stretched onClick={onDecline}>
              На главную
            </Button>
          </>
        }
      >
        <ErrorText>{state.message}</ErrorText>
        <Hint>Попросите у администратора новую ссылку или код компании.</Hint>
      </OnboardingLayout>
    );
  }

  const { invite } = state;
  const expires = new Date(invite.expiresAt);
  return (
    <OnboardingLayout
      title={invite.orgName}
      subtitle="Вас пригласили в компанию. После вступления вы получите роли:"
      footer={
        <>
          <Button variant="primary" stretched loading={busy} onClick={() => void join()}>
            Вступить
          </Button>
          <Button variant="ghost" stretched disabled={busy} onClick={onDecline}>
            Не сейчас
          </Button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {invite.roles.map((role) => (
          <Typography.Body key={role.id} variant="large-strong">
            {role.name}
          </Typography.Body>
        ))}
      </div>
      {!Number.isNaN(expires.getTime()) && (
        <Hint>
          Ссылка действует до{' '}
          {expires.toLocaleString('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' })}
        </Hint>
      )}
      {error && <ErrorText>{error}</ErrorText>}
    </OnboardingLayout>
  );
}
