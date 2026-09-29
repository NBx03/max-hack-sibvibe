import type { IconName } from './icons';

// Статусы документа и решения по шагу — один словарь тонов на всё приложение: метка документа, точки
// таймлайна и статусы людей берут цвет, иконку и слово отсюда, поэтому одинаковое значение не может получить
// два разных цвета. Цвет тона — токен (ui/tokens.css), не HEX.

export type DocumentStatus = 'DRAFT' | 'IN_APPROVAL' | 'APPROVED' | 'RETURNED' | 'REJECTED';
/** Показываемый статус: вычисляет бэкенд, экраны показывают его, а не status. */
export type DisplayStatus = DocumentStatus | 'IN_ENDORSEMENT' | 'ENDORSED';
export type StepDecision = 'PENDING' | 'APPROVED' | 'RETURNED' | 'REJECTED' | 'SKIPPED';
/**
 * AUTHOR_HOLDS_ROLE — согласовано автоматически: роль только у автора. CARRIED_OVER — одобрение прошлой версии
 * сохранилось: документ не менялся. MEMBER_REMOVED — согласующего исключили или сняли роль, документ вернулся автору.
 */
export type AutoReason = 'AUTHOR_HOLDS_ROLE' | 'CARRIED_OVER' | 'MEMBER_REMOVED' | null;

export type StatusTone = 'neutral' | 'progress' | 'endorse' | 'warning' | 'danger' | 'success';

export interface StatusMeta {
  text: string;
  tone: StatusTone;
  icon: IconName;
}

const DOCUMENT_STATUS_META: Record<DisplayStatus, StatusMeta> = {
  DRAFT: { text: 'Черновик', tone: 'neutral', icon: 'draft' },
  IN_APPROVAL: { text: 'На согласовании', tone: 'progress', icon: 'clock' },
  IN_ENDORSEMENT: { text: 'На утверждении', tone: 'endorse', icon: 'endorse' },
  ENDORSED: { text: 'Утверждён', tone: 'success', icon: 'endorse' },
  RETURNED: { text: 'Возвращён', tone: 'warning', icon: 'return' },
  REJECTED: { text: 'Отклонён', tone: 'danger', icon: 'cross' },
  APPROVED: { text: 'Согласован', tone: 'success', icon: 'check' },
};

const STEP_DECISION_META: Record<StepDecision, StatusMeta> = {
  PENDING: { text: 'Ждёт', tone: 'progress', icon: 'clock' },
  APPROVED: { text: 'Согласовано', tone: 'success', icon: 'check' },
  RETURNED: { text: 'Возвращено на доработку', tone: 'warning', icon: 'return' },
  REJECTED: { text: 'Отклонено', tone: 'danger', icon: 'cross' },
  SKIPPED: { text: 'Не понадобилось', tone: 'neutral', icon: 'close' },
};

export const DOCUMENT_STATUSES: DisplayStatus[] = ['DRAFT', 'IN_APPROVAL', 'IN_ENDORSEMENT', 'RETURNED', 'APPROVED', 'ENDORSED', 'REJECTED'];

export function documentStatusMeta(status: DisplayStatus): StatusMeta {
  return DOCUMENT_STATUS_META[status];
}

/** Автоматические отметки переопределяют текст решения — пояснение обязательно видно в истории (docs/DESIGN-DECISIONS.md, №10). */
export function stepDecisionMeta(decision: StepDecision, autoReason?: AutoReason): StatusMeta {
  if (decision === 'APPROVED' && autoReason === 'AUTHOR_HOLDS_ROLE') {
    return { text: 'Согласовано автором', tone: 'success', icon: 'check' };
  }
  if (decision === 'APPROVED' && autoReason === 'CARRIED_OVER') {
    return { text: 'Согласовано ранее', tone: 'success', icon: 'check' };
  }
  if (autoReason === 'MEMBER_REMOVED') {
    // Без глагола с родом: пол сотрудника неизвестен.
    return { text: 'Больше не участвует', tone: 'warning', icon: 'return' };
  }
  return STEP_DECISION_META[decision];
}
