import { describe, expect, it } from 'vitest';
import { collapsibleItems, type TimelineItem } from './Timeline';

const item = (key: string, state: TimelineItem['state'], collapsible = true): TimelineItem =>
  ({ key, state, title: key, collapsible });

describe('какие этапы сворачиваются', () => {
  it('пройденные этапы до текущего', () => {
    const items = [item('создан', 'done', false), item('этап 1', 'done'), item('этап 2', 'done'), item('этап 3', 'current')];
    expect(collapsibleItems(items).map((entry) => entry.key)).toEqual(['этап 1', 'этап 2']);
  });

  it('пройденный этап после текущего не сворачивается и не считается', () => {
    // этап 3 сразу согласовал автор (у него одного роль Директора), этап 2 ещё идёт
    const items = [item('создан', 'done', false), item('этап 1', 'done'), item('этап 2', 'current'), item('этап 3', 'done'), item('итог', 'future', false)];
    expect(collapsibleItems(items).map((entry) => entry.key)).toEqual(['этап 1']);
  });

  it('документ завершён — сворачиваются все пройденные этапы', () => {
    const items = [item('создан', 'done', false), item('этап 1', 'done'), item('этап 2', 'done'), item('итог', 'done', false)];
    expect(collapsibleItems(items).map((entry) => entry.key)).toEqual(['этап 1', 'этап 2']);
  });
});
