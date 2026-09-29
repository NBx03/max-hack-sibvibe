import { useState, type FormEvent } from 'react';
import { Button, SelectField } from '../../ui';
import { defaultCity, locationOf, zoneChoice, zoneOptions } from '../../app/companyTime';
import { Input, Typography } from '@maxhub/max-ui';
import { useSession } from '../../app/SessionContext';
import { createCompany } from '../../api/onboarding';
import { errorMessage } from '../../api/errors';
import { ErrorText, Hint, OnboardingLayout } from './OnboardingLayout';

// Лимиты — как в CreateOrganizationRequest на бэкенде. ИНН по SCREENS.md не проверяется.
const NAME_MAX = 255;
const INN_MAX = 255;

/** 2. Создание компании (docs/SCREENS.md). */
export function CreateCompanyScreen({ onBack }: { onBack: () => void }) {
  const { refresh } = useSession();
  const [name, setName] = useState('');
  const [inn, setInn] = useState('');
  // Часовой пояс — сразу по телефону: «сегодня» у компании считается по нему. Выбирается пояс, а не город
  // Города компании может не быть в списке, а пояс есть у любого.
  const [zone, setZone] = useState(() => zoneChoice(defaultCity().timeZone));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const trimmedName = name.trim();
  const trimmedInn = inn.trim();
  const canSubmit = trimmedName.length > 0 && !busy;

  const submit = async (event?: FormEvent) => {
    event?.preventDefault();
    if (!canSubmit) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const location = locationOf(zone);
      await createCompany(trimmedName, trimmedInn === '' ? null : trimmedInn, location.city, location.timeZone);
      // После создания человек — администратор своей компании: роутер по свежему /me
      // сразу откроет «3. Приглашение» (AppRoutes, guardTarget).
      await refresh();
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  };

  return (
    <OnboardingLayout
      title="Создание компании"
      subtitle="Вы станете администратором и получите роль «Директор». Стандартные роли и маршруты согласования появятся сразу."
      footer={
        <>
          <Button variant="primary" stretched disabled={!canSubmit} loading={busy} onClick={() => void submit()}>
            Создать
          </Button>
          <Button variant="ghost" stretched disabled={busy} onClick={onBack}>
            Назад
          </Button>
        </>
      }
    >
      <form onSubmit={(event) => void submit(event)} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <Typography.Label variant="medium-strong">Название компании</Typography.Label>
          <Input
            value={name}
            maxLength={NAME_MAX}
            placeholder="ООО «Ромашка»"
            autoFocus
            onChange={(event) => setName(event.target.value)}
          />
        </label>
        {/* Подпись — как у соседних полей: SelectField со своей подписью выглядел бы другим шрифтом. */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <Typography.Label variant="medium-strong">Часовой пояс</Typography.Label>
          <SelectField
          placeholder="Выберите часовой пояс"
          sheetTitle="Часовой пояс компании"
          options={zoneOptions()}
          value={zone}
          onChange={setZone}
          />
        </div>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <Typography.Label variant="medium-strong">ИНН</Typography.Label>
          <Input
            value={inn}
            inputMode="numeric"
            maxLength={INN_MAX}
            placeholder="Необязательно"
            onChange={(event) => setInn(event.target.value)}
          />
          <Hint>Необязательно, не проверяется.</Hint>
        </label>
        {/* Отправка по Enter с клавиатуры телефона. */}
        <button type="submit" hidden aria-hidden />
      </form>
      {error && <ErrorText>{error}</ErrorText>}
    </OnboardingLayout>
  );
}
