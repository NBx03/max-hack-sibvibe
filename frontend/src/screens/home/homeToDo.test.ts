import { describe, expect, it } from 'vitest';
import type { DocumentListItem } from '../../api/types';
import { waitingForYou } from './homeToDo';

const item = (id: number) => ({ id }) as DocumentListItem;

describe('«Ждут вас» на главной', () => {
  it('сначала на вашем решении, затем ваши возвращённые — каждый со своим разделом', () => {
    expect(waitingForYou([item(1)], [item(2), item(3)]).map(({ item: doc, list }) => [doc.id, list])).toEqual([
      [1, 'waiting'], [2, 'mine'], [3, 'mine'],
    ]);
  });

  it('у согласующего без своих возвращённых — только его решения', () => {
    expect(waitingForYou([item(1), item(4)], [])).toHaveLength(2);
  });

  it('ничего не ждёт — пусто, и главная покажет недавние', () => {
    expect(waitingForYou([], [])).toEqual([]);
  });
});
