import type { DocumentCard, DocumentVersion, FileRef, RouteView, StepView } from '../../api/types';
import { documentStatusMeta, stepDecisionMeta, type StatusMeta } from '../../ui';

/** Шаги маршрута этой версии: у текущей — этапы маршрута, у прошлых — история. */
export function versionSteps(doc: Pick<DocumentCard, 'currentVersionNo' | 'route'>, versionNo: number): StepView[] {
  if (!doc.route) return [];
  const steps = versionNo === doc.route.versionNo
    ? doc.route.stages.flatMap((stage) => stage.steps)
    : doc.route.history.filter((step) => step.versionNo === versionNo);
  return [...steps].sort((left, right) => left.stageOrder - right.stageOrder);
}

export interface VersionOutcome {
  meta: StatusMeta;
  /** Кто остановил версию — вернул, отклонил или выбыл; null — остановил не человек или никто. */
  step: StepView | null;
}

/**
 * Чем закончилась версия («у каждой версии должно быть видно её состояние»). Текущая — статусом документа, как
 * в шапке; прошлая — тем, почему понадобилась следующая: вернули, отозвал автор, выбыл согласующий или её заменили,
 * не отправляя.
 */
export function versionOutcome(
  doc: Pick<DocumentCard, 'currentVersionNo' | 'route' | 'displayStatus'>,
  version: Pick<DocumentVersion, 'versionNo' | 'withdrawnAt'>,
): VersionOutcome {
  const steps = versionSteps(doc, version.versionNo);
  if (version.withdrawnAt) {
    return { meta: { text: 'Отозвана автором', tone: 'warning', icon: 'return' }, step: null };
  }
  const rejected = steps.find((step) => step.decision === 'REJECTED');
  if (rejected) return { meta: { text: 'Отклонена', tone: 'danger', icon: 'cross' }, step: rejected };
  const returned = steps.find((step) => step.decision === 'RETURNED');
  if (returned) return { meta: { text: 'Возвращена на доработку', tone: 'warning', icon: 'return' }, step: returned };
  const removed = steps.find((step) => step.autoReason === 'MEMBER_REMOVED');
  if (removed) return { meta: { text: 'Вернулась автоматически', tone: 'warning', icon: 'return' }, step: removed };
  if (version.versionNo === doc.currentVersionNo) {
    return { meta: documentStatusMeta(doc.displayStatus), step: null };
  }
  if (steps.length === 0) {
    return { meta: { text: 'Не отправлялась', tone: 'neutral', icon: 'draft' }, step: null };
  }
  return { meta: { text: 'Заменена новой версией', tone: 'neutral', icon: 'draft' }, step: null };
}

/** Решение человека словом и значком; у утверждающего — «Утверждено» / «Ждёт утверждения». */
export function personMeta(step: StepView): StatusMeta {
  if (step.kind === 'ENDORSEMENT' && step.decision === 'APPROVED' && step.autoReason === 'CARRIED_OVER') {
    return { text: 'Утверждено ранее', tone: 'success', icon: 'endorse' };
  }
  if (step.kind === 'ENDORSEMENT' && step.decision === 'APPROVED' && !step.autoReason) {
    return { text: 'Утверждено', tone: 'success', icon: 'endorse' };
  }
  if (step.kind === 'ENDORSEMENT' && step.decision === 'PENDING') {
    return { text: 'Ждёт утверждения', tone: 'endorse', icon: 'clock' };
  }
  return stepDecisionMeta(step.decision, step.autoReason);
}

/** Последнее решение RETURNED - в текущем маршруте или в истории прошлых версий. */
export function findReturnReason(route: RouteView | null): StepView | null {
  if (!route) {
    return null;
  }
  const steps = [...route.stages.flatMap((stage) => stage.steps), ...route.history];
  const returned = steps.filter((step) => step.decision === 'RETURNED');
  return returned.reduce<StepView | null>((latest, step) => {
    if (!latest) return step;
    return (step.decidedAt ?? '') > (latest.decidedAt ?? '') ? step : latest;
  }, null);
}

/**
 * Возврат, по которому автор сейчас исправляет документ: возвращён, или уже черновик следующей версии, но ещё не
 * отправлен заново. Экран исправления показывает его комментарий — раньше автору приходилось помнить, что просили.
 */
export function pendingReturn(doc: Pick<DocumentCard, 'status' | 'route'>): StepView | null {
  if (doc.status !== 'RETURNED' && doc.status !== 'DRAFT') return null;
  const step = findReturnReason(doc.route);
  if (!step || !doc.route) return null;
  const later = [...doc.route.stages.flatMap((stage) => stage.steps), ...doc.route.history]
    .some((other) => other.versionNo > step.versionNo);
  return later ? null : step;
}

/**
 * Показывать ли «Изменения с версии N». Разница нужна тем, кто прошлую версию видел: если её не
 * отправляли (версия 1 до «Исправить в файле»), сравнивать не с чем. У завершённого документа пустое «не менялся» —
 * шум; на согласовании оно полезно: согласующий знает, что перечитывать не нужно.
 */
export function showVersionChanges(doc: Pick<DocumentCard, 'status' | 'changes' | 'route' | 'currentVersionNo'>): boolean {
  const changes = doc.changes;
  if (!changes || doc.status === 'DRAFT') return false;
  if (versionSteps(doc, changes.comparedToVersionNo).length === 0) return false;
  const empty = changes.files.length === 0 && changes.fields.length === 0 && !changes.contentChanged;
  return !(empty && (doc.status === 'APPROVED' || doc.status === 'REJECTED'));
}

/**
 * Итоговый файл — главное действие утверждённого или согласованного документа. У записки, заполненной в
 * приложении, файла нет по замыслу: её текст целиком на карточке, выгрузка в файл — WON'T (docs/DESIGN-DECISIONS.md) — тогда null,
 * и карточка без закреплённого действия.
 */
export function finalFile(doc: Pick<DocumentCard, 'status' | 'versions' | 'currentVersionNo'>): FileRef | null {
  if (doc.status !== 'APPROVED') return null;
  const current = doc.versions.find((version) => version.versionNo === doc.currentVersionNo);
  return current?.files.find((file) => file.kind === 'MAIN') ?? null;
}
