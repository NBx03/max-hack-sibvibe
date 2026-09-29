import { describe, expect, it } from 'vitest';
import { DOCUMENT_STATUSES, documentStatusMeta, stepDecisionMeta } from './status';

describe('метки статусов', () => {
  it('у каждого показываемого статуса — своё слово и значок, не только цвет', () => {
    const texts = DOCUMENT_STATUSES.map((status) => documentStatusMeta(status).text);
    expect(new Set(texts).size).toBe(DOCUMENT_STATUSES.length);
    DOCUMENT_STATUSES.forEach((status) => expect(documentStatusMeta(status).icon).toBeTruthy());
    expect(documentStatusMeta('IN_ENDORSEMENT').text).toBe('На утверждении');
    expect(documentStatusMeta('ENDORSED').text).toBe('Утверждён');
  });

  it('решение человека: автоматические — с причиной', () => {
    expect(stepDecisionMeta('APPROVED').text).toBe('Согласовано');
    expect(stepDecisionMeta('APPROVED', 'AUTHOR_HOLDS_ROLE').text).toBe('Согласовано автором');
    expect(stepDecisionMeta('APPROVED', 'CARRIED_OVER').text).toBe('Согласовано ранее');
    expect(stepDecisionMeta('SKIPPED').text).toBe('Не понадобилось');
  });
});
