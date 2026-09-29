import { Typography } from '@maxhub/max-ui';
import { Button } from '../../ui';
import { useSession } from '../../app/SessionContext';
import { useOpenDemo } from '../../app/useOpenDemo';
import { ErrorText, OnboardingLayout } from './OnboardingLayout';

const FEATURES = [
  {
    title: 'Проверка до отправки',
    text: 'Ошибки оформления находятся до отправки, а не у последнего согласующего.',
  },
  {
    title: 'Понятный маршрут',
    text: 'Перед отправкой видно, кто и в каком порядке согласует — с именами.',
  },
  {
    title: 'Уведомления в MAX',
    text: 'Сообщение приходит, когда подошла ваша очередь или принято решение.',
  },
];

/**
 * 1. Приветствие (docs/SCREENS.md): что это за сервис и два пути — создать компанию или вступить
 * по коду. Демонстрация — третьим, негромким действием: она для знакомства с продуктом (почему:
 * экран был пустым, текст мелким, а про демо говорилось так, будто это режим для всех).
 */
export function WelcomeScreen({ onCreate, onJoin }: { onCreate: () => void; onJoin: () => void }) {
  const { me } = useSession();
  const demo = useOpenDemo();

  return (
    <OnboardingLayout
      title="Согласование документов"
      subtitle="Готовьте, проверяйте и согласовывайте документы компании прямо в MAX."
      footer={
        <>
          <Button variant="primary" stretched onClick={onCreate}>
            Создать компанию
          </Button>
          {/* Демонстрация — второй: так проверяющий проходит весь сценарий один и не пропустит кнопку внизу.
              Одно название всегда: «Продолжить» ставило в тупик того, кто открыл приложение впервые. */}
          {me?.demoMode && (
            <Button variant="secondary" stretched loading={demo.busy} onClick={() => void demo.open()}>
              Посмотреть демонстрацию
            </Button>
          )}
          {demo.error && <ErrorText>{demo.error}</ErrorText>}
          <Button variant={me?.demoMode ? 'ghost' : 'secondary'} stretched onClick={onJoin}>
            Присоединиться по коду
          </Button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16, marginTop: 8 }}>
        {FEATURES.map((feature, index) => (
          <div key={feature.title} style={{ display: 'flex', gap: 14, alignItems: 'flex-start' }}>
            <span
              aria-hidden
              style={{
                flex: 'none',
                width: 32,
                height: 32,
                borderRadius: 16,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                background: 'var(--background-secondary)',
                border: '1px solid var(--divider-primary)',
                fontWeight: 700,
                fontSize: 15,
              }}
            >
              {index + 1}
            </span>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <Typography.Headline variant="small">{feature.title}</Typography.Headline>
              <span style={{ fontSize: 15, lineHeight: '21px', color: 'var(--text-secondary)' }}>{feature.text}</span>
            </div>
          </div>
        ))}
      </div>
      {me?.demoMode && (
        <span style={{ fontSize: 14, lineHeight: '20px', color: 'var(--text-tertiary)', marginTop: 'auto' }}>
          Демонстрация — компания с вымышленными сотрудниками, чтобы попробовать сервис.
          {demo.hasSandbox && ' Ваши действия в ней сохраняются.'}
        </span>
      )}
    </OnboardingLayout>
  );
}
