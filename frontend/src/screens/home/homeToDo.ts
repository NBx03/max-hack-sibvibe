import type { DocumentListItem } from '../../api/types';

/**
 * «Ждут вас» на главной: сначала документы на вашем решении, затем ваши возвращённые — и то и другое
 * ждёт действия от вас. Пусто — главная показывает «Недавние документы».
 */
export function waitingForYou(
  waiting: DocumentListItem[],
  returned: DocumentListItem[],
): { item: DocumentListItem; list: 'waiting' | 'mine' }[] {
  return [
    ...waiting.map((item) => ({ item, list: 'waiting' as const })),
    ...returned.map((item) => ({ item, list: 'mine' as const })),
  ];
}
