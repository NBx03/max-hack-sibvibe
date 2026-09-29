import { describe, expect, it } from 'vitest';
import { stageView } from './DocumentCardScreen';

describe('этап на таймлайне — по решениям людей', () => {
  const steps = (...decisions: ('PENDING' | 'APPROVED' | 'RETURNED' | 'REJECTED' | 'SKIPPED')[]) =>
    decisions.map((decision) => ({ decision }));

  it('все одобрили — пройден', () => {
    expect(stageView(steps('APPROVED', 'APPROVED'), false)).toEqual({ state: 'done', stop: null });
  });

  it('все «Не понадобилось» после отзыва или возврата — не наступил, а не пройден', () => {
    expect(stageView(steps('SKIPPED', 'SKIPPED'), false)).toEqual({ state: 'future', stop: null });
  });

  it('вернули или отклонили — точка остановки', () => {
    expect(stageView(steps('APPROVED', 'RETURNED', 'SKIPPED'), false).stop).toBe('RETURNED');
    expect(stageView(steps('REJECTED'), false).stop).toBe('REJECTED');
  });

  it('идёт сейчас — текущий', () => {
    expect(stageView(steps('APPROVED', 'PENDING'), true)).toEqual({ state: 'current', stop: null });
  });
});
