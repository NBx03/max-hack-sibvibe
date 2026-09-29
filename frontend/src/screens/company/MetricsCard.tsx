import type { OrganizationMetrics } from '../../api/types';
import { Muted, Section } from '../../ui';

function documentsLabel(count: number): string {
  const mod10 = count % 10;
  const mod100 = count % 100;
  if (mod10 === 1 && mod100 !== 11) return `${count} документу`;
  return `${count} документам`;
}

/**
 * Значение — коротко и в одну строку: «меньше часа» переносилось на две строки и сдвигало плитку.
 * Чистая функция — покрыта тестом.
 */
export function formatDuration(hours: number | null): string {
  if (hours === null) return '—';
  if (hours < 1) return '< 1 ч';
  if (hours < 48) return `${Math.round(hours)} ч`;
  return `${Math.round(hours / 24)} дн`;
}

export function formatRate(value: number | null): string {
  return value === null ? '—' : `${Math.round(value * 100)}%`;
}

function MetricTile({ value, label }: { value: string; label: string }) {
  return (
    <div
      style={{
        flex: '1 1 0',
        minWidth: 0,
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'flex-start',
        gap: 4,
        padding: 'var(--ds-space-m)',
        borderRadius: 'var(--ds-radius-m)',
        background: 'var(--ds-surface)',
      }}
    >
      {/* Значение не переносится; на узком экране уменьшается шрифт, а не ломается раскладка. */}
      <span style={{ fontSize: 'clamp(20px, 7vw, 28px)', lineHeight: '34px', fontWeight: 700, whiteSpace: 'nowrap', color: 'var(--text-primary)' }}>
        {value}
      </span>
      <span style={{ fontSize: 'var(--ds-text-xs)', lineHeight: '18px', color: 'var(--text-secondary)' }}>{label}</span>
    </div>
  );
}

/**
 * Показатели согласования в компании — ровно то, что сервис обещает улучшить: срок согласования и долю возвратов.
 * Плитки одной высоты, значение сверху, подпись под ним.
 */
export function MetricsCard({ metrics }: { metrics: OrganizationMetrics }) {
  return (
    <Section title="Показатели согласования">
      <Muted>
        {metrics.documentsCount === 0
          ? 'Появятся, когда в компании отправят первый документ на согласование.'
          : `По ${documentsLabel(metrics.documentsCount)}, отправленным на согласование.`}
      </Muted>
      {metrics.documentsCount > 0 && (
        <div style={{ display: 'flex', alignItems: 'stretch', gap: 'var(--ds-space-xs)' }}>
          <MetricTile value={formatDuration(metrics.medianApprovalHours)} label="обычно от отправки до итогового решения" />
          <MetricTile value={formatRate(metrics.returnRate)} label="документов хотя бы раз вернули на доработку" />
        </div>
      )}
    </Section>
  );
}
