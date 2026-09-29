import { AiLegend } from './Badge';
import { Section } from './Page';

/**
 * Блок «Кратко от ИИ» на карточке документа — над «Файлами». Отдельный вызов модели, не то же
 * извлечение, что поля. Пояснение про значок ✦ — та же легенда, что и у остальных значений от ИИ
 *: отдельной иконки ⓘ здесь не заводим, чтобы не плодить два способа узнать одно и то же.
 */
export function AiSummaryCard({ summary, isAuthor }: { summary: string; isAuthor?: boolean }) {
  return (
    <Section title="Кратко от ИИ">
      <AiLegend />
      <p style={{ margin: 0 }}>{summary}</p>
      <p className="ds-text-secondary" style={{ margin: 0 }}>
        {isAuthor
          ? 'Составлено ИИ по тексту документа. Сверьте с документом.'
          : 'Составлено ИИ по тексту документа. Решение принимаете вы.'}
      </p>
    </Section>
  );
}
