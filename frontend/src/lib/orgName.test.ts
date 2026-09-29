import { describe, expect, it } from 'vitest';
import { orgInText } from './orgName';

describe('orgInText', () => {
  it('берёт простое название в кавычки', () => {
    expect(orgInText('Ромашка')).toBe('«Ромашка»');
  });

  it('не добавляет кавычки, если они уже есть в названии', () => {
    expect(orgInText('ООО «Жюри Один»')).toBe('ООО «Жюри Один»');
    expect(orgInText('ООО "Ромашка"')).toBe('ООО "Ромашка"');
  });
});
