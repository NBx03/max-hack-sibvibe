import type { DocumentCard, UserRef } from '../../api/types';

/**
 * Демонстрация: чей сейчас ход по документу, если не того участника, от имени которого действует
 * проверяющий. Карточка предлагает перейти к нему одной кнопкой — без этого сценарий держался на README: после отправки
 * ничто не показывало, что дальше надо переключиться на согласующего.
 *
 * На согласовании — первый, кто ещё не решил на активном этапе; возвращён — автор. null — ход за текущим участником,
 * документ завершён или ходить некому.
 */
export function demoNextActor(
  doc: Pick<DocumentCard, 'status' | 'author' | 'route' | 'currentVersionNo'>,
  actingUserId: number,
  sandboxUserIds: number[],
): UserRef | null {
  const inSandbox = (user: UserRef) => user.id !== actingUserId && sandboxUserIds.includes(user.id);
  if (doc.status === 'RETURNED') {
    return inSandbox(doc.author) ? doc.author : null;
  }
  if (doc.status !== 'IN_APPROVAL' || !doc.route || doc.route.versionNo !== doc.currentVersionNo) {
    return null;
  }
  const active = doc.route.stages.find((stage) => stage.state === 'ACTIVE');
  const waiting = active?.steps.filter((step) => step.decision === 'PENDING') ?? [];
  // Ход уже у текущего участника — ему и решать, переходить некуда.
  if (waiting.some((step) => step.approver.id === actingUserId)) return null;
  return waiting.map((step) => step.approver).find(inSandbox) ?? null;
}
