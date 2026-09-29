import { useState } from 'react';
import { AiGlyph, Icon } from './icons';
import { BottomSheet } from './Sheet';
import {
  documentStatusMeta,
  stepDecisionMeta,
  type AutoReason,
  type DisplayStatus,
  type StatusMeta,
  type StepDecision,
} from './status';

/** Метка тона: приглушённый фон, светлый текст того же оттенка, иконка + слово. */
export function StatusBadge({ meta }: { meta: StatusMeta }) {
  return (
    <span className={`ds-badge ds-badge--${meta.tone}`}>
      <Icon name={meta.icon} size={14} />
      {meta.text}
    </span>
  );
}

export function DocumentStatusBadge({ status }: { status: DisplayStatus }) {
  return <StatusBadge meta={documentStatusMeta(status)} />;
}

export function StepDecisionBadge({ decision, autoReason }: { decision: StepDecision; autoReason?: AutoReason }) {
  return <StatusBadge meta={stepDecisionMeta(decision, autoReason)} />;
}

/** Значение получено от ИИ: маленький ✦ рядом с ним — только там, где это правда, не как декор. */
export function AiMark() {
  return (
    <span className="ds-ai" aria-label="значение от ИИ" role="img">
      <AiGlyph />
    </span>
  );
}

/** Что значит ✦ — пояснение, которое открывает легенда. */
function AiSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <BottomSheet open={open} onClose={onClose} title="✦ — значение от ИИ">
      <p className="ds-text-secondary">
        Так отмечено то, что ИИ нашёл в тексте документа: вид документа, поля, краткое содержание.
      </p>
      <p className="ds-text-secondary">
        ИИ может ошибиться — сверьте с документом. Решения по документу ИИ не принимает: замечания проверяет
        обычный код по правилам компании, а согласуют люди.
      </p>
      <p className="ds-text-secondary">Неверное значение исправьте в поле — проверка пройдёт заново.</p>
    </BottomSheet>
  );
}

/**
 * Легенда под заголовком блока значений от ИИ — сама кнопка: «✦ — заполнено ИИ, проверьте, «Что это?»» акцентным
 * цветом открывает пояснение.
 */
export function AiLegend() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" className="ds-ai-legend" onClick={() => setOpen(true)}>
        <AiMark />
        <span className="ds-ai-legend__text">— заполнено ИИ, проверьте</span>
        <span className="ds-ai-legend__more">
          <Icon name="info" size={16} />
          Что это?
        </span>
      </button>
      <AiSheet open={open} onClose={() => setOpen(false)} />
    </>
  );
}
